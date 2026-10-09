package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.ui.chat.ApprovalChoice
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LegacyApprovalRepositoryTest {
    @Test fun simultaneous_approval_answers_address_exact_queue_ids() = runTest {
        val client = mockk<HermesGatewayClient>()
        val calls = mutableListOf<JsonObject>()
        coEvery { client.call("approval.respond", capture(calls)) } returns buildJsonObject { put("resolved", true) }
        val repository = ChatRepository(client)
        repository.respondApproval("live-fixture", ApprovalChoice.DENY, "queue-first")
        repository.respondApproval("live-fixture", ApprovalChoice.ONCE, "queue-second")
        assertEquals(listOf("queue-first", "queue-second"), calls.map { (it["request_id"] as JsonPrimitive).content })
        assertTrue(calls.all { it["session_id"] == JsonPrimitive("live-fixture") })
        assertEquals(JsonPrimitive("deny"), calls.first()["choice"])
        assertEquals(JsonPrimitive("once"), calls.last()["choice"])
        assertTrue(calls.none { it["all"] == JsonPrimitive(true) })
    }
}
