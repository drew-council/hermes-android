package com.hermes.client.ui.chat

import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.*
import com.hermes.client.data.repository.*
import com.hermes.client.domain.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real HTTP/WS, repositories and VM. Ancillary device services alone are mocked.
 * Wire shapes are from methods_session._resume_live_unpersisted/_resume_response,
 * server._live_session_payload, session_history._history_to_messages and REST sessions.py.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatGatewayLifecycleTest {
    private class Backend : AutoCloseable {
        val server = MockWebServer()
        val calls = CopyOnWriteArrayList<JsonObject>()
        var autoReady = true
        var persisted = false
        var warm = false
        var failure: String? = null
        var socket: WebSocket? = null
        init {
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url.encodedPath == "/ws") return MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            socket = ws
                            if (autoReady) ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                        }
                        override fun onMessage(ws: WebSocket, text: String) {
                            val rpc = Json.parseToJsonElement(text).jsonObject
                            if (rpc["method"] == null) { calls += rpc; return }
                            calls += rpc
                            val method = rpc["method"]!!.jsonPrimitive.content
                            val params = rpc["params"]!!.jsonObject
                            fun reply(result: String) = ws.send("""{"jsonrpc":"2.0","id":${rpc["id"]},"result":$result}""")
                            when (method) {
                                "client.capabilities" -> reply("{}")
                                "session.create" -> reply("""{"session_id":"temporary","stored_session_id":"stored","message_count":0,"messages":[],"info":{"lazy":true}}""")
                                "session.resume" -> {
                                    // _find_live_unpersisted matches stored key, NOT runtime sid.
                                    if (params["session_id"] != JsonPrimitive("stored") || failure == "notfound") {
                                        ws.send("""{"jsonrpc":"2.0","id":${rpc["id"]},"error":{"code":4007,"message":"session not found"}}""")
                                    } else if (failure == "decode") reply("""{"session_id":"live","messages":{}}""")
                                    else if (!persisted) reply("""{"session_id":"live","stored_session_id":"stored","message_count":0,"messages":[],"info":{"lazy":true,"profile_name":"fixture"}}""")
                                    else reply("""{"session_id":"live","session_key":"stored","resumed":"stored","message_count":2,"messages":[{"role":"user","text":"synthetic"},{"role":"assistant","text":"reply","reasoning_details":[{"type":"text","text":"reason"}]}],"info":{"profile_name":"fixture"},"inflight":${if (warm) """{"user":"synthetic","assistant":"partial","streaming":true}""" else "null"},"running":$warm,"started_at":123.5,"turn_started_at":${if (warm) "124.0" else "null"},"status":"${if (warm) "working" else "idle"}","messages_omitted":false}""")
                                }
                                "prompt.submit" -> {
                                    check(params["session_id"] == JsonPrimitive("live"))
                                    persisted = true
                                    reply("{\"status\":\"started\"}")
                                    ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"live","payload":{}}}""")
                                    ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"live","payload":{"text":"reply"}}}""")
                                    ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"live","payload":{"text":"reply"}}}""")
                                }
                                else -> reply("{}")
                            }
                        }
                    }).build()
                    if (request.url.encodedPath == "/api/profiles") return MockResponse.Builder().body("""{"profiles":[{"name":"fixture"}]}""").build()
                    if (request.url.encodedPath == "/api/profiles/active") return MockResponse.Builder().body("""{"active":"fixture"}""").build()
                    if (failure == "auth") return MockResponse.Builder().code(401).body("{\"detail\":\"Unauthorized\"}").build()
                    if (!persisted) return MockResponse.Builder().code(404).body("{\"detail\":\"Session not found\"}").build()
                    return MockResponse.Builder().body("""{"session_id":"stored","profile":"fixture","messages":[{"id":1,"role":"user","content":"synthetic","timestamp":123.0},{"id":2,"role":"assistant","content":[{"type":"text","text":"reply"}],"reasoning_content":"reason","tool_calls":null,"display_metadata":null,"timestamp":124.0}],"pagination":{"limit":500,"offset":0,"order":"latest","returned":2}}""").build()
                }
            }
            server.start()
        }
        override fun close() { socket?.close(1000, "fixture done"); server.close() }
    }
    private suspend fun waitFor(check: () -> Boolean) { withTimeout(5000) { while (!check()) delay(10) } }
    private fun vm(chat: ChatRepository, sessions: SessionRepository, owner: ProfileManager? = null): ChatViewModel {
        val profiles = owner ?: mockk<ProfileManager>(relaxed = true).also {
            every { it.active } returns MutableStateFlow<String?>("fixture")
        }
        val favorites = mockk<ModelFavoritesStore>(relaxed = true)
        every { favorites.favorites } returns MutableStateFlow(emptySet())
        val tts = mockk<com.hermes.client.data.tts.TextToSpeechController>(relaxed = true)
        every { tts.speaking } returns MutableStateFlow(false)
        val prompts = mockk<PromptStore>(relaxed = true)
        every { prompts.prompts } returns MutableStateFlow(emptyList())
        return ChatViewModel(chat, sessions, mockk(relaxed = true), mockk(relaxed = true), profiles,
            favorites, com.hermes.client.share.PendingShareStore(), tts, prompts, mockk(relaxed = true))
    }
    @Test fun create_open_send_reopen_rich_history_send_warm_reconnect_and_profile() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Backend().use { backend ->
            val http = OkHttpClient()
            val json = Json { ignoreUnknownKeys = true }
            val gateway = HermesGatewayClient(http, json, scope, wsUrlProvider = { backend.server.url("/ws").toString() })
            val chat = ChatRepository(gateway)
            val sessions = SessionRepository(HermesRestApi(http, json) { GatewayConfig(backend.server.url("/").toString()) })
            val vm = vm(chat, sessions)
            gateway.connect()
            val key = chat.createSession("fixture")
            assertEquals("stored", key)
            vm.open(key)
            waitFor { vm.liveSessionId.value == "live" && vm.state.value.timeline.ready }
            assertTrue(vm.state.value.timeline.entries.isEmpty())
            gateway.reconnectNow()
            waitFor { backend.calls.count { it["method"] == JsonPrimitive("session.resume") } >= 2 && vm.state.value.timeline.ready }
            assertTrue(vm.state.value.timeline.entries.isEmpty())
            vm.send("synthetic")
            waitFor { backend.persisted && vm.state.value.timeline.entries.any { (it.body as? TimelineBody.Message)?.content?.text == "reply" } }
            vm.open(key)
            waitFor { vm.state.value.timeline.entries.any { it.source.rowId == 2L } }
            assertTrue(vm.state.value.timeline.entries.any { (it.body as? TimelineBody.PersistedRowDetails)?.sidecars?.containsKey("reasoning_content") == true })
            vm.send("synthetic again")
            waitFor { backend.calls.count { it["method"] == JsonPrimitive("prompt.submit") } == 2 }
            backend.warm = true
            gateway.reconnectNow()
            waitFor { vm.state.value.resumeSnapshot?.running == true }
            assertEquals("partial", vm.state.value.resumeSnapshot?.inflight?.assistant)
            assertTrue(backend.calls.filter { it["method"] == JsonPrimitive("session.resume") }.all { it["params"]!!.jsonObject["profile"] == JsonPrimitive("fixture") })
            gateway.cancelNow()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
        scope.cancel(); Dispatchers.resetMain()
    }
    @Test fun notfound_decode_and_auth_are_distinguished_without_marking_ready() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Backend().use { backend ->
            val http = OkHttpClient(); val json = Json { ignoreUnknownKeys = true }
            val gateway = HermesGatewayClient(http, json, scope, wsUrlProvider = { backend.server.url("/ws").toString() })
            val chat = ChatRepository(gateway)
            val vm = vm(chat, SessionRepository(HermesRestApi(http, json) { GatewayConfig(backend.server.url("/").toString()) }))
            gateway.connect()
            vm.open("temporary")
            waitFor { vm.state.value.timeline.entries.any { (it.body as? TimelineBody.Notice)?.text?.contains("RPC 4007") == true } }
            assertTrue(vm.state.value.timeline.entries.any { (it.body as? TimelineBody.Notice)?.text?.contains("History unavailable (history: HTTP 404)") == true })
            assertFalse(vm.state.value.timeline.ready)
            for ((mode, expected) in listOf("notfound" to "RPC 4007", "decode" to "invalid-response")) {
                backend.failure = mode
                vm.open("stored")
                waitFor { vm.state.value.timeline.entries.any { (it.body as? TimelineBody.Notice)?.text?.contains(expected) == true } }
                assertFalse(vm.state.value.timeline.ready)
            }
            backend.failure = "auth"; vm.open("stored")
            waitFor { vm.unauthorized.value }
            assertFalse(vm.state.value.timeline.ready)
            gateway.cancelNow()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
        scope.cancel(); Dispatchers.resetMain()
    }

    @Test fun legacy_cold_entry_awaits_profile_and_late_readiness_retries_failed_initial_resume() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Backend().use { backend ->
            backend.persisted = true; backend.autoReady = false
            val http = OkHttpClient(); val json = Json { ignoreUnknownKeys = true }
            val rest = HermesRestApi(http, json) { GatewayConfig(backend.server.url("/").toString()) }
            val gateway = HermesGatewayClient(http, json, scope, wsUrlProvider = { backend.server.url("/ws").toString() })
            val manager = ProfileManager(ProfileRepository(rest))
            val vm = vm(ChatRepository(gateway), SessionRepository(rest), manager)
            assertNull(manager.active.value)
            assertEquals(ConnectionState.Disconnected, gateway.connectionState.value)
            vm.open("stored") // no external connect; no preloaded profile
            withTimeout(18000) {
                while (vm.state.value.timeline.entries.none { (it.body as? TimelineBody.Notice)?.text?.contains("resume: RPC 0") == true }) delay(10)
            }
            assertFalse(vm.state.value.timeline.ready)
            backend.socket!!.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
            waitFor { vm.state.value.timeline.ready && vm.liveSessionId.value == "live" }
            assertEquals("fixture", vm.activeProfile.value)
            assertTrue(backend.calls.filter { it["method"] == JsonPrimitive("session.resume") }.all { it["params"]!!.jsonObject["profile"] == JsonPrimitive("fixture") })
            gateway.cancelNow(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
        scope.cancel(); Dispatchers.resetMain()
    }
}
