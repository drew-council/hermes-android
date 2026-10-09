package com.hermes.client.domain

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineBlockCompletionTest {
    private fun event(type: String, text: String? = null) = ServerEvent(type, "s", buildJsonObject { text?.let { put("text", it) } })
    @Test fun available_reasoning_blocks_remain_on_both_sides_of_tool() {
        val state = TimelineSnapshot().reduceTimeline(event("message.start"))
            .reduceTimeline(event("reasoning.available", "A"))
            .reduceTimeline(event("tool.start"))
            .reduceTimeline(event("reasoning.available", "B"))
        assertEquals(listOf("Reasoning", "ToolInvocation", "Reasoning"), state.entries.map { it.body.javaClass.simpleName })
        assertEquals(listOf("A", "B"), state.entries.mapNotNull { (it.body as? TimelineBody.Reasoning)?.text })
    }
    @Test fun available_completes_only_adjacent_streaming_block_then_seals_it() {
        val streamed = TimelineSnapshot().reduceTimeline(event("message.start"))
            .reduceTimeline(event("reasoning.delta", "par"))
        val completed = streamed.reduceTimeline(event("reasoning.available", "part"))
        assertEquals(streamed.entries.single().key, completed.entries.single().key)
        assertEquals("part", (completed.entries.single().body as TimelineBody.Reasoning).text)
        assertFalse((completed.entries.single().body as TimelineBody.Reasoning).streaming)
        val next = completed.reduceTimeline(event("reasoning.delta", "next"))
        assertEquals(listOf("part", "next"), next.entries.map { (it.body as TimelineBody.Reasoning).text })
    }
    @Test fun streamed_blocks_and_terminal_aggregate_preserve_observed_positions() {
        val observed = TimelineSnapshot().reduceTimeline(event("message.start"))
            .reduceTimeline(event("reasoning.delta", "A"))
            .reduceTimeline(event("reasoning.available", "A"))
            .reduceTimeline(event("tool.start"))
            .reduceTimeline(event("reasoning.delta", "B"))
            .reduceTimeline(event("reasoning.available", "B"))
        val terminal = observed.reduceTimeline(ServerEvent("message.complete", "s", buildJsonObject {
            put("text", "final"); put("reasoning", "AB")
        }))
        assertEquals(listOf("A", "B"), terminal.entries.mapNotNull { (it.body as? TimelineBody.Reasoning)?.text })
        assertEquals(observed.entries.map { it.key }, terminal.entries.take(observed.entries.size).map { it.key })
        assertTrue(terminal.entries[1].body is TimelineBody.ToolInvocation)
    }
    @Test fun identical_available_blocks_are_not_deduplicated_by_text() {
        val state = TimelineSnapshot().reduceTimeline(event("message.start"))
            .reduceTimeline(event("reasoning.available", "same"))
            .reduceTimeline(event("tool.start"))
            .reduceTimeline(event("reasoning.available", "same"))
        assertEquals(listOf("same", "same"), state.entries.mapNotNull { (it.body as? TimelineBody.Reasoning)?.text })
    }
    @Test fun text_only_completion_without_start_preserves_unconfirmed_final_and_reasoning() {
        val state = TimelineSnapshot().reduceTimeline(ServerEvent("message.complete", "s", buildJsonObject {
            put("text", "final"); put("reasoning", "reason")
        }))
        assertEquals(2, state.entries.size)
        assertEquals("final", (state.entries[0].body as TimelineBody.Message).content.text)
        assertEquals("reason", (state.entries[1].body as TimelineBody.Reasoning).text)
        assertTrue(state.entries.all { it.orderEvidence == OrderEvidence.RESUMED_UNKNOWN && it.source.turnId == null })
        assertTrue(state.completeness.overlapUnconfirmed)
    }
}
