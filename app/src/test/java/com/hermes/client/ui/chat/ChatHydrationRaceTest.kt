package com.hermes.client.ui.chat

import com.hermes.client.data.network.*
import com.hermes.client.data.repository.*
import com.hermes.client.domain.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHydrationRaceTest {
    private val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val chat = mockk<ChatRepository>(relaxed = true)
    private val sessions = mockk<SessionRepository>(relaxed = true)
    private val profiles = mockk<ProfileManager>(relaxed = true)
    private val favorites = mockk<ModelFavoritesStore>(relaxed = true)
    private val tts = mockk<com.hermes.client.data.tts.TextToSpeechController>(relaxed = true)
    private val prompts = mockk<PromptStore>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { chat.events } returns events
        every { chat.connectionState } returns connection
        every { profiles.active } returns MutableStateFlow<String?>(null)
        every { favorites.favorites } returns MutableStateFlow(emptySet())
        every { tts.speaking } returns MutableStateFlow(false)
        every { prompts.prompts } returns MutableStateFlow(emptyList())
        coEvery { sessions.historySnapshot(any(), any()) } returns TimelineSnapshot()
        coEvery { chat.resumeSnapshot(any(), any()) } returns ResumeDto(sessionId = "live")
    }
    private fun vm() = ChatViewModel(chat, sessions, mockk(relaxed = true), mockk(relaxed = true), profiles,
        favorites, com.hermes.client.share.PendingShareStore(), tts, prompts, mockk(relaxed = true))
    private fun event(type: String, text: String) = ServerEvent(type, "live", buildJsonObject { put("text", text) })
    private fun warm() = ResumeDto(sessionId = "live", running = true, inflight = InflightDto(
        user = "original", assistant = "prefixsuffix", streaming = true,
        corrections = listOf("correction"), correctionOffsets = listOf(6)))
    private fun messages(state: ChatUiState) = state.timeline.entries.mapNotNull { it.body as? TimelineBody.Message }.map { it.content.text }
    private fun assertRecovered(state: ChatUiState) {
        assertTrue(messages(state).containsAll(listOf("prefix", "suffix", "correction")))
        assertTrue(state.timeline.completeness.overlapUnconfirmed)
        assertTrue(state.timeline.entries.any { (it.body as? TimelineBody.Notice)?.kind == "resume-overlap-unconfirmed" })
        assertFalse(messages(state).contains("suffixsuffix"))
    }

    @Test fun initialization_partial_buffered_suffix_keeps_warm_prefix_and_correction() = runTest {
        val resume = CompletableDeferred<ResumeDto>()
        coEvery { chat.resumeSnapshot(any(), any()) } coAnswers { resume.await() }
        val vm = vm()
        vm.open("stored"); advanceUntilIdle()
        events.emit(event("message.delta", "suffix")); advanceUntilIdle()
        resume.complete(warm()); advanceUntilIdle()
        assertRecovered(vm.state.value)
        assertEquals(2, messages(vm.state.value).count { it == "suffix" })
    }

    @Test fun reconnect_partial_buffered_suffix_keeps_warm_prefix_and_correction() = runTest {
        val vm = vm()
        vm.open("stored"); advanceUntilIdle()
        connection.value = ConnectionState.Reconnecting; advanceUntilIdle()
        val resume = CompletableDeferred<ResumeDto>()
        coEvery { chat.resumeSnapshot(any(), any()) } coAnswers { resume.await() }
        connection.value = ConnectionState.Connected; advanceUntilIdle()
        events.emit(event("message.delta", "suffix")); advanceUntilIdle()
        resume.complete(warm()); advanceUntilIdle()
        assertRecovered(vm.state.value)
        assertEquals(2, messages(vm.state.value).count { it == "suffix" })
    }

    @Test fun deferred_rest_before_final_then_stopped_resume_keeps_buffered_completion() = runTest {
        val history = CompletableDeferred<TimelineSnapshot>()
        val resume = CompletableDeferred<ResumeDto>()
        coEvery { sessions.historySnapshot(any(), any()) } coAnswers { history.await() }
        coEvery { chat.resumeSnapshot(any(), any()) } coAnswers { resume.await() }
        val vm = vm()
        vm.open("stored"); advanceUntilIdle()
        history.complete(TimelineSnapshot()); advanceUntilIdle()
        events.emit(ServerEvent("message.complete", "live", buildJsonObject { put("text", "final"); put("reasoning", "reason") }))
        advanceUntilIdle()
        resume.complete(ResumeDto(sessionId = "live", running = false)); advanceUntilIdle()
        assertEquals(listOf("final"), messages(vm.state.value))
        val terminal = vm.state.value.timeline.entries.filter { it.body is TimelineBody.Message || it.body is TimelineBody.Reasoning }
        assertTrue(terminal.all { it.orderEvidence == OrderEvidence.RESUMED_UNKNOWN && it.source.turnId == null })
        assertEquals("reason", (terminal.last().body as TimelineBody.Reasoning).text)
        assertFalse(vm.state.value.timeline.generating)
    }

    @Test fun stopped_reconnect_snapshot_does_not_discard_later_buffered_final() = runTest {
        val vm = vm()
        vm.open("stored"); advanceUntilIdle()
        events.emit(ServerEvent("message.start", "live", buildJsonObject {}))
        events.emit(event("message.delta", "partial")); advanceUntilIdle()
        connection.value = ConnectionState.Reconnecting; advanceUntilIdle()
        val fetched = CompletableDeferred<TimelineSnapshot>()
        coEvery { sessions.historySnapshot(any(), any()) } coAnswers { fetched.await() }
        connection.value = ConnectionState.Connected; advanceUntilIdle()
        events.emit(event("message.complete", "final")); advanceUntilIdle()
        fetched.complete(TimelineSnapshot()); advanceUntilIdle()
        assertTrue(messages(vm.state.value).containsAll(listOf("partial", "final")))
        assertEquals(OrderEvidence.RESUMED_UNKNOWN, vm.state.value.timeline.entries.last().orderEvidence)
        assertFalse(vm.state.value.timeline.generating)
    }
}
