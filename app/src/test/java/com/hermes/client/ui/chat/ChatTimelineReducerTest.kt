package com.hermes.client.ui.chat

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatTimelineReducerTest {
    private fun ev(type: String, seq: Long? = null, build: JsonObjectBuilder.() -> Unit = {}) = ServerEvent(type, "s", buildJsonObject(build), seq)
    @Test fun live_arrival_order_interim_tools_interaction_and_final_are_preserved() {
        var state = ChatUiState.empty().withUserMessage("prompt")
        state = state.reduce(ev("message.start"))
        state = state.reduce(ev("reasoning.delta") { put("text", "think") })
        state = state.reduce(ev("message.delta") { put("text", "comment") })
        state = state.reduce(ev("message.interim") { put("text", "comment"); put("already_streamed", true) })
        state = state.reduce(ev("tool.start") { put("tool_id", "c"); put("name", "test"); putJsonObject("args") { put("x", 1) } })
        state = state.reduce(ev("clarify.request") { put("request_id", "r"); put("question", "which?") })
        state = state.reduce(ev("tool.complete") { put("tool_id", "c"); putJsonObject("result") { put("fixture", 1) } })
        state = state.reduce(ev("message.delta") { put("text", "fi") })
        state = state.reduce(ev("message.delta") { put("text", "nal") })
        state = state.reduce(ev("message.complete") { put("text", "final"); put("reasoning", "think") })
        val bodies = state.timeline.entries.map { it.body }
        assertEquals(listOf("Message", "Reasoning", "Message", "ToolInvocation", "Interaction", "ToolResult", "Message"), bodies.map { it.javaClass.simpleName })
        assertEquals("comment", (bodies[2] as TimelineBody.Message).content.text)
        assertEquals("final", (bodies.last() as TimelineBody.Message).content.text)
        assertEquals(ToolState.COMPLETED, (bodies[3] as TimelineBody.ToolInvocation).state)
        assertEquals("{\"fixture\":1}", (bodies[5] as TimelineBody.ToolResult).result.original.toString())
        val resolved = state.timeline.resolveInteraction("clarify", "r")
        assertEquals(InteractionState.ANSWERED, (resolved.entries[4].body as TimelineBody.Interaction).state)
        assertEquals(state.timeline.entries.map { it.key }, resolved.entries.map { it.key })
    }
    @Test fun unstreamed_interim_is_sealed_and_final_does_not_replace_it() {
        var s = TimelineSnapshot().reduceTimeline(ev("message.start"))
        s = s.reduceTimeline(ev("message.interim") { put("text", "comment"); put("already_streamed", false) })
        s = s.reduceTimeline(ev("message.complete") { put("text", "answer") })
        assertEquals(listOf("comment", "answer"), s.entries.map { (it.body as TimelineBody.Message).content.text })
    }
    @Test fun streamed_growth_and_tool_updates_change_revision_not_keys_or_count() {
        var s = TimelineSnapshot().reduceTimeline(ev("message.start"))
        s = s.reduceTimeline(ev("message.delta", 1) { put("text", "a") })
        val first = s
        s = s.reduceTimeline(ev("message.delta", 2) { put("text", "b") })
        assertEquals(first.entries.map { it.key }, s.entries.map { it.key })
        assertEquals("ab", (s.entries.last().body as TimelineBody.Message).content.text)
        assertTrue(s.revision > first.revision)
        assertEquals(s, s.reduceTimeline(ev("message.delta", 2) { put("text", "b") }))
    }
    @Test fun late_completion_resolves_original_unique_call_not_newest_turn_and_reuse_is_unknown() {
        var s = TimelineSnapshot().reduceTimeline(ev("message.start"))
        s = s.reduceTimeline(ev("tool.start") { put("tool_id", "c") })
        s = s.reduceTimeline(ev("message.complete"))
        s = s.reduceTimeline(ev("message.start"))
        s = s.reduceTimeline(ev("tool.complete") { put("tool_id", "c"); put("result", "done") })
        assertEquals(ToolState.COMPLETED, (s.entries.first().body as TimelineBody.ToolInvocation).state)
        assertEquals(s.entries.first().source.turnId, s.entries.last().source.turnId)
        s = s.reduceTimeline(ev("tool.start") { put("tool_id", "c") })
        s = s.reduceTimeline(ev("tool.complete") { put("tool_id", "c"); put("result", "ambiguous") })
        assertEquals(ToolState.RUNNING, s.entries.mapNotNull { it.body as? TimelineBody.ToolInvocation }.last().state)
        assertNull(s.entries.last().source.turnId)
    }
    @Test fun error_terminal_marks_active_running_tools_interrupted() {
        var s = TimelineSnapshot().reduceTimeline(ev("message.start"))
        s = s.reduceTimeline(ev("tool.start") { put("tool_id", "c") })
        s = s.reduceTimeline(ev("message.complete") { put("status", "interrupted"); put("partial", true) })
        assertEquals(ToolState.INTERRUPTED, (s.entries.first().body as TimelineBody.ToolInvocation).state)
        assertFalse(s.generating)
    }
    @Test fun full_reasoning_snapshot_replaces_not_appends_the_delta_body() {
        var s = TimelineSnapshot().reduceTimeline(ev("message.start"))
        s = s.reduceTimeline(ev("reasoning.delta") { put("text", "part") })
        s = s.reduceTimeline(ev("reasoning.available") { put("text", "part whole") })
        assertEquals(1, s.entries.size)
        assertEquals("part whole", (s.entries.single().body as TimelineBody.Reasoning).text)
    }
}
