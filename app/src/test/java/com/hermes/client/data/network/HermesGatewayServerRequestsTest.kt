package com.hermes.client.data.network

import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.repository.ChatRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class HermesGatewayServerRequestsTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val json = Json
    private val ready = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    private val request = """{"jsonrpc":"2.0","id":"srq-fixture","method":"vault.code","params":{"session_id":"synthetic-s1","site":"example.invalid"}}"""

    private class Peer(val opened: CompletableDeferred<WebSocket> = CompletableDeferred(), val frames: Channel<JsonObject> = Channel(Channel.UNLIMITED))
    private fun peer(): Peer {
        val peer = Peer()
        serverRule.server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peer.opened.complete(webSocket); webSocket.send(ready)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                peer.frames.trySend(json.parseToJsonElement(text).jsonObject)
            }
        }).build())
        return peer
    }

    private fun fixture(test: suspend CoroutineScope.(HermesGatewayClient, ChatRepository) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val client = HermesGatewayClient(http, json, scope) { serverRule.server.url("/synthetic-ws").toString().replace("http", "ws") }
        try { withTimeout(10_000) { supervisorScope { test(client, ChatRepository(client)) } } }
        finally {
            client.cancelNow(); scope.cancel()
            http.dispatcher.executorService.shutdown()
            http.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
            http.connectionPool.evictAll()
            DebugLog.setEnabled(false); DebugLog.clear()
        }
    }

    @Test fun default_false_enable_after_ready_precedes_session_calls_and_withdrawal_is_explicit() = fixture { client, repo ->
        val p = peer(); client.connect()
        val ws = p.opened.await()
        val first = p.frames.receive()
        assertEquals("client.capabilities", first["method"]!!.jsonPrimitive.content)
        assertEquals(false, first["params"]!!.jsonObject["server_requests"]!!.jsonPrimitive.boolean)
        ws.send(request)
        assertEquals(-32601, p.frames.receive()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        repo.setServerRequestHandlerAvailable(true)
        repo.setServerRequestHandlerAvailable(true) // no duplicate advertisement
        val resume = async { repo.resume("synthetic-stored") }
        val capability = p.frames.receive()
        assertEquals(true, capability["params"]!!.jsonObject["server_requests"]!!.jsonPrimitive.boolean)
        val call = p.frames.receive()
        assertEquals("session.resume", call["method"]!!.jsonPrimitive.content)
        // Older servers may reject capability; no response-to-response and no gate failure.
        ws.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", capability["id"]!!); put("error", buildJsonObject { put("code", -32601); put("message", "Synthetic unsupported") }) }.toString())
        ws.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", call["id"]!!); put("result", buildJsonObject { put("session_id", "synthetic-s1") }) }.toString())
        assertEquals("synthetic-s1", resume.await())
        repo.setServerRequestHandlerAvailable(false)
        assertEquals(false, p.frames.receive()["params"]!!.jsonObject["server_requests"]!!.jsonPrimitive.boolean)
        assertTrue(p.frames.tryReceive().isFailure)
    }

    @Test fun snapshot_before_resume_consumer_exact_secret_response_reconnect_and_redacted_errors() = fixture { client, repo ->
        DebugLog.clear(); DebugLog.setEnabled(true)
        val p = peer(); repo.setServerRequestHandlerAvailable(true); repo.connect()
        val ws = p.opened.await()
        val capability = p.frames.receive()
        assertEquals(true, capability["params"]!!.jsonObject["server_requests"]!!.jsonPrimitive.boolean)
        ws.send(ready) // duplicate ready must not duplicate capability
        val resume = async { repo.resume("synthetic-stored") }
        val call = p.frames.receive()
        assertEquals("session.resume", call["method"]!!.jsonPrimitive.content)
        val openRequest = json.parseToJsonElement(request).jsonObject.filterKeys { it != "jsonrpc" }
        ws.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", call["id"]!!)
            put("result", buildJsonObject { put("session_id", "synthetic-s1"); put("open_requests", JsonArray(listOf(JsonObject(openRequest)))) })
        }.toString())
        assertEquals("synthetic-s1", resume.await())
        val handle = repo.pendingServerRequests.value.single() // already restored before deferred completes
        assertTrue(handle.replayed)
        ws.send(request) // live duplicate
        ws.send("SYNTHETIC_ONLY_MALFORMED_CANARY")
        ws.send("""{"jsonrpc":"2.0","id":"SYNTHETIC_ONLY_STRING_ID","result":{}}""")
        val answer = buildJsonObject { put("value", "SYNTHETIC_ONLY_OTP") }
        assertEquals(RespondOutcome.ENQUEUED, repo.respondServerRequest(handle, answer))
        assertEquals(RespondOutcome.STALE, repo.respondServerRequest(handle, answer))
        val response = p.frames.receive()
        assertEquals(setOf("jsonrpc", "id", "result"), response.keys)
        assertEquals("srq-fixture", response["id"]!!.jsonPrimitive.content)
        assertEquals(answer, response["result"])
        assertTrue(repo.pendingServerRequests.value.isEmpty())
        assertFalse(handle.toString().contains("SYNTHETIC"))
        val ping = async { client.call("ping", buildJsonObject {}) }
        val pingCall = p.frames.receive()
        assertFalse(pingCall.toString().contains("SYNTHETIC_ONLY_OTP"))
        ws.send(buildJsonObject { put("id", pingCall["id"]!!); put("error", buildJsonObject { put("code", 4000); put("message", "SYNTHETIC_ONLY_ERROR_CANARY") }) }.toString())
        // async failure is caught inside a supervisor scope to avoid cancelling the fixture.
        try { ping.await(); fail("expected error") } catch (e: GatewayRpcException) { assertEquals("Gateway RPC failed", e.message) }
        assertFalse(DebugLog.export().contains("SYNTHETIC_ONLY"))
        val second = peer(); repo.reconnect()
        val secondWs = second.opened.await()
        assertEquals(true, second.frames.receive()["params"]!!.jsonObject["server_requests"]!!.jsonPrimitive.boolean)
        assertEquals(RespondOutcome.STALE, repo.respondServerRequest(handle, answer))
        secondWs.send(request)
        val restored = repo.pendingServerRequests.first { it.isNotEmpty() }.single()
        assertNotEquals(handle.generation, restored.generation)
        assertFalse(restored.params.toString().contains("SYNTHETIC_ONLY_OTP"))
        // Cancellation removes the exact request internally even without event collectors.
        secondWs.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","payload":{"id":"srq-fixture","session_id":"synthetic-s1","reason":"timeout"}}}""")
        repo.pendingServerRequests.first { it.isEmpty() }
        assertEquals(RespondOutcome.STALE, repo.respondServerRequest(restored, answer))
    }
    @Test fun clarification_locks_are_request_scoped_with_expiry_final_resolution_and_stale_replies() = fixture { client, repo ->
        val p = peer(); repo.setServerRequestHandlerAvailable(true); repo.connect()
        val ws = p.opened.await(); p.frames.receive()
        val batch = """{"jsonrpc":"2.0","id":"srq-batch","method":"clarify","params":{"session_id":"synthetic-s1","questions":[{"qid":"q1","question":"First?","multi_select":false},{"qid":"q2","question":"Second?","multi_select":false}],"answers":{"q1":"A"}}}"""
        ws.send(batch)
        val handle = repo.pendingServerRequests.first { it.isNotEmpty() }.single()
        try { repo.lockServerClarification(handle, "not-a-qid", "Fixture"); fail("invalid qid") }
        catch (e: IllegalArgumentException) { assertEquals("Invalid clarification question", e.message) }
        val first = async { repo.lockServerClarification(handle, "q1", "B") }
        val call = p.frames.receive()
        assertEquals("clarify.lock", call["method"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("request_id", "srq-batch"); put("question_id", "q1"); put("answer", "B") }, call["params"])
        ws.send(buildJsonObject { put("id", call["id"]!!); put("result", buildJsonObject { put("status", "ok"); put("remaining", JsonArray(listOf(JsonPrimitive("q2")))) }) }.toString())
        assertEquals(ClarificationLockOutcome.Ok(listOf("q2")), first.await())
        assertSame(handle, repo.pendingServerRequests.value.single())
        val final = async { repo.lockServerClarification(handle, "q2", "Fixture") }
        val finalCall = p.frames.receive()
        ws.send(buildJsonObject { put("id", finalCall["id"]!!); put("result", buildJsonObject { put("status", "ok"); put("remaining", JsonArray(emptyList())) }) }.toString())
        assertEquals(ClarificationLockOutcome.Ok(emptyList()), final.await())
        assertTrue(repo.pendingServerRequests.value.isEmpty())
        assertEquals(ClarificationLockOutcome.Stale, repo.lockServerClarification(handle, "q1", "Fixture"))
        ws.send(batch.replace("srq-batch", "srq-expired"))
        val expiredHandle = repo.pendingServerRequests.first { it.isNotEmpty() }.single()
        val expired = async { repo.lockServerClarification(expiredHandle, "q1", "Fixture") }
        val expiredCall = p.frames.receive()
        ws.send(buildJsonObject { put("id", expiredCall["id"]!!); put("result", buildJsonObject { put("status", "expired") }) }.toString())
        assertEquals(ClarificationLockOutcome.Expired, expired.await())
        assertTrue(repo.pendingServerRequests.value.isEmpty())
        ws.send(batch.replace("srq-batch", "srq-cancel"))
        val canceledHandle = repo.pendingServerRequests.first { it.isNotEmpty() }.single()
        val late = async { repo.lockServerClarification(canceledHandle, "q1", "Fixture") }
        val lateCall = p.frames.receive()
        ws.send("""{"method":"event","params":{"type":"request.cancel","payload":{"id":"srq-cancel","session_id":"synthetic-s1","reason":"interrupted"}}}""")
        repo.pendingServerRequests.first { it.isEmpty() }
        ws.send(buildJsonObject { put("id", lateCall["id"]!!); put("result", buildJsonObject { put("status", "ok"); put("remaining", JsonArray(listOf(JsonPrimitive("q2")))) }) }.toString())
        assertEquals(ClarificationLockOutcome.Stale, late.await())
        assertTrue(repo.pendingServerRequests.value.isEmpty())
        ws.send(batch.replace("srq-batch", "srq-disconnect"))
        val disconnectHandle = repo.pendingServerRequests.first { it.isNotEmpty() }.single()
        val disconnected = async { repo.lockServerClarification(disconnectHandle, "q1", "Fixture") }
        p.frames.receive()
        ws.close(1000, "synthetic close")
        assertEquals(ClarificationLockOutcome.Disconnected, disconnected.await())
        assertTrue(repo.pendingServerRequests.value.isEmpty())
    }

    @Test fun concurrent_response_and_cancellation_settle_only_once() = fixture { client, repo ->
        val p = peer(); repo.setServerRequestHandlerAvailable(true); repo.connect()
        val ws = p.opened.await(); p.frames.receive()
        ws.send(request)
        val handle = repo.pendingServerRequests.first { it.isNotEmpty() }.single()
        val start = CompletableDeferred<Unit>()
        val results = List(8) { async(Dispatchers.Default) {
            start.await()
            repo.respondServerRequest(handle, buildJsonObject { put("value", "SYNTHETIC_ONLY_CONCURRENT_OTP") })
        } }
        val cancellation = async(Dispatchers.Default) {
            start.await()
            ws.send("""{"method":"event","params":{"type":"request.cancel","payload":{"id":"srq-fixture","session_id":"synthetic-s1","reason":"timeout"}}}""")
        }
        start.complete(Unit)
        val outcomes = results.awaitAll(); cancellation.await()
        assertTrue(outcomes.count { it == RespondOutcome.ENQUEUED } <= 1)
        assertTrue(outcomes.all { it == RespondOutcome.ENQUEUED || it == RespondOutcome.STALE })
        repo.pendingServerRequests.first { it.isEmpty() }
        // A round trip fences both directions without recording generic websocket payloads.
        val ping = async { client.call("ping", buildJsonObject {}) }
        var responses = 0
        while (true) {
            val frame = p.frames.receive()
            if (frame["method"] == JsonPrimitive("ping")) {
                ws.send(buildJsonObject { put("id", frame["id"]!!); put("result", buildJsonObject {}) }.toString())
                break
            }
            assertEquals("srq-fixture", frame["id"]!!.jsonPrimitive.content)
            responses++
        }
        ping.await()
        assertEquals(outcomes.count { it == RespondOutcome.ENQUEUED }, responses)
    }

    @Test fun lock_errors_cancellation_and_generation_replacement_are_honest_and_do_not_retry() = fixture { client, repo ->
        val p = peer(); repo.setServerRequestHandlerAvailable(true); repo.connect()
        val ws = p.opened.await(); p.frames.receive()
        ws.send("""{"id":"srq-batch","method":"clarify","params":{"session_id":"synthetic-s1","questions":[{"qid":"q1","question":"First?","multi_select":false}]}}""")
        val handle = repo.pendingServerRequests.first { it.isNotEmpty() }.single()
        val error = async { repo.lockServerClarification(handle, "q1", "Fixture") }
        val errorCall = p.frames.receive()
        ws.send(buildJsonObject { put("id", errorCall["id"]!!); put("error", buildJsonObject { put("code", 4002); put("message", "SYNTHETIC_ERROR_DETAIL") }) }.toString())
        try { error.await(); fail("expected RPC failure") } catch (e: GatewayRpcException) {
            assertEquals(4002, e.code); assertEquals("Gateway RPC failed", e.message)
        }
        val malformed = async { repo.lockServerClarification(handle, "q1", "Fixture") }
        val malformedCall = p.frames.receive()
        ws.send(buildJsonObject { put("id", malformedCall["id"]!!); put("result", buildJsonObject { put("status", "ok") }) }.toString())
        try { malformed.await(); fail("expected malformed response failure") } catch (e: GatewayRpcException) {
            assertEquals("Invalid clarification lock response", e.message)
        }
        val canceled = async { repo.lockServerClarification(handle, "q1", "Fixture") }
        val canceledCall = p.frames.receive()
        canceled.cancelAndJoin()
        assertTrue(canceled.isCancelled)
        ws.send(buildJsonObject { put("id", canceledCall["id"]!!); put("result", buildJsonObject { put("status", "ok"); put("remaining", JsonArray(emptyList())) }) }.toString())
        val inFlight = async { repo.lockServerClarification(handle, "q1", "Fixture") }
        p.frames.receive()
        val second = peer(); repo.reconnect()
        assertEquals(ClarificationLockOutcome.Stale, inFlight.await())
        second.opened.await()
        assertEquals("client.capabilities", second.frames.receive()["method"]!!.jsonPrimitive.content)
        assertTrue(repo.pendingServerRequests.value.isEmpty())
        assertTrue(second.frames.tryReceive().isFailure) // no lock/answer retransmission
    }

}
