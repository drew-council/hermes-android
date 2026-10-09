package com.hermes.client.domain

import com.hermes.client.data.network.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineReconciliationTest {
    private fun event(type: String, build: JsonObjectBuilder.() -> Unit = {}) = ServerEvent(type, "live", buildJsonObject(build))
    private fun local(complete: Boolean): TimelineSnapshot = TimelineSnapshot(completeness = HistoryCompleteness(resolvedSessionId = "s"))
        .withAuthoredPrompt("same").reduceTimeline(event("message.start"))
        .reduceTimeline(event("message.delta") { put("text", "reply") })
        .reduceTimeline(event("message.complete") {
            put("text", "reply")
            putJsonObject("persisted_turn") {
                put("complete", complete); put("user_row_id", 1); put("final_assistant_row_id", 2)
                putJsonArray("row_ids") { add(1); add(2) }
            }
        })
    private fun fetched() = projectRestHistory(MessagesDto(sessionId = "s", profile = "p", messages = listOf(
        MessageDto(1, "user", JsonPrimitive("same")), MessageDto(2, "assistant", JsonPrimitive("reply")),
    )), "s", "p")
    @Test fun complete_proven_receipt_retires_only_its_local_turn_without_text_dedup() {
        val local = local(true).withAuthoredPrompt("same")
        val reconciled = local.reconcileHistory(fetched())
        assertEquals(3, reconciled.entries.size)
        assertEquals(2, reconciled.entries.count { (it.body as? TimelineBody.Message)?.content?.text == "same" })
        assertNull(reconciled.persistedTurn)
    }
    @Test fun partial_or_missing_rows_keep_unconfirmed_overlay() {
        val partial = local(false).reconcileHistory(fetched())
        assertEquals(4, partial.entries.size)
        assertTrue(partial.completeness.overlapUnconfirmed)
        val missing = local(true).reconcileHistory(fetched().copy(entries = fetched().entries.take(1)))
        assertEquals(3, missing.entries.size)
        assertNotNull(missing.persistedTurn)
    }
    @Test fun warm_accumulated_text_never_concatenates_onto_live_overlay() {
        val local = TimelineSnapshot().reduceTimeline(event("message.start"))
            .reduceTimeline(event("message.delta") { put("text", "abc") })
        val resumed = local.projectResume(ResumeDto(sessionId = "live", running = true,
            inflight = InflightDto(assistant = "abc", streaming = true)), "p", "s", hasLiveOverlay = true)
        assertEquals(listOf("abc", "abc"), resumed.entries.mapNotNull { (it.body as? TimelineBody.Message)?.content?.text })
        assertTrue(resumed.entries.any { (it.body as? TimelineBody.Notice)?.kind == "resume-overlap-unconfirmed" })
        assertEquals(OrderEvidence.RESUMED_UNKNOWN, resumed.entries.last().orderEvidence)
        assertFalse((resumed.entries.last().body as TimelineBody.Message).streaming)
        val continued = resumed.reduceTimeline(event("message.delta") { put("text", "d") })
        assertEquals("d", (continued.entries.last().body as TimelineBody.Message).content.text)
        assertEquals(listOf("abc", "abc", "d"), continued.entries.mapNotNull { (it.body as? TimelineBody.Message)?.content?.text })
    }
    @Test fun resumed_running_turn_accepts_tool_start_without_fabricated_message_start() {
        val resumed = TimelineSnapshot().projectResume(ResumeDto(sessionId = "live", running = true), "p", "s")
        val updated = resumed.reduceTimeline(event("tool.start") { put("tool_id", "c") })
        assertTrue(updated.entries.single().body is TimelineBody.ToolInvocation)
        assertNotNull(updated.entries.single().source.turnId)
        val unproven = TimelineSnapshot().reduceTimeline(event("tool.start") { put("tool_id", "c") })
        assertTrue(unproven.entries.single().body is TimelineBody.ToolInvocation)
        assertNull(unproven.entries.single().source.turnId)
    }
    @Test fun lineage_redirect_does_not_merge_another_session() {
        val local = local(true)
        val redirected = local.reconcileHistory(fetched().copy(completeness = HistoryCompleteness(resolvedSessionId = "another")))
        assertEquals(0, redirected.entries.count { it.source.rowId != null })
        assertEquals("lineage-changed", (redirected.entries.last().body as TimelineBody.Notice).kind)
    }

    @Test fun stopped_warm_resume_seals_local_stream_without_inventing_successful_tool_outcome() {
        var local = TimelineSnapshot().reduceTimeline(event("message.start"))
            .reduceTimeline(event("tool.start") { put("tool_id", "c") })
            .reduceTimeline(event("message.delta") { put("text", "partial") })
        local = local.projectResume(ResumeDto(sessionId = "live", running = false), "p", "s", hasLiveOverlay = true)
        assertNull(local.activeTurn)
        assertFalse(local.generating)
        assertEquals(ToolState.UNKNOWN, (local.entries.first().body as TimelineBody.ToolInvocation).state)
        assertFalse((local.entries.last().body as TimelineBody.Message).streaming)
    }

    @Test fun inconsistent_rest_and_resume_identities_disable_send_instead_of_merging() {
        val base = fetched()
        val mismatch = base.projectResume(ResumeDto(sessionId = "live", sessionKey = "different", running = true,
            inflight = InflightDto(assistant = "must not merge")), "p", "s")
        assertFalse(mismatch.ready)
        assertEquals(3, mismatch.entries.size)
        assertEquals("lineage-changed", (mismatch.entries.last().body as TimelineBody.Notice).kind)
        val wrongProfile = base.projectResume(ResumeDto(sessionId = "live", sessionKey = "s"), "other", "s")
        assertFalse(wrongProfile.ready)
    }
}
