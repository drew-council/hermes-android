package com.hermes.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ProfileRepository
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.*
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.serialization.json.put

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sessions: SessionRepository,
    private val modelRepo: ModelRepository,
    private val profileRepo: ProfileRepository,
    private val profileManager: ProfileManager,
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
    private val pendingShareStore: com.hermes.client.share.PendingShareStore,
    private val tts: com.hermes.client.data.tts.TextToSpeechController,
    private val promptStore: com.hermes.client.data.repository.PromptStore,
    private val configRepo: com.hermes.client.data.repository.ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState.empty())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = chat.connectionState
    val pendingServerRequests get() = chat.pendingServerRequests
    private val _liveSessionId = MutableStateFlow<String?>(null)
    val liveSessionId: StateFlow<String?> = _liveSessionId.asStateFlow()

    // Registration describes installed handlers, not the visibility of a card.
    fun registerServerRequestRenderer() = chat.setServerRequestHandlerAvailable(true)
    fun respondServerRequest(request: com.hermes.client.data.network.PendingServerRequest,
                             result: kotlinx.serialization.json.JsonObject): com.hermes.client.data.network.RespondOutcome {
        if (request.sessionId != sessionId || _liveSessionId.value != sessionId || !_state.value.timeline.ready)
            return com.hermes.client.data.network.RespondOutcome.STALE
        val outcome = chat.respondServerRequest(request, result)
        if (outcome == com.hermes.client.data.network.RespondOutcome.ENQUEUED && request.sessionId == sessionId) {
            val queueId = (request.params["request_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            _state.update { state ->
                val entries = state.timeline.entries.map { entry ->
                    val body = entry.body as? TimelineBody.Interaction
                    if (body?.kind == request.method && body.requestId != null &&
                        (body.requestId == request.id || (request.method == "approval" && body.requestId == queueId)))
                        entry.copy(body = body.copy(state = InteractionState.ANSWERED, text = "Response sent (not acknowledged)"))
                    else entry
                }
                state.copy(timeline = state.timeline.copy(entries = entries, revision = state.timeline.revision + 1),
                    pendingApproval = state.pendingApproval?.takeUnless { request.method == "approval" && queueId != null && it.requestId == queueId },
                    pendingClarify = state.pendingClarify?.takeUnless { request.method == "clarify" && it.requestId == request.id })
            }
        }
        return outcome
    }

    // I1: expose 401 unauthorized so the nav layer can route back to Setup
    private val _unauthorized = MutableStateFlow(false)
    val unauthorized: StateFlow<Boolean> = _unauthorized.asStateFlow()

    // The model this session is confirmed to be using. Null until a switch succeeds (the gateway
    // doesn't report the session's current model up-front), so the picker shows "Model" until the
    // user changes it, then the chosen model as confirmation the switch took.
    private val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    // Provider list for the model sheet (grouped by real slug); loaded alongside options.
    private val _providers = MutableStateFlow<List<com.hermes.client.data.network.ModelProviderDto>>(emptyList())
    val providers: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.network.ModelProviderDto>> = _providers.asStateFlow()

    // Provider of the confirmed session model (set together with _currentModel on a successful switch).
    private val _currentProvider = MutableStateFlow<String?>(null)
    val currentProvider: kotlinx.coroutines.flow.StateFlow<String?> = _currentProvider.asStateFlow()

    // Text handed off from a share (Share-to-Hermes). ChatScreen pre-fills the composer with it once.
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()
    fun clearInitialDraft() { _initialDraft.value = null }

    val favorites: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        favoritesStore.favorites.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True while a response is being read aloud. */
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean> = tts.speaking

    /** Read [text] aloud (markdown stripped for cleaner speech). */
    fun readAloud(text: String) = tts.speak(speechText(text))

    /** Stop any current read-aloud. */
    fun stopReading() = tts.stop()

    /** Device-local saved prompts, for the composer's prompt picker. */
    val savedPrompts: kotlinx.coroutines.flow.StateFlow<List<com.hermes.client.data.repository.SavedPrompt>> =
        promptStore.prompts.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    data class ModelSheetUi(
        val query: String = "",
        val scope: com.hermes.client.ui.models.ModelScope = com.hermes.client.ui.models.ModelScope.SESSION,
        val pending: Boolean = false,
        val error: String? = null,
    )
    private val _modelSheet = MutableStateFlow(ModelSheetUi())
    val modelSheet: kotlinx.coroutines.flow.StateFlow<ModelSheetUi> = _modelSheet.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileDto>>(emptyList())
    val profiles: StateFlow<List<ProfileDto>> = _profiles.asStateFlow()

    /** Active profile name — shown in the chat top bar so the user knows which tenant they're in. */
    private val _ownerProfile = MutableStateFlow<String?>(null)
    val activeProfile: StateFlow<String?> = _ownerProfile.asStateFlow()

    /** Slash-command catalog (name to description) for the composer palette. */
    private val _commands = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val commands: StateFlow<List<Pair<String, String>>> = _commands.asStateFlow()

    /** "@" mention completions for the current @-word in the composer. */
    private val _pathItems = MutableStateFlow<List<com.hermes.client.data.repository.PathItem>>(emptyList())
    val pathItems: StateFlow<List<com.hermes.client.data.repository.PathItem>> = _pathItems.asStateFlow()

    private var sessionId: String = ""
    private var collectJob: Job? = null
    private var connJob: Job? = null
    private var initializationJob: Job? = null
    private var recoveryJob: Job? = null
    private var epoch: Long = 0

    fun open(id: String, ownerProfile: String? = null) {
        val owner = ++epoch
        val profileReady = kotlinx.coroutines.CompletableDeferred<String?>()
        initializationJob?.cancel()
        recoveryJob?.cancel()
        collectJob?.cancel()
        connJob?.cancel()
        sessionId = id
        _liveSessionId.value = null
        _ownerProfile.value = ownerProfile
        _state.value = ChatUiState.empty()
        _initialDraft.value = null
        val ps = pendingShareStore.take(id)
        ps?.text?.let { _initialDraft.value = it }
        val buffered = mutableListOf<com.hermes.client.data.network.ServerEvent>()
        var initializing = true
        var recovering = false
        var bufferTruncated = false
        // Subscribe before any suspension. Unknown handles are quarantined until resume binds one.
        collectJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            chat.events.collect { event ->
                if (owner != epoch || event.sessionId == null) return@collect
                if (initializing || recovering) {
                    if (buffered.size == 2048) { buffered.removeAt(0); bufferTruncated = true }
                    buffered += event
                }
                else if (event.sessionId == sessionId) _state.value = _state.value.reduce(event)
            }
        }
        // Direct/restored chat entry must not depend on notification-service startup.
        chat.connect()
        initializationJob = viewModelScope.launch {
            val profile = ownerProfile ?: run {
                profileManager.refresh()
                profileManager.active.value
            }
            if (owner != epoch) return@launch
            _ownerProfile.value = profile
            profileReady.complete(profile)
            var historyFailure: Exception? = null
            var history = try {
                sessions.historySnapshot(id, profile)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (owner != epoch) return@launch
                if (e is HermesApiException && e.code == 401) { _unauthorized.value = true; return@launch }
                historyFailure = e
                TimelineSnapshot(entries = listOf(TimelineEntry("history-unavailable", SourceRef(profile, id),
                    TimelineBody.Notice("history-unavailable", "History unavailable (${sessionFailureStatus("history", e)})"), OrderEvidence.RESUMED_UNKNOWN)))
            }
            if (owner != epoch) return@launch
            val resume = try { chat.resumeSnapshot(id, profile) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (owner != epoch) return@launch
                    if (e is HermesApiException && e.code == 401) _unauthorized.value = true
                    _state.value = ChatUiState(messages = history.legacyMessages(), timeline = history)
                    appendError("Couldn't resume session (${sessionFailureStatus("resume", e)})")
                    initializing = false
                    recovering = true
                    buffered.clear()
                    return@launch
                }
            if (owner != epoch) return@launch
            val handle = resume.sessionId
            if (handle == null) {
                _state.value = ChatUiState(messages = history.legacyMessages(), timeline = history)
                appendError("Couldn't resume session (resume: missing-handle)")
                initializing = false
                recovering = true
                buffered.clear()
                return@launch
            }
            // Only the backend's live-unpersisted response proves that this 404 is a normal
            // empty draft. Never hide missing/failed history for an existing stored session.
            if ((historyFailure as? HermesApiException)?.code == 404 &&
                resume.storedSessionId == id && resume.messageCount == 0 && resume.messages.isEmpty()) {
                history = TimelineSnapshot(completeness = HistoryCompleteness(
                    ownerProfile = profile, resolvedSessionId = id, mayHaveOlderRows = false))
            }
            sessionId = handle
            _liveSessionId.value = handle
            val matching = buffered.filter { it.sessionId == handle }
            // Any buffered observation may be only a suffix, not the whole accumulated warm turn.
            val hydrated = history.projectResume(resume, profile, id, hasLiveOverlay = matching.isNotEmpty()).let {
                it.copy(completeness = it.completeness.copy(liveEventsIncomplete = bufferTruncated))
            }
            if (!hydrated.ready) {
                _state.value = ChatUiState(messages = hydrated.legacyMessages(), timeline = hydrated)
                initializing = false
                recovering = true
                buffered.clear()
                return@launch
            }
            var installed = ChatUiState(messages = hydrated.legacyMessages(), timeline = hydrated,
                isGenerating = resume.running, resumeSnapshot = resume)
            // Without an intervening suspension, install base + buffered events atomically.
            matching.forEach { installed = installed.reduce(it) }
            _state.value = installed
            buffered.clear()
            initializing = false
            recovering = false
            ps?.let { share ->
                if (share.imageBase64 != null && share.imageMime != null) {
                    try { stageAttachment(java.util.Base64.getDecoder().decode(share.imageBase64), share.imageMime) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { appendError("Attach failed: ${e.message}") }
                }
            }
            launch { runCatching { modelRepo.providers() }.onSuccess { if (owner == epoch) _providers.value = it }.onFailure { if (it is CancellationException) throw it } }
            launch { runCatching { profileRepo.list() }.onSuccess { if (owner == epoch) _profiles.value = it }.onFailure { if (it is CancellationException) throw it } }
            launch { runCatching { chat.commandsCatalog() }.onSuccess { if (owner == epoch) _commands.value = it }.onFailure { if (it is CancellationException) throw it } }
        }
        connJob = viewModelScope.launch {
            var previous: ConnectionState? = null
            chat.connectionState.collect { cur ->
                if (owner != epoch) return@collect
                if (cur is ConnectionState.Reconnecting || cur is ConnectionState.Error ||
                    (cur is ConnectionState.Connecting && previous != null && !initializing)) {
                    // Losing transport is not evidence that server generation ended.
                    _state.value = _state.value.copy(timeline = _state.value.timeline.copy(ready = false, completeness = _state.value.timeline.completeness.copy(liveEventsIncomplete = true)))
                    recovering = true
                }
                if (cur is ConnectionState.Connected && recovering && !initializing) {
                    recoveryJob?.cancel()
                    recoveryJob = launch {
                        try {
                            val profile = profileReady.await()
                            val resume = chat.resumeSnapshot(id, profile)
                            if (owner != epoch) return@launch
                            val handle = resume.sessionId ?: return@launch
                            sessionId = handle
                            _liveSessionId.value = handle
                            val fetched = try { sessions.historySnapshot(id, profile) }
                            catch (e: HermesApiException) {
                                if (e.code == 404 && resume.storedSessionId == id &&
                                    resume.messageCount == 0 && resume.messages.isEmpty()) TimelineSnapshot(
                                    completeness = HistoryCompleteness(ownerProfile = profile,
                                        resolvedSessionId = id, mayHaveOlderRows = false))
                                else throw e
                            }
                            if (owner != epoch) return@launch
                            val existing = _state.value.timeline.reconcileHistory(fetched)
                            val matching = buffered.filter { it.sessionId == handle }
                            var installed = _state.value.copy(timeline = existing.projectResume(resume, profile, id,
                                hasLiveOverlay = matching.isNotEmpty() ||
                                    (existing.activeTurn != null && existing.entries.any { it.source.turnId == existing.activeTurn })),
                                isGenerating = resume.running, resumeSnapshot = resume,
                                messages = if (resume.running) _state.value.messages else _state.value.messages.map { it.copy(isStreaming = false) })
                            if (!installed.timeline.ready) { _state.value = installed; return@launch }
                            matching.forEach { installed = installed.reduce(it) }
                            _state.value = installed
                            buffered.clear()
                            recovering = false
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) {
                            if (owner == epoch) appendError("Couldn't reconnect session (${sessionFailureStatus("reconnect", e)})")
                        }
                    }
                }
                previous = cur
            }
        }
    }

    fun stageAttachment(bytes: ByteArray, mimeType: String) {
        // Generate the id outside update{}: staging is called from background (IO) threads, and the
        // update lambda can re-run under CAS contention — a shared counter would race/collide. UUID
        // is collision-free across threads.
        val id = "att-${java.util.UUID.randomUUID()}"
        _state.update { it.withAttachment(PendingAttachment(id, bytes, mimeType)) }
    }
    fun removeAttachment(id: String) { _state.update { it.withoutAttachment(id) } }

    fun send(text: String) {
        if (!_state.value.timeline.ready) return
        val owner = epoch
        val handle = sessionId
        val atts = _state.value.pendingAttachments
        if (text.isBlank() && atts.isEmpty()) return
        val isSlash = text.trimStart().startsWith("/")
        val shown = text.ifBlank { "📎 ${atts.size} image${if (atts.size > 1) "s" else ""}" }
        _state.value = _state.value.withUserMessage(shown).copy(pendingAttachments = emptyList())
        viewModelScope.launch {
            try {
                atts.forEach { a ->
                    // java.util.Base64 (minSdk 26): consistent with the share-decode path and,
                    // unlike android.util.Base64, not stubbed to null under JVM unit tests.
                    val b64 = java.util.Base64.getEncoder().encodeToString(a.bytes)
                    runCatching { chat.attachImageBytes(handle, b64, a.mimeType) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            if (owner == epoch) appendError("Attach failed: ${it.message}")
                        }
                }
                // A leading "/" is a slash command — execute it (the gateway strips the slash)
                // rather than prompting the model.
                if (isSlash) chat.slashExec(handle, text.trim())
                else chat.submit(handle, text)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // A gateway error (e.g. "session not found") must surface, not crash the app.
                if (owner == epoch) appendError(e.message ?: "Failed to send message")
            }
        }
    }

    /** Re-ask: re-submit the last user prompt (appends a new answer; the gateway can't replace). */
    fun regenerate() {
        if (_state.value.isGenerating) return
        val prompt = lastUserPrompt(_state.value.timeline) ?: return
        send(prompt)
    }

    fun stop() { viewModelScope.launch { runCatching { chat.interrupt(sessionId) } } }

    private fun appendSystem(text: String) {
        val timeline = _state.value.timeline.withNotice("system", text)
        _state.value = _state.value.copy(
            timeline = timeline,
            messages = _state.value.messages + ChatMessage(
                id = "s-${_state.value.messages.size}", role = Role.SYSTEM, text = text,
            ),
        )
    }

    /** User tapped "Retry" on the offline banner — force an immediate reconnect. */
    fun reconnect() { runCatching { chat.reconnect() } }

    /** Fetch "@" completions for [word] (empty word clears them). */
    fun completePath(word: String) = viewModelScope.launch {
        if (!word.startsWith("@")) { _pathItems.value = emptyList(); return@launch }
        runCatching { chat.completePath(sessionId, word) }
            .onSuccess { _pathItems.value = it }
            .onFailure { _pathItems.value = emptyList() }
    }

    fun clearPathItems() { _pathItems.value = emptyList() }

    suspend fun respondLegacyApproval(request: ApprovalRequest, choice: ApprovalChoice): Boolean {
        if (_state.value.pendingApproval !== request || !_state.value.timeline.ready || request.requestId.isNullOrBlank() ||
            choice !in legacyApprovalChoices(request)) return false
        val owner = epoch
        val handle = sessionId
        try {
            chat.respondApproval(handle, choice, request.requestId)
            if (owner != epoch || _state.value.pendingApproval !== request) return false
            _state.value = _state.value.copy(pendingApproval = null,
                timeline = _state.value.timeline.resolveInteraction("approval", request.requestId))
            return true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return false }
    }

    fun respondApproval(choice: ApprovalChoice) {
        _state.value = _state.value.copy(pendingApproval = null, timeline = _state.value.timeline.resolveInteraction("approval", null))
        viewModelScope.launch {
            runCatching { chat.respondApproval(sessionId, choice) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    // The sheet is already gone; surface the failure so a lost approve/deny is visible.
                    appendError("Couldn't send your approval — check the connection and try again.")
                }
        }
    }

    fun clarify(answer: String) {
        val requestId = _state.value.pendingClarify?.requestId ?: ""
        _state.value = _state.value.copy(pendingClarify = null, timeline = _state.value.timeline.resolveInteraction("clarify", requestId))
        viewModelScope.launch { runCatching { chat.respondClarify(sessionId, requestId, answer) } }
    }

    /** Appends a non-fatal error as a system message and stops the generating spinner. */
    private fun appendError(text: String) {
        val timeline = _state.value.timeline.reduceTimeline(com.hermes.client.data.network.ServerEvent(
            "error", sessionId, kotlinx.serialization.json.buildJsonObject { put("message", text) }))
        _state.value = _state.value.copy(
            timeline = timeline,
            messages = _state.value.messages + ChatMessage(
                id = "e-${_state.value.messages.size}",
                role = Role.SYSTEM,
                text = text,
                isError = true,
            ),
            isGenerating = false,
        )
    }

    fun onSheetQuery(q: String) { _modelSheet.value = _modelSheet.value.copy(query = q) }
    fun onSheetScope(s: com.hermes.client.ui.models.ModelScope) {
        _modelSheet.value = _modelSheet.value.copy(scope = s, error = null)
    }
    fun toggleFavorite(provider: String, model: String) =
        viewModelScope.launch { favoritesStore.toggle(provider, model) }

    /**
     * Apply a model chosen in the sheet. SESSION → the /model --session slash (overrides just this
     * chat); DEFAULT → the global default via REST. On failure the gateway message is surfaced in
     * the sheet (kept open); on success the sheet is dismissed by the caller via [onDone].
     */
    fun onSelectFromSheet(provider: String, model: String, onDone: () -> Unit) {
        _modelSheet.value = _modelSheet.value.copy(pending = true, error = null)
        viewModelScope.launch {
            when (_modelSheet.value.scope) {
                com.hermes.client.ui.models.ModelScope.SESSION ->
                    runCatching { chat.slashExec(sessionId, "/model $model --provider $provider --session") }
                        .onSuccess {
                            _currentModel.value = model
                            _currentProvider.value = provider
                            _modelSheet.value = ModelSheetUi()  // reset + clear pending/error
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't switch model.",
                            )
                        }
                com.hermes.client.ui.models.ModelScope.DEFAULT ->
                    runCatching { modelRepo.set(provider, model) }
                        .onSuccess {
                            _modelSheet.value = _modelSheet.value.copy(pending = false, error = null)
                            appendSystem("Default set to $model")
                            onDone()
                        }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _modelSheet.value = _modelSheet.value.copy(
                                pending = false,
                                error = e.message ?: "Couldn't set default model.",
                            )
                        }
            }
        }
    }

    fun selectProfile(name: String) {
        viewModelScope.launch { runCatching { profileRepo.setActive(name) } }
    }

    data class PersonaUi(
        val personas: List<Persona> = emptyList(),
        val active: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )
    private val _personaUi = MutableStateFlow(PersonaUi())
    val personaUi: StateFlow<PersonaUi> = _personaUi.asStateFlow()

    /** Fetch the profile's configured personalities (called when the persona sheet opens). */
    fun loadPersonas() {
        _personaUi.value = _personaUi.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { configRepo.get(activeProfile.value) }
                .onSuccess { cfg -> _personaUi.value = PersonaUi(parsePersonas(cfg), activePersonaOf(cfg)) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't load personas")
                }
        }
    }

    /** Apply a persona to this session (null / "none" / "default" clears it). */
    fun setPersona(name: String?) {
        val wire = name?.takeIf { it.isNotBlank() && !it.equals("none", true) && !it.equals("default", true) } ?: "none"
        _personaUi.value = _personaUi.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { chat.slashExec(sessionId, "/personality $wire") }
                .onSuccess { out ->
                    if (out != null && out.contains("unknown", ignoreCase = true)) {
                        _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't apply that persona")
                    } else {
                        _personaUi.value = _personaUi.value.copy(loading = false, active = if (wire == "none") null else wire)
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _personaUi.value = _personaUi.value.copy(loading = false, error = "Couldn't apply persona")
                }
        }
    }
}
