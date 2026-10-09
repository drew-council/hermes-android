package com.hermes.client.data.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ServerRequestCoordinatorTest {
    private val json = Json
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun frame(id: String = "srq-1", method: String = "vault.code", session: String = "synthetic-s1", extra: String = "") =
        RpcServerRequest(id, method, obj("""{"session_id":"$session"$extra}"""))
    private fun value(text: String) = buildJsonObject { put("value", text) }

    @Test fun response_is_exact_ephemeral_and_single_settlement() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        val frames = mutableListOf<String>()
        val send: (String) -> Boolean = { frames.add(it); true }
        c.receive(frame(), false, true, send)
        val request = c.pending.value.single()
        assertFalse(request.toString().contains("synthetic"))
        assertEquals(RespondOutcome.ENQUEUED, c.respond(request, value("SYNTHETIC_ONLY_OTP"), true, send))
        assertEquals(obj("""{"jsonrpc":"2.0","id":"srq-1","result":{"value":"SYNTHETIC_ONLY_OTP"}}"""), obj(frames.single()))
        assertTrue(c.pending.value.isEmpty())
        assertEquals(RespondOutcome.STALE, c.respond(request, value("SYNTHETIC_ONLY_OTP"), true, send))
        assertEquals(1, frames.size)
        c.replay(obj("""{"open_requests":[{"id":"srq-1","method":"vault.code","params":{"session_id":"synthetic-s1"}}]}"""), true, send)
        assertTrue(c.pending.value.isEmpty())
    }

    @Test fun matching_cancel_only_and_late_snapshot_cannot_resurrect() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        val send: (String) -> Boolean = { true }
        c.receive(frame("srq-1", "approval", extra = """, "request_id":"queue-1", "choices":["once","deny"]"""), false, true, send)
        c.receive(frame("srq-2", "approval", extra = """, "request_id":"queue-2", "choices":["once","deny"]"""), false, true, send)
        c.receive(frame("srq-3", session = "synthetic-s2"), false, true, send)
        val stale = c.pending.value.first()
        c.cancel(obj("""{"id":"srq-1","session_id":"wrong-session"}"""))
        assertEquals(3, c.pending.value.size)
        c.cancel(obj("""{"id":"srq-1","session_id":"synthetic-s1","reason":"timeout"}"""))
        assertEquals(listOf("srq-2", "srq-3"), c.pending.value.map { it.id })
        c.replay(obj("""{"open_requests":[{"id":"srq-1","method":"vault.code","params":{"session_id":"synthetic-s1"}}]}"""), true, send)
        assertEquals(RespondOutcome.STALE, c.respond(stale, value(""), true, send))
    }

    @Test fun replay_restores_locks_deduplicates_and_reset_invalidates_handles() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        val send: (String) -> Boolean = { true }
        val snapshot = obj("""{"open_requests":[{"id":"srq-batch","method":"clarify","params":{"session_id":"synthetic-s1","questions":[{"qid":"q1","question":"First?","choices":null,"multi_select":false}],"answers":{"q1":"A"}}}]}""")
        c.replay(snapshot, true, send)
        val request = c.pending.value.single()
        assertTrue(request.replayed)
        assertEquals("A", request.params["answers"]!!.jsonObject["q1"]!!.jsonPrimitive.content)
        c.replay(snapshot, true, send)
        assertSame(request, c.pending.value.single())
        assertEquals(RespondOutcome.ENQUEUED, c.respond(request, obj("""{"answers":{}}"""), true, send))
        c.reset(2); c.replay(snapshot, true, send)
        assertEquals(RespondOutcome.STALE, c.respond(request, obj("""{"answers":{}}"""), true, send))
    }

    @Test fun late_snapshot_merges_nonsecret_locks_into_live_request_without_reordering() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        val params = obj("""{"session_id":"synthetic-s1","questions":[{"qid":"q1","question":"First?","multi_select":false}]}""")
        c.receive(RpcServerRequest("srq-batch", "clarify", params), false, true) { true }
        val old = c.pending.value.single()
        c.receive(frame("srq-second"), false, true) { true }
        c.replay(buildJsonObject { put("open_requests", JsonArray(listOf(buildJsonObject {
            put("id", "srq-batch"); put("method", "clarify"); put("params", JsonObject(params + ("answers" to obj("""{"q1":"A"}"""))))
        }))) }, true) { true }
        assertEquals(listOf("srq-batch", "srq-second"), c.pending.value.map { it.id })
        assertEquals(obj("""{"q1":"A"}"""), c.pending.value.first().params["answers"])
        assertEquals(RespondOutcome.STALE, c.respond(old, obj("""{"answers":{}}"""), true) { true })
    }

    @Test fun send_failure_retains_no_answer_and_no_offline_retry() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        c.receive(frame(), false, true) { true }
        val request = c.pending.value.single()
        assertEquals(RespondOutcome.DISCONNECTED, c.respond(request, value("SYNTHETIC_ONLY_PASSWORD"), true) { false })
        assertTrue(c.pending.value.isEmpty())
        assertEquals(RespondOutcome.STALE, c.respond(request, value("SYNTHETIC_ONLY_PASSWORD"), true) { fail("retry"); false })
        c.reset(2); c.receive(frame(), true, true) { true }
        assertFalse(c.pending.value.toString().contains("SYNTHETIC_ONLY_PASSWORD"))
        assertFalse(c.pending.value.single().params.toString().contains("SYNTHETIC_ONLY_PASSWORD"))
    }

    @Test fun unknown_unavailable_and_malformed_are_static_errors() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        val frames = mutableListOf<String>()
        val send: (String) -> Boolean = { frames.add(it); true }
        c.receive(frame("srq-1", "SYNTHETIC_UNTRUSTED_METHOD"), false, true, send)
        c.receive(frame("srq-2"), false, false, send)
        c.receive(RpcServerRequest("srq-3", "secret", obj("""{"session_id":"synthetic-s1","prompt":"SYNTHETIC_ONLY_PASSWORD"}""")), false, true, send)
        assertEquals(listOf(-32601, -32601, -32602), frames.map { obj(it)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int })
        assertTrue(c.pending.value.isEmpty())
        assertFalse(frames.joinToString().contains("SYNTHETIC"))
    }

    @Test fun supports_all_value_methods_single_clarify_and_restricts_approval() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        val methods = mapOf(
            "vault.code" to "", "sudo" to "",
            "secret" to """, "env_var":"SYNTHETIC_TOKEN", "prompt":"Fixture"""",
            "vault.unlock_prompt" to """, "backend":"synthetic", "display_name":"Fixture"""",
            "vault.save_login" to """, "origin":"https://example.invalid", "site":"example.invalid"""",
        )
        methods.entries.forEachIndexed { index, (method, extra) ->
            c.receive(frame("srq-$index", method, extra = extra), false, true) { true }
            assertEquals(RespondOutcome.ENQUEUED, c.respond(c.pending.value.single(), value(""), true) { true })
        }
        c.receive(frame("srq-a", "approval", extra = """, "request_id":"queue", "choices":["once","deny"], "allow_permanent":false, "allow_session":false"""), false, true) { true }
        val approval = c.pending.value.single()
        try { c.respond(approval, obj("""{"choice":"always"}"""), true) { fail("must not send"); true }; fail("invalid approval") } catch (e: IllegalArgumentException) { assertEquals("Invalid server request result", e.message) }
        assertEquals(RespondOutcome.ENQUEUED, c.respond(approval, obj("""{"choice":"deny","all":false}"""), true) { true })
        c.receive(frame("srq-c", "clarify", extra = """, "question":null, "choices":null, "multi_select":false"""), false, true) { true }
        assertEquals(RespondOutcome.ENQUEUED, c.respond(c.pending.value.single(), obj("""{"answer":""}"""), true) { true })
    }

    @Test fun onepassword_unlock_fails_closed_without_account_password_fallback() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        val frames = mutableListOf<String>()
        listOf("1password", "onepassword", "One-Password", "1Password-service-account", "op").forEachIndexed { index, backend ->
            c.receive(frame("srq-blocked-$index", "vault.unlock_prompt", extra = """, "backend":"$backend", "display_name":"Fixture manager""""), false, true) { frames.add(it); true }
        }
        c.receive(frame("srq-blocked-label", "vault.unlock_prompt", extra = """, "backend":"op", "display_name":"1Password""""), true, true) { frames.add(it); true }
        assertTrue(c.pending.value.isEmpty())
        assertEquals(6, frames.size)
        assertTrue(frames.all { obj(it)["error"]!!.jsonObject == obj("""{"code":-32601,"message":"Unsupported server request"}""") })
        c.receive(frame("srq-bitwarden", "vault.unlock_prompt", extra = """, "backend":"bitwarden", "display_name":"Bitwarden fixture""""), false, true) { true }
        assertEquals("srq-bitwarden", c.pending.value.single().id)
        c.receive(frame("srq-otp", "vault.code", extra = """, "site":"example.invalid""""), false, true) { true }
        assertEquals(listOf("srq-bitwarden", "srq-otp"), c.pending.value.map { it.id })
    }

    @Test fun bounded_pending_requests_fail_statically() {
        val c = ServerRequestCoordinator(json); c.reset(1)
        repeat(128) { c.receive(frame("srq-$it"), false, true) { true } }
        var error = ""
        c.receive(frame("srq-overflow"), false, true) { error = it; true }
        assertEquals(128, c.pending.value.size)
        assertEquals(-32603, obj(error)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }
}
