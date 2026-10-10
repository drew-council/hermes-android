package com.hermes.client.ui.chat

import com.hermes.client.data.network.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SessionFailureStatusTest {
    private fun status(code: Int, reason: String): String {
        val parsed = parseInbound(Json, """{"jsonrpc":"2.0","id":3,"error":{"code":$code,"message":"SYNTHETIC_PRIVATE_DETAIL","data":{"reason":"$reason","extra":"SYNTHETIC_PRIVATE_DETAIL"}}}""") as RpcErrorReply
        return sessionFailureStatus("send", GatewayRpcException(parsed.error.code, parsed.error.message, parsed.error.reason))
    }
    @Test fun production_4090_machine_reason_distinguishes_ownership_without_raw_detail() {
        assertEquals("send: RPC 4090 · session owned by another live client", status(4090, "SESSION_NOT_OWNED"))
        assertEquals("send: RPC 4090 · concurrency limit reached", status(4090, "MAX_CONCURRENT_SESSIONS"))
        assertEquals("send: RPC 4090 · session coordination unavailable", status(4090, "SESSION_COORDINATION_UNAVAILABLE"))
    }
    @Test fun unknown_reason_and_unrelated_codes_never_echo_server_data() {
        assertEquals("send: RPC 4090", status(4090, "SYNTHETIC_PRIVATE_DETAIL"))
        assertEquals("send: RPC 4007", status(4007, "SESSION_NOT_OWNED"))
    }
}
