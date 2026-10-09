package com.hermes.client.ui.chat

import com.hermes.client.domain.*
import com.hermes.client.data.network.PendingServerRequest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelinePresentationTest {
    private fun entry(key: String, body: TimelineBody = TimelineBody.Message(Role.ASSISTANT, DisplayContent(listOf(ContentPart.Text(key))))) =
        TimelineEntry(key, SourceRef(null, "synthetic-session"), body, OrderEvidence.LIVE_ARRIVAL)
    private fun request(id: String, replayed: Boolean = false, method: String = "approval") =
        PendingServerRequest(id, method, "synthetic-session", buildJsonObject { put("request_id", "queue-$id") }, 1, replayed)

    @Test fun chronological_parts_are_not_bucketed_or_parsed_from_prose() {
        val bodies = listOf(
            TimelineBody.Message(Role.ASSISTANT, DisplayContent(listOf(ContentPart.Text("A")))),
            TimelineBody.Reasoning("R"), TimelineBody.ToolInvocation("T", "fixture", null),
            TimelineBody.Message(Role.ASSISTANT, DisplayContent(listOf(ContentPart.Text("B")))),
            TimelineBody.Interaction("approval", "p", "Approval requested"),
            TimelineBody.Message(Role.ASSISTANT, DisplayContent(listOf(ContentPart.Text("{tool: prose}")))),
        )
        val timeline = TimelineSnapshot(entries = bodies.mapIndexed { i, body -> entry("key-$i", body) })
        val rendered = chatRenderItems(timeline, emptyList(), emptyMap()).map { (it as ChatRenderItem.Entry).entry }
        assertEquals(bodies, rendered.map { it.body })
        assertEquals(timeline.entries.map { it.key }, rendered.map { it.key })
        assertEquals(listOf(5), timelineMatchIndices(timeline, "tool: prose"))
    }

    @Test fun live_anchor_does_not_drift_and_replay_does_not_invent_order() {
        val live = request("live")
        val replay = request("replay", true)
        val timeline = TimelineSnapshot(entries = listOf(entry("A"), entry("B")))
        val anchors = mapOf(live to "A", replay to "A")
        val rendered = chatRenderItems(timeline, listOf(live, replay), anchors)
        assertEquals(listOf("A", "live", "B", "replay"), rendered.map {
            when (it) { is ChatRenderItem.Entry -> it.entry.key; is ChatRenderItem.Request -> it.request.id }
        })
        assertTrue((rendered[1] as ChatRenderItem.Request).anchored)
        assertFalse((rendered.last() as ChatRenderItem.Request).anchored)
        val grown = chatRenderItems(timeline.copy(entries = timeline.entries + entry("C")), listOf(live, replay), anchors)
        assertSame(live, (grown[1] as ChatRenderItem.Request).request)
        assertSame(replay, (grown.last() as ChatRenderItem.Request).request)
        assertFalse(chatRenderItems(timeline.copy(entries = listOf(entry("replaced"))), listOf(live), anchors)
            .filterIsInstance<ChatRenderItem.Request>().single().anchored)
        assertTrue((chatRenderItems(timeline, listOf(live), mapOf(live to "^start")).first() as ChatRenderItem.Request).anchored)
    }

    @Test fun modern_dedup_uses_wire_or_queue_identity_never_text() {
        val req = request("wire")
        assertTrue(hasModernRequest(TimelineBody.Interaction("approval", "wire", "unrelated text"), listOf(req)))
        assertTrue(hasModernRequest(TimelineBody.Interaction("approval", "queue-wire", "unrelated text"), listOf(req)))
        assertFalse(hasModernRequest(TimelineBody.Interaction("approval", "other", "same command"), listOf(req)))
        assertFalse(hasModernRequest(TimelineBody.Interaction("clarify", "wire", "same command"), listOf(req)))
        assertFalse(hasModernRequest(TimelineBody.Interaction("approval", null, "same command"), listOf(req)))
    }

    @Test fun only_current_pending_legacy_marker_gets_actions() {
        val request = ApprovalRequest("fixture", "", emptyList(), false, requestId = "fixture-queue")
        val body = TimelineBody.Interaction("approval", "fixture-queue", "Approval requested")
        val timeline = TimelineSnapshot(entries = listOf(entry("older", body), entry("new", body)))
        assertFalse(legacyApprovalAt(timeline, 0, request))
        assertTrue(legacyApprovalAt(timeline, 1, request))
        assertFalse(legacyApprovalAt(timeline, 1, request.copy(requestId = null)))
        assertFalse(legacyApprovalAt(timeline.copy(entries = listOf(entry("new", body.copy(state = InteractionState.ANSWERED)))), 0, request))
    }

    @Test fun scopes_are_allowlisted_and_restricted() {
        val params = buildJsonObject {
            put("choices", JsonArray(listOf("once", "session", "always", "deny", "unknown").map(::JsonPrimitive)))
            put("allow_session", false); put("allow_permanent", false)
        }
        assertEquals(listOf("once", "deny"), approvalChoices(params))
        assertEquals(listOf("once", "deny"), approvalChoices(JsonObject(params + ("smart_denied" to JsonPrimitive(true)))))
    }

    @Test fun legacy_scopes_fail_closed_without_explicit_permission() {
        val request = ApprovalRequest("", "", emptyList(), false)
        assertEquals(setOf(ApprovalChoice.ONCE, ApprovalChoice.DENY), legacyApprovalChoices(request).toSet())
        assertFalse(legacyApprovalChoices(request.copy(choices = listOf("session", "always", "deny"))).contains(ApprovalChoice.SESSION))
        assertEquals(setOf(ApprovalChoice.ONCE, ApprovalChoice.DENY),
            legacyApprovalChoices(request.copy(allowSession = true, allowPermanent = true, smartDenied = true)).toSet())
    }

    @Test fun reader_intent_survives_growth_search_and_resumes_explicitly() {
        val pinned = ChatScrollIntent()
        assertTrue(pinned.following) // geometry/growth has no transition
        val reading = pinned.readerScroll(false)
        assertFalse(reading.following)
        assertFalse(reading.search().following)
        assertTrue(reading.latest().following)
        assertTrue(reading.readerScroll(true).following)
    }
}
