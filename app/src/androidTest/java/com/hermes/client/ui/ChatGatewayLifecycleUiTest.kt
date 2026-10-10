package com.hermes.client.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.navigation.compose.composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.network.*
import com.hermes.client.data.repository.*
import com.hermes.client.data.tts.TextToSpeechController
import com.hermes.client.domain.*
import com.hermes.client.ui.chat.*
import com.hermes.client.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Full ChatScreen -> VM -> real repositories -> loopback HTTP/WS regression; synthetic data only. */
class ChatGatewayLifecycleUiTest {
    @get:Rule val rule = createComposeRule()
    private class Backend : AutoCloseable {
        val server = MockWebServer()
        val calls = CopyOnWriteArrayList<JsonObject>()
        var defaultProfile = "fixture"
        val historyProfiles = CopyOnWriteArrayList<String>()
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
                            ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
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
                                    if (params["session_id"] != JsonPrimitive("stored") || params["profile"] != JsonPrimitive("fixture") || failure == "notfound") {
                                        ws.send("""{"jsonrpc":"2.0","id":${rpc["id"]},"error":{"code":4007,"message":"session not found"}}""")
                                    } else if (failure == "decode") reply("""{"session_id":"live","messages":{}}""")
                                    else if (!persisted) reply("""{"session_id":"live","stored_session_id":"stored","message_count":0,"messages":[],"info":{"lazy":true,"profile_name":"fixture"}}""")
                                    else reply("""{"session_id":"live","session_key":"stored","resumed":"stored","message_count":2,"messages":[{"role":"user","text":"synthetic"},{"role":"assistant","text":"reply","reasoning_details":[{"type":"text","text":"reason"}]}],"info":{"profile_name":"fixture"},"inflight":${if (warm) """{"user":"synthetic","assistant":"partial","streaming":true}""" else "null"},"running":$warm,"started_at":123.5,"turn_started_at":${if (warm) "124.0" else "null"},"status":"${if (warm) "working" else "idle"}","messages_omitted":false}""")
                                }
                                "prompt.submit" -> {
                                    check(params["session_id"] == JsonPrimitive("live"))
                                    if (failure == "ownership") {
                                        ws.send("""{"jsonrpc":"2.0","id":${rpc["id"]},"error":{"code":4090,"message":"synthetic private detail","data":{"reason":"SESSION_NOT_OWNED"}}}""")
                                        return
                                    }
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
                    if (request.url.encodedPath == "/api/profiles") return MockResponse.Builder().body("""{"profiles":[{"name":"fixture","is_default":true}]}""").build()
                    if (request.url.encodedPath == "/api/profiles/active") return MockResponse.Builder().body("""{"active":"$defaultProfile"}""").build()
                    if (request.url.encodedPath == "/api/model/options") return MockResponse.Builder().body("""{"providers":[]}""").build()
                    if (request.url.encodedPath.endsWith("/messages")) {
                        val profile = request.url.queryParameter("profile")
                        historyProfiles += profile ?: "absent"
                        if (profile != "fixture") return MockResponse.Builder().code(404).body("{}").build()
                    }
                    if (failure == "auth") return MockResponse.Builder().code(401).body("{\"detail\":\"Unauthorized\"}").build()
                    if (!persisted) return MockResponse.Builder().code(404).body("{\"detail\":\"Session not found\"}").build()
                    return MockResponse.Builder().body("""{"session_id":"stored","profile":"fixture","messages":[{"id":1,"role":"user","content":"synthetic","timestamp":123.0},{"id":2,"role":"assistant","content":[{"type":"text","text":"reply"}],"reasoning_content":"reason","tool_calls":null,"display_metadata":null,"timestamp":124.0}],"pagination":{"limit":500,"offset":0,"order":"latest","returned":2}}""").build()
                }
            }
            server.start()
        }
        override fun close() { socket?.close(1000, "fixture done"); server.close() }
    }

    @Test fun new_chat_existing_history_reconnect_and_private_code_on_current_transport() = runBlocking {
        com.hermes.client.data.diagnostics.DebugLog.clear()
        com.hermes.client.data.diagnostics.DebugLog.setEnabled(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Backend().use { backend ->
            val http = OkHttpClient()
            val json = Json { ignoreUnknownKeys = true }
            val rest = HermesRestApi(http, json) { GatewayConfig(backend.server.url("/").toString()) }
            val gateway = HermesGatewayClient(http, json, scope, wsUrlProvider = { backend.server.url("/ws").toString() })
            val chat = ChatRepository(gateway)
            val profiles = ProfileRepository(rest)
            val manager = ProfileManager(profiles)
            manager.refresh()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tts = object : TextToSpeechController {
                override val speaking = MutableStateFlow(false)
                override fun speak(text: String) = Unit
                override fun stop() = Unit
            }
            val vm = ChatViewModel(chat, SessionRepository(rest), ModelRepository(rest), profiles, manager,
                ModelFavoritesStore(context), com.hermes.client.share.PendingShareStore(), tts,
                PromptStore(context), ConfigRepository(rest))
            gateway.connect()
            val key = chat.createSession("fixture")
            val showing = mutableStateOf(true)
            rule.setContent { HermesTheme { if (showing.value) ChatScreen(key, ownerProfile = "fixture", vm = vm) } }
            rule.waitUntil(5000) { vm.state.value.timeline.ready && vm.liveSessionId.value == "live" }
            rule.onNodeWithText("History unavailable", substring = true).assertDoesNotExist()
            rule.onNodeWithText("Couldn't resume session", substring = true).assertDoesNotExist()
            rule.onNodeWithText("Message Hermes…").performTextInput("synthetic")
            rule.onNodeWithContentDescription("Send").performClick()
            rule.waitUntil(5000) { backend.persisted && !vm.state.value.isGenerating && vm.state.value.timeline.entries.any { (it.body as? TimelineBody.Message)?.content?.text == "reply" } }
            rule.runOnIdle { showing.value = false }
            rule.waitForIdle()
            rule.runOnIdle { showing.value = true }
            rule.waitUntil(5000) { vm.state.value.timeline.entries.any { it.source.rowId == 2L } }
            rule.onNodeWithText("Message Hermes…").performTextInput("synthetic again")
            rule.onNodeWithContentDescription("Send").performClick()
            rule.waitUntil(5000) { backend.calls.count { it["method"] == JsonPrimitive("prompt.submit") } == 2 }
            backend.warm = true
            gateway.reconnectNow()
            rule.waitUntil(5000) { vm.state.value.resumeSnapshot?.running == true }
            assertEquals("partial", vm.state.value.resumeSnapshot?.inflight?.assistant)
            backend.socket!!.send("""{"jsonrpc":"2.0","id":"fixture-code","method":"vault.code","params":{"session_id":"live","site":"synthetic.invalid","hint":"Synthetic verification fixture"}}""")
            rule.waitUntil(5000) { vm.pendingServerRequests.value.size == 1 }
            rule.onNodeWithText("Verification code").performScrollTo().assertIsDisplayed()
            // Secure field semantics carry Password; composer is a separate ordinary text field.
            rule.onNode(isEditable() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)).performScrollTo().performTextInput("123456")
            rule.onNode(hasText("123456") and !SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)).assertDoesNotExist()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNode(isEditable() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertTrue(layouts.all { it.layoutInput.text.text == "••••••" })
            rule.onNodeWithText("Send privately").performScrollTo().performClick()
            rule.waitUntil(5000) { backend.calls.any { it["id"] == JsonPrimitive("fixture-code") && it["result"] != null } }
            val reply = backend.calls.single { it["id"] == JsonPrimitive("fixture-code") }
            assertEquals(buildJsonObject { put("value", "123456") }, reply["result"])
            assertTrue(vm.pendingServerRequests.value.isEmpty())
            rule.onNodeWithText("Response sent (not acknowledged)").assertIsDisplayed()
            assertFalse(vm.state.value.messages.any { it.text.contains("123456") || it.text.contains("Synthetic verification fixture") })
            assertFalse(vm.state.value.timeline.entries.any { (it.body as? TimelineBody.Message)?.content?.text?.contains("123456") == true })
            assertTrue(backend.calls.filter { it["method"] == JsonPrimitive("session.resume") }.all { it["params"]!!.jsonObject["profile"] == JsonPrimitive("fixture") })
            assertFalse(com.hermes.client.data.diagnostics.DebugLog.entries.value.any { it.message.contains("123456") || it.message.contains("Synthetic verification fixture") })
            backend.socket!!.send("""{"jsonrpc":"2.0","id":"other-session-code","method":"vault.code","params":{"session_id":"other-live","site":"other.invalid"}}""")
            rule.waitUntil(5000) { vm.pendingServerRequests.value.size == 1 }
            val other = vm.pendingServerRequests.value.single()
            rule.onNodeWithText("other.invalid", substring = true).assertDoesNotExist()
            assertEquals(RespondOutcome.STALE, vm.respondServerRequest(other, buildJsonObject { put("value", "123456") }))
            rule.onNodeWithText("Request expired or replaced · answer discarded").assertIsDisplayed()
            assertFalse(backend.calls.any { it["id"] == JsonPrimitive("other-session-code") })
            // Disposal of an unanswered request must not fabricate an empty answer.
            backend.socket!!.send("""{"jsonrpc":"2.0","id":"dispose-code","method":"vault.code","params":{"session_id":"live","site":"synthetic.invalid"}}""")
            rule.waitUntil(5000) { vm.pendingServerRequests.value.size == 2 }
            rule.runOnIdle { showing.value = false }
            rule.waitForIdle()
            assertFalse(backend.calls.any { it["id"] == JsonPrimitive("dispose-code") })
            gateway.cancelNow()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
        scope.cancel()
        com.hermes.client.data.diagnostics.DebugLog.setEnabled(false)
        com.hermes.client.data.diagnostics.DebugLog.clear()
    }

    @Test fun restored_existing_chat_connects_without_service_and_retains_nondefault_owner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Backend().use { backend ->
            backend.persisted = true
            backend.defaultProfile = "other"
            val http = OkHttpClient()
            val json = Json { ignoreUnknownKeys = true }
            val rest = HermesRestApi(http, json) { GatewayConfig(backend.server.url("/").toString()) }
            val gateway = HermesGatewayClient(http, json, scope, wsUrlProvider = { backend.server.url("/ws").toString() })
            val chat = ChatRepository(gateway)
            val profiles = ProfileRepository(rest)
            val manager = ProfileManager(profiles)
            manager.refresh()
            assertEquals("other", manager.active.value)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tts = object : TextToSpeechController {
                override val speaking = MutableStateFlow(false)
                override fun speak(text: String) = Unit
                override fun stop() = Unit
            }
            val vm = ChatViewModel(chat, SessionRepository(rest), ModelRepository(rest), profiles, manager,
                ModelFavoritesStore(context), com.hermes.client.share.PendingShareStore(), tts,
                PromptStore(context), ConfigRepository(rest))
            assertEquals(ConnectionState.Disconnected, gateway.connectionState.value)
            // Navigation starts directly on the saved owner-bearing route. No fixture connect,
            // SessionsScreen preparation, notification service, or global profile switch.
            val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
            restoration.setContent {
                val nav = androidx.navigation.compose.rememberNavController()
                androidx.navigation.compose.NavHost(nav, startDestination = com.hermes.client.ui.nav.chatRoute("stored", "fixture")) {
                    composable(com.hermes.client.ui.nav.CHAT_ROUTE_PATTERN, arguments = listOf(androidx.navigation.navArgument("profile") {
                        type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null
                    })) { entry ->
                        HermesTheme { ChatScreen(entry.arguments!!.getString("id")!!,
                            ownerProfile = entry.arguments!!.getString("profile"), vm = vm) }
                    }
                }
            }
            rule.waitUntil(5000) { vm.state.value.timeline.ready && vm.state.value.timeline.entries.any { it.source.rowId == 2L } }
            restoration.emulateSavedInstanceStateRestore()
            rule.waitUntil(5000) { vm.state.value.timeline.ready && vm.liveSessionId.value == "live" }
            rule.onNodeWithText("Message Hermes…").performTextInput("synthetic after restore")
            rule.onNodeWithContentDescription("Send").performClick()
            rule.waitUntil(5000) { backend.calls.any { it["method"] == JsonPrimitive("prompt.submit") } }
            assertEquals("other", manager.active.value)
            assertTrue(backend.historyProfiles.isNotEmpty())
            assertTrue(backend.historyProfiles.all { it == "fixture" })
            assertTrue(backend.calls.filter { it["method"] == JsonPrimitive("session.resume") }.all { it["params"]!!.jsonObject["profile"] == JsonPrimitive("fixture") })
            gateway.cancelNow()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
        scope.cancel()
    }

    @Test fun blank_and_separator_code_send_are_blocked_skip_is_explicit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Backend().use { backend ->
            val http = OkHttpClient()
            val json = Json { ignoreUnknownKeys = true }
            val rest = HermesRestApi(http, json) { GatewayConfig(backend.server.url("/").toString()) }
            val gateway = HermesGatewayClient(http, json, scope, wsUrlProvider = { backend.server.url("/ws").toString() })
            val chat = ChatRepository(gateway)
            val profiles = ProfileRepository(rest)
            val manager = ProfileManager(profiles)
            manager.refresh()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tts = object : TextToSpeechController {
                override val speaking = MutableStateFlow(false)
                override fun speak(text: String) = Unit
                override fun stop() = Unit
            }
            val vm = ChatViewModel(chat, SessionRepository(rest), ModelRepository(rest), profiles, manager,
                ModelFavoritesStore(context), com.hermes.client.share.PendingShareStore(), tts,
                PromptStore(context), ConfigRepository(rest))
            gateway.connect()
            val key = chat.createSession("fixture")
            val showing = mutableStateOf(true)
            rule.setContent { HermesTheme { if (showing.value) ChatScreen(key, ownerProfile = "fixture", vm = vm) } }
            rule.waitUntil(5000) { vm.state.value.timeline.ready && vm.liveSessionId.value == "live" }

            backend.socket!!.send("""{"jsonrpc":"2.0","id":"blank-code","method":"vault.code","params":{"session_id":"live","site":"synthetic.invalid"}}""")
            rule.waitUntil(5000) { vm.pendingServerRequests.value.size == 1 }
            val field = rule.onNodeWithContentDescription("Enter verification code")
            field.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            rule.onNodeWithText("Send privately").performScrollTo().assertIsNotEnabled()
            java.io.File(context.cacheDir, "verification-input-synthetic.png").outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            assertFalse(backend.calls.any { it["id"] == JsonPrimitive("blank-code") })
            assertEquals(1, vm.pendingServerRequests.value.size)
            field.performScrollTo().performTextInput(" - \u2003-- ")
            rule.onNodeWithText("Send privately").assertIsNotEnabled()
            assertFalse(backend.calls.any { it["id"] == JsonPrimitive("blank-code") })
            field.performTextReplacement("123 456")
            val click = rule.onNodeWithText("Send privately").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            field.performTextClearance()
            // Invoke the captured callback despite disabled UI: it must recheck live input state.
            rule.runOnIdle { click() }
            assertFalse(backend.calls.any { it["id"] == JsonPrimitive("blank-code") })
            assertEquals(1, vm.pendingServerRequests.value.size)
            rule.onNodeWithText("Enter the requested input before sending").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Skip").performScrollTo().performClick()
            rule.waitUntil(5000) { backend.calls.any { it["id"] == JsonPrimitive("blank-code") } }
            assertEquals(buildJsonObject { put("value", "") }, backend.calls.single { it["id"] == JsonPrimitive("blank-code") }["result"])
            assertTrue(vm.pendingServerRequests.value.isEmpty())
            rule.onNodeWithText("Request skipped (not acknowledged)").assertIsDisplayed()
            assertFalse(vm.state.value.messages.any { it.text.contains("Request skipped") })
            backend.failure = "ownership"
            rule.onNodeWithText("Message Hermes…").performTextInput("synthetic refused turn")
            rule.onNodeWithContentDescription("Send").performClick()
            rule.waitUntil(5000) { vm.state.value.timeline.entries.any { (it.body as? TimelineBody.Notice)?.text?.contains("send: RPC 4090 · session owned by another live client") == true } }
            assertFalse(vm.state.value.messages.any { it.text.contains("synthetic private detail") })
            backend.socket!!.send("""{"jsonrpc":"2.0","id":"disconnect-code","method":"vault.code","params":{"session_id":"live"}}""")
            rule.waitUntil(5000) { vm.pendingServerRequests.value.size == 1 }
            val disconnected = vm.pendingServerRequests.value.single()
            gateway.cancelNow()
            assertEquals(RespondOutcome.DISCONNECTED, vm.respondServerRequest(disconnected, buildJsonObject { put("value", "123456") }))
            rule.onNodeWithText("Disconnected · answer discarded").assertIsDisplayed()
            assertFalse(backend.calls.any { it["id"] == JsonPrimitive("disconnect-code") })
            rule.runOnIdle { showing.value = false }
            gateway.cancelNow(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
        scope.cancel()
    }
}
