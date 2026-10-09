package com.hermes.client.ui.chat

import app.cash.turbine.test
import com.hermes.client.domain.withAuthoredPrompt
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelFavoritesStore
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    private val connectionStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)
    private val modelRepo = mockk<ModelRepository>(relaxed = true)
    private val profileRepo = mockk<ProfileRepository>(relaxed = true)
    private val profileManager = mockk<com.hermes.client.data.repository.ProfileManager>(relaxed = true)
    private val favoritesStore = mockk<ModelFavoritesStore>(relaxed = true)
    private val pendingShareStore = com.hermes.client.share.PendingShareStore()
    private val tts = mockk<com.hermes.client.data.tts.TextToSpeechController>(relaxed = true)
    private val promptStore = mockk<com.hermes.client.data.repository.PromptStore>(relaxed = true)
    private val configRepo = mockk<com.hermes.client.data.repository.ConfigRepository>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { chatRepo.events } returns events
        every { chatRepo.connectionState } returns connectionStateFlow
        // resume returns null here so the ViewModel keeps the opened id stable for these tests
        // (production switches to the live handle resume returns).
        coEvery { chatRepo.resumeSnapshot(any(), any()) } answers { com.hermes.client.data.network.ResumeDto(sessionId = firstArg()) }
        every { profileManager.active } returns MutableStateFlow<String?>(null)
        coEvery { sessionRepo.historySnapshot(any(), any()) } returns com.hermes.client.domain.TimelineSnapshot()
        coEvery { modelRepo.options() } returns emptyList()
        coEvery { modelRepo.providers() } returns emptyList()
        coEvery { profileRepo.list() } returns emptyList()
        every { favoritesStore.favorites } returns MutableStateFlow(emptySet())
        every { tts.speaking } returns MutableStateFlow(false)
        every { promptStore.prompts } returns MutableStateFlow(emptyList())
    }

    @Test fun private_request_response_never_enters_chat_and_registration_is_explicit() = runTest {
        val vm = buildVm()
        vm.registerServerRequestRenderer()
        io.mockk.verify { chatRepo.setServerRequestHandlerAvailable(true) }
        vm.open("s1")
        advanceUntilIdle()
        val before = vm.state.value
        val request = com.hermes.client.data.network.PendingServerRequest("fixture-wire", "vault.code", "s1",
            buildJsonObject { put("session_id", "s1"); put("site", "example.invalid") }, 1, false)
        every { chatRepo.respondServerRequest(request, any()) } returns com.hermes.client.data.network.RespondOutcome.ENQUEUED
        val result = buildJsonObject { put("value", "SYNTHETIC_PRIVATE_CANARY") }
        assertEquals(com.hermes.client.data.network.RespondOutcome.ENQUEUED, vm.respondServerRequest(request, result))
        assertEquals(before.timeline.entries, vm.state.value.timeline.entries)
        assertEquals(before.messages, vm.state.value.messages)
        assertFalse(vm.state.value.toString().contains("SYNTHETIC_PRIVATE_CANARY"))
        io.mockk.verify(exactly = 1) { chatRepo.respondServerRequest(request, result) }
    }

    @Test fun legacy_approval_failure_preserves_pending_marker_and_stale_request_cannot_send() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        events.emit(ServerEvent("approval.request", "s1", buildJsonObject {
            put("request_id", "fixture-queue"); put("command", "synthetic command")
        }))
        advanceUntilIdle()
        val request = vm.state.value.pendingApproval!!
        assertEquals("fixture-queue", request.requestId)
        coEvery { chatRepo.respondApproval(any(), any(), any()) } throws IllegalStateException("synthetic failure")
        assertFalse(vm.respondLegacyApproval(request, ApprovalChoice.DENY))
        assertTrue(vm.state.value.pendingApproval === request)
        val marker = vm.state.value.timeline.entries.last().body as com.hermes.client.domain.TimelineBody.Interaction
        assertEquals(com.hermes.client.domain.InteractionState.PENDING, marker.state)
        vm.open("s2")
        advanceUntilIdle()
        assertFalse(vm.respondLegacyApproval(request, ApprovalChoice.ONCE))
        coVerify(exactly = 1) { chatRepo.respondApproval("s1", ApprovalChoice.DENY, "fixture-queue") }
    }

    private fun buildVm() = ChatViewModel(chatRepo, sessionRepo, modelRepo, profileRepo, profileManager, favoritesStore, pendingShareStore, tts, promptStore, configRepo)

    @Test fun streamed_delta_appears_in_state() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.state.test {
            awaitItem() // initial empty (or current) state
            events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("session_id", "s1") }))
            events.emit(ServerEvent("message.delta", "s1", buildJsonObject { put("session_id", "s1"); put("text", "Hi") }))
            advanceUntilIdle()
            val latest = expectMostRecentItem()
            assertEquals("Hi", latest.messages.last().text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * C2: when connectionState transitions Reconnecting → Connected (not the first Connected),
     * chat.resume() must be called a second time to re-attach the agent stream.
     */
    @Test fun reconnect_triggers_second_resume() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        // open() already called resume once; now simulate a reconnect cycle
        connectionStateFlow.value = ConnectionState.Reconnecting
        advanceUntilIdle()
        connectionStateFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        // resume must have been called exactly twice: once in open(), once on reconnect
        coVerify(exactly = 2) { chatRepo.resumeSnapshot("s1", null) }
    }

    /**
     * Profile bug: session-scoped WebSocket RPCs must carry the active profile, or the gateway
     * resolves session.resume against the wrong profile's DB and returns "session not found"
     * (4007) — which then makes the next prompt.submit fail too. open() must pass the active
     * profile to resume so a session that lives in a non-default profile can be reattached.
     */
    @Test fun open_resumes_with_active_profile() = runTest {
        every { profileManager.active } returns MutableStateFlow<String?>("personal")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        coVerify { chatRepo.resumeSnapshot("s1", "personal") }
    }

    /**
     * A pending image share must be staged as a local attachment chip (not attached to the
     * gateway immediately) — it's flushed on the next send() using whatever the live
     * post-resume sessionId is at that time.
     */
    @Test fun open_stages_pending_share_image_as_attachment() = runTest {
        coEvery { chatRepo.resumeSnapshot("s1", null) } returns com.hermes.client.data.network.ResumeDto(sessionId = "s1-live")
        pendingShareStore.put(
            "s1",
            // Valid base64 (decodes to "abc") — real decode logic runs in unit tests.
            com.hermes.client.share.PendingShare(imageBase64 = "YWJj", imageMime = "image/png"),
        )
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.attachImageBytes(any(), any(), any()) }
        assertEquals(1, vm.state.value.pendingAttachments.size)
        assertEquals("image/png", vm.state.value.pendingAttachments.first().mimeType)
    }

    /** A text-only pending share (no image) must not trigger an attach call at all. */
    @Test fun open_with_text_only_pending_share_does_not_attach_image() = runTest {
        pendingShareStore.put("s1", com.hermes.client.share.PendingShare(text = "hello"))
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepo.attachImageBytes(any(), any(), any()) }
        assertEquals("hello", vm.initialDraft.value)
    }

    /**
     * I3: when connectionState enters Reconnecting while generation is in progress,
     * the in-flight assistant message must be marked interrupted and isGenerating cleared.
     */
    @Test fun reconnecting_while_generating_marks_interrupted() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        // Start a generation via message.start event
        events.emit(ServerEvent("message.start", "s1", buildJsonObject { put("message_id", "m1") }))
        advanceUntilIdle()
        assertTrue("should be generating after message.start", vm.state.value.isGenerating)

        // Simulate connection drop while generating
        connectionStateFlow.value = ConnectionState.Reconnecting
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("transport loss does not terminate generation", s.isGenerating)
        assertFalse(s.timeline.ready)
        val lastMsg = s.messages.lastOrNull()
        assertTrue("last message stays streaming until terminal evidence", lastMsg?.isStreaming == true)
    }

    /**
     * C2 edge case: the very first Connected (startup) must NOT trigger a second resume.
     */
    @Test fun initial_connected_does_not_double_resume() = runTest {
        val vm = buildVm()
        // Start with Connecting, then transition to Connected (first connect)
        connectionStateFlow.value = ConnectionState.Connecting
        vm.open("s1")
        advanceUntilIdle()
        connectionStateFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        // Only one resume from open(); the Connected transition had prev==Connecting (not Reconnecting)
        coVerify(exactly = 1) { chatRepo.resumeSnapshot("s1", null) }
    }

    // Changing the model inside a chat (SESSION scope, the default) must switch THIS session's
    // model (a `/model … --session` slash), not the global default — otherwise a session pinned
    // to an unavailable model keeps failing with "model is not available in session" no matter
    // how often the picker is used.
    @Test fun onSelectFromSheet_session_success_switches_via_slash() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.SESSION)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        coVerify { chatRepo.slashExec("s1", "/model opus --provider anthropic --session") }
        assertEquals("opus", vm.currentModel.value)
        assertTrue("success must clear any sheet error", vm.modelSheet.value.error == null)
        assertTrue("onDone must be invoked so the caller dismisses the sheet", onDoneCalled)
    }

    // A worker failure ("slash worker closed pipe") throws — it must surface in the sheet's error
    // (not the chat transcript), and the sheet must stay open (onDone not invoked) so the user can
    // retry or pick a different model.
    @Test fun onSelectFromSheet_session_failure_surfaces_sheet_error() = runTest {
        coEvery { chatRepo.slashExec("s1", any()) } throws RuntimeException("slash worker closed pipe")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.SESSION)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        assertTrue("a failed switch must surface a sheet error", vm.modelSheet.value.error != null)
        assertFalse("the sheet must stay open on failure", onDoneCalled)
    }

    // DEFAULT scope sets the global default model via REST, not the session slash.
    @Test fun onSelectFromSheet_default_success_sets_default_model() = runTest {
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.DEFAULT)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        coVerify { modelRepo.set("anthropic", "opus") }
        assertTrue("success must clear any sheet error", vm.modelSheet.value.error == null)
        assertTrue("onDone must be invoked so the caller dismisses the sheet", onDoneCalled)
    }

    @Test fun onSelectFromSheet_default_failure_surfaces_sheet_error() = runTest {
        coEvery { modelRepo.set(any(), any()) } throws RuntimeException("could not set default")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()

        var onDoneCalled = false
        vm.onSheetScope(com.hermes.client.ui.models.ModelScope.DEFAULT)
        vm.onSelectFromSheet("anthropic", "opus") { onDoneCalled = true }
        advanceUntilIdle()

        assertTrue("a failed default switch must surface a sheet error", vm.modelSheet.value.error != null)
        assertFalse("the sheet must stay open on failure", onDoneCalled)
    }

    @Test fun selectProfile_calls_profileRepo_setActive() = runTest {
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()

        vm.selectProfile("personal")
        advanceUntilIdle()

        coVerify { profileRepo.setActive("personal") }
    }

    @Test fun readAloud_speaks_markdown_stripped_text() {
        val vm = buildVm()
        vm.readAloud("**hi** `there`")
        io.mockk.verify { tts.speak("hi there") }
    }

    @Test fun stopReading_stops_tts() {
        val vm = buildVm()
        vm.stopReading()
        io.mockk.verify { tts.stop() }
    }

    @Test fun setPersona_sends_personality_slash() = runTest {
        val vm = buildVm()
        vm.setPersona("witty"); advanceUntilIdle()
        io.mockk.coVerify { chatRepo.slashExec(any(), "/personality witty") }
    }

    @Test fun setPersona_null_clears_with_none() = runTest {
        val vm = buildVm()
        vm.setPersona(null); advanceUntilIdle()
        io.mockk.coVerify { chatRepo.slashExec(any(), "/personality none") }
    }

    // chat.slashExec returns command-level errors in its output string (only transport failures
    // throw), so a gateway rejection of an unknown persona must surface as an error, not silently
    // set active — otherwise the UI would show a persona as applied when the gateway refused it.
    @Test fun setPersona_rejection_surfaces_error_and_does_not_set_active() = runTest {
        coEvery { chatRepo.slashExec(any(), any()) } returns "unknown personality: x"
        val vm = buildVm()
        vm.setPersona("bad"); advanceUntilIdle()

        assertTrue("a gateway rejection must surface a persona error", vm.personaUi.value.error != null)
        assertEquals(null, vm.personaUi.value.active)
    }

    @Test fun blocked_history_buffers_early_handle_events_and_blocks_send_until_ready() = runTest {
        val history = kotlinx.coroutines.CompletableDeferred<com.hermes.client.domain.TimelineSnapshot>()
        coEvery { sessionRepo.historySnapshot("s1", null) } coAnswers { history.await() }
        coEvery { chatRepo.resumeSnapshot("s1", null) } returns com.hermes.client.data.network.ResumeDto(sessionId = "live")
        val vm = buildVm()
        vm.open("s1")
        advanceUntilIdle()
        vm.send("must not submit")
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
        events.emit(ServerEvent("message.start", "live", buildJsonObject {}))
        events.emit(ServerEvent("message.delta", "live", buildJsonObject { put("text", "early") }))
        advanceUntilIdle()
        history.complete(com.hermes.client.domain.TimelineSnapshot())
        advanceUntilIdle()
        assertTrue(vm.state.value.timeline.ready)
        assertEquals("early", (vm.state.value.timeline.entries.last().body as com.hermes.client.domain.TimelineBody.Message).content.text)
        vm.send("now")
        advanceUntilIdle()
        coVerify { chatRepo.submit("live", "now") }
    }

    @Test fun reopening_cancels_and_epoch_guards_even_non_cancellable_old_history() = runTest {
        val old = kotlinx.coroutines.CompletableDeferred<com.hermes.client.domain.TimelineSnapshot>()
        coEvery { sessionRepo.historySnapshot("A", null) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { old.await() }
        }
        val vm = buildVm()
        vm.open("A"); advanceUntilIdle()
        vm.open("B"); advanceUntilIdle()
        events.emit(ServerEvent("message.start", "B", buildJsonObject {}))
        events.emit(ServerEvent("message.delta", "B", buildJsonObject { put("text", "B content") }))
        advanceUntilIdle()
        old.complete(com.hermes.client.domain.TimelineSnapshot().withAuthoredPrompt("A stale"))
        advanceUntilIdle()
        assertEquals("B content", (vm.state.value.timeline.entries.last().body as com.hermes.client.domain.TimelineBody.Message).content.text)
        coVerify(exactly = 0) { chatRepo.resumeSnapshot("A", any()) }
        vm.send("B prompt"); advanceUntilIdle()
        coVerify { chatRepo.submit("B", "B prompt") }
    }

    @Test fun profile_is_captured_once_across_history_and_resume_suspensions() = runTest {
        val active = MutableStateFlow<String?>("first")
        every { profileManager.active } returns active
        val history = kotlinx.coroutines.CompletableDeferred<com.hermes.client.domain.TimelineSnapshot>()
        coEvery { sessionRepo.historySnapshot("s1", "first") } coAnswers { history.await() }
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        active.value = "second"
        history.complete(com.hermes.client.domain.TimelineSnapshot())
        advanceUntilIdle()
        coVerify { chatRepo.resumeSnapshot("s1", "first") }
        coVerify(exactly = 0) { chatRepo.resumeSnapshot("s1", "second") }
    }

    @Test fun resume_failure_does_not_enable_send_or_erase_loaded_history() = runTest {
        coEvery { sessionRepo.historySnapshot("s1", null) } returns com.hermes.client.domain.TimelineSnapshot().withAuthoredPrompt("loaded")
        coEvery { chatRepo.resumeSnapshot("s1", null) } throws IllegalStateException("synthetic failure")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        assertFalse(vm.state.value.timeline.ready)
        assertEquals("loaded", com.hermes.client.domain.lastUserPrompt(vm.state.value.timeline))
        vm.send("no"); advanceUntilIdle()
        coVerify(exactly = 0) { chatRepo.submit(any(), any()) }
    }

    @Test fun old_reconnect_result_cannot_rebind_new_open() = runTest {
        val old = kotlinx.coroutines.CompletableDeferred<com.hermes.client.data.network.ResumeDto>()
        val vm = buildVm()
        vm.open("A"); advanceUntilIdle()
        coEvery { chatRepo.resumeSnapshot("A", null) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { old.await() }
        }
        connectionStateFlow.value = ConnectionState.Reconnecting; advanceUntilIdle()
        connectionStateFlow.value = ConnectionState.Connected; advanceUntilIdle()
        vm.open("B"); advanceUntilIdle()
        old.complete(com.hermes.client.data.network.ResumeDto(sessionId = "old-live"))
        advanceUntilIdle()
        vm.send("new"); advanceUntilIdle()
        coVerify { chatRepo.submit("B", "new") }
        coVerify(exactly = 0) { chatRepo.submit("old-live", any()) }
    }

    @Test fun old_resume_completing_last_does_not_rebind_or_stage_share_in_new_session() = runTest {
        val old = kotlinx.coroutines.CompletableDeferred<com.hermes.client.data.network.ResumeDto>()
        coEvery { chatRepo.resumeSnapshot("A", null) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { old.await() }
        }
        pendingShareStore.put("A", com.hermes.client.share.PendingShare(imageBase64 = "YWJj", imageMime = "image/png"))
        val vm = buildVm()
        vm.open("A"); advanceUntilIdle()
        vm.open("B"); advanceUntilIdle()
        old.complete(com.hermes.client.data.network.ResumeDto(sessionId = "stale")); advanceUntilIdle()
        assertTrue(vm.state.value.pendingAttachments.isEmpty())
        vm.send("new"); advanceUntilIdle()
        coVerify { chatRepo.submit("B", "new") }
        coVerify(exactly = 0) { chatRepo.submit("stale", any()) }
    }

    @Test fun resume_unauthorized_sets_navigation_flag_and_keeps_ready_false() = runTest {
        coEvery { chatRepo.resumeSnapshot("s1", null) } throws com.hermes.client.data.network.HermesApiException(401, "synthetic unauthorized")
        val vm = buildVm()
        vm.open("s1"); advanceUntilIdle()
        assertTrue(vm.unauthorized.value)
        assertFalse(vm.state.value.timeline.ready)
    }
}
