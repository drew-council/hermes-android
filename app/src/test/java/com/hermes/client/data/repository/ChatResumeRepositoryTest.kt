package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatResumeRepositoryTest {
    @Test fun typed_resume_preserves_warm_state_and_profile_without_reusing_rest_schema() = runTest {
        val client = mockk<HermesGatewayClient>()
        coEvery { client.call("session.resume", any()) } returns Json.parseToJsonElement("""{
            "session_id":"live","session_key":"stored","running":true,"status":"waiting",
            "messages":[{"role":"tool","name":"test","context":"preview","args":{"q":"synthetic"}}],
            "inflight":{"user":"fixture prompt","assistant":"partial","streaming":true},
            "queued":{"text":"queued fixture"},"pending_approval":{"command":"test"},
            "open_requests":[{"id":7,"method":"clarify","params":{"question":"synthetic?"}}]
        }""")
        val snapshot = ChatRepository(client).resumeSnapshot("stored", "p")
        assertEquals("live", snapshot.sessionId)
        assertEquals("stored", snapshot.sessionKey)
        assertTrue(snapshot.running)
        assertEquals("waiting", snapshot.status)
        assertEquals("partial", snapshot.inflight?.assistant)
        assertEquals("preview", snapshot.messages.single().context)
        assertNotNull(snapshot.openRequests)
        assertNotNull(snapshot.queued)
        coVerify { client.call("session.resume", match { it["profile"] == JsonPrimitive("p") && it["session_id"] == JsonPrimitive("stored") }) }
    }
}
