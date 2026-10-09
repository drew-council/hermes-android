package com.hermes.client.domain

import com.hermes.client.data.network.MessagesDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HistoryTimelineMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun page(rows: String) = json.decodeFromString(MessagesDto.serializer(), """{"session_id":"stored","profile":"p","messages":$rows,"pagination":{"limit":500,"returned":7}}""")
    private val rows = """[
      {"id":10,"role":"user","content":"same","timestamp":20},
      {"id":11,"role":"assistant","content":null,"tool_calls":[{"id":"c1","function":{"name":"one","arguments":"{\"x\":1}"}},{"id":"c2","function":{"name":"two","arguments":{}}}]},
      {"id":12,"role":"tool","tool_call_id":"c1","tool_name":"one","content":{"answer":1}},
      {"id":13,"role":"assistant","content":"commentary","timestamp":2},
      {"id":14,"role":"tool","tool_call_id":"c2","content":"result"},
      {"id":15,"role":"assistant","content":"final"},
      {"id":16,"role":"user","content":"same"}
    ]"""
    @Test fun rest_preserves_row_order_tool_types_arguments_and_repeated_prompts() {
        val snapshot = projectRestHistory(page(rows), "requested", "p")
        assertEquals(listOf(10L, 11L, 11L, 12L, 13L, 14L, 15L, 16L), snapshot.entries.map { it.source.rowId })
        val calls = snapshot.entries.mapNotNull { it.body as? TimelineBody.ToolInvocation }
        assertEquals(listOf("c1", "c2"), calls.map { it.callId })
        assertEquals("{\"x\":1}", calls.first().args.toString())
        assertTrue(calls.all { it.state == ToolState.COMPLETED })
        val result = snapshot.entries[3].body as TimelineBody.ToolResult
        assertEquals("c1", result.callId)
        assertEquals("[Unsupported content]", result.result.text)
        assertEquals("{\"answer\":1}", result.result.original.toString())
        assertEquals(2, snapshot.entries.count { (it.body as? TimelineBody.Message)?.content?.text == "same" })
        assertFalse(snapshot.entries.any { (it.body as? TimelineBody.Message)?.content?.text == "" })
        assertEquals("same", lastUserPrompt(snapshot))
    }
    @Test fun orphan_results_and_unfinished_invocations_are_not_invented_or_assumed_running() {
        val snapshot = projectRestHistory(page("""[{"id":1,"role":"tool","tool_call_id":"outside","content":"ok"},{"id":2,"role":"assistant","tool_calls":[{"id":"unproven","function":{"name":"test"}}]}]"""), "stored", "p")
        assertTrue(snapshot.entries.first().body is TimelineBody.ToolResult)
        assertEquals(ToolState.UNKNOWN, (snapshot.entries.last().body as TimelineBody.ToolInvocation).state)
    }
    @Test fun sidecars_are_bundled_without_fabricating_internal_timing() {
        val snapshot = projectRestHistory(page("""[{"id":1,"role":"assistant","content":"reply","reasoning_content":"reason","codex_message_items":"[{\"type\":\"unknown\"}]","tool_calls":[{"id":"c","function":{"name":"t"}}]}]"""), "stored", "p")
        assertTrue(snapshot.entries.first().body is TimelineBody.PersistedRowDetails)
        assertEquals(OrderEvidence.WITHIN_ROW_UNKNOWN, snapshot.entries.first().orderEvidence)
        val body = snapshot.entries.first().body as TimelineBody.PersistedRowDetails
        assertEquals(JsonPrimitive("reason"), body.sidecars["reasoning_content"])
        assertEquals("[{\"type\":\"unknown\"}]", body.sidecars["codex_message_items"].toString())
    }
    @Test fun prefix_insertion_and_duplicate_page_rows_use_durable_keys_not_positions() {
        val base = projectRestHistory(page(rows), "stored", "p")
        val prefixed = projectRestHistory(page(rows.replaceFirst("[", "[{\"id\":9,\"role\":\"user\",\"content\":\"old\"},")), "stored", "p")
        assertEquals(base.entries.map { it.key }, prefixed.entries.drop(1).map { it.key })
        val duplicates = projectRestHistory(page("""[{"id":1,"role":"user","content":"x"},{"id":1,"role":"user","content":"x"}]"""), "stored", "p")
        assertEquals(1, duplicates.entries.size)
    }
    @Test fun page_completeness_and_display_markers_are_explicit() {
        val page = json.decodeFromString(MessagesDto.serializer(), """{"messages":[{"id":1,"role":"user","content":"real"},{"id":2,"role":"user","content":"generated","display_kind":"auto_continue"},{"id":3,"role":"assistant","content":"hidden","display_kind":"hidden"},{"id":4,"role":"user","content":"physical","display_content":"display"}],"pagination":{"limit":500,"returned":500}}""")
        val snapshot = projectRestHistory(page, "stored", "p")
        assertTrue(snapshot.completeness.mayHaveOlderRows)
        assertTrue(snapshot.completeness.excludesCompacted)
        assertEquals("display", lastUserPrompt(snapshot))
        assertEquals(3, snapshot.entries.size)
        assertEquals("real", lastUserPrompt(snapshot.copy(entries = snapshot.entries.take(2))))
    }
}
