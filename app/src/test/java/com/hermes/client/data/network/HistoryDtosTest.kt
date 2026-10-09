package com.hermes.client.data.network

import com.hermes.client.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HistoryDtosTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun rest_polymorphic_content_is_safe_and_does_not_abort_page() {
        val page = json.decodeFromString(MessagesDto.serializer(), """{"messages":[
            {"id":9000000000,"role":"user","content":[{"type":"text","text":"hi"},{"type":"image_url","image_url":{"url":"synthetic"}}]},
            {"id":2,"role":"assistant","content":{"future":"opaque"}},
            {"id":3,"role":"assistant","content":null},
            {"id":4,"role":"assistant","content":42},
            {"id":5,"role":"assistant","content":"after"}
        ]}""")
        val timeline = projectRestHistory(page, "s", "p")
        assertEquals(9000000000L, timeline.entries.first().source.rowId)
        assertEquals("hi\n[image_url]", (timeline.entries.first().body as TimelineBody.Message).content.text)
        assertEquals(4, timeline.entries.size)
        assertEquals("after", (timeline.entries.last().body as TimelineBody.Message).content.text)
        assertEquals("[Unsupported content]", (timeline.entries[1].body as TimelineBody.Message).content.text)
    }
    @Test fun resume_is_a_separate_text_and_compact_tool_schema() {
        val resume = json.decodeFromString(ResumeDto.serializer(), """{"session_id":"live","session_key":"stored","running":true,"messages":[{"row_id":1,"role":"user","text":"prompt"},{"role":"tool","name":"search","context":"preview","args":{"q":"fixture"},"tool_call_id":"c"}],"inflight":{"user":"prompt","assistant":"abc","streaming":true,"corrections":["correct"],"correction_offsets":[1]},"pending_approval":{"command":"synthetic"},"open_requests":[]}""")
        assertEquals("prompt", (resume.messages.first().text as JsonPrimitive).content)
        assertNull(resume.messages.last().content)
        val compact = projectResumeHistory(resume, "stored", "p")
        assertTrue(compact.entries.last().body is TimelineBody.ToolResult)
        assertEquals("[Output unavailable in resume history]", (compact.entries.last().body as TimelineBody.ToolResult).result.text)
        val timeline = TimelineSnapshot().projectResume(resume, "p", "stored")
        assertEquals(listOf("prompt", "a", "correct", "bc"), timeline.entries.mapNotNull { (it.body as? TimelineBody.Message)?.content?.text })
        assertEquals(OrderEvidence.RESUMED_UNKNOWN, timeline.entries.last().orderEvidence)
        assertEquals("Resumed pending approval", (timeline.entries.last().body as TimelineBody.Interaction).text)
        assertTrue(timeline.generating)
        assertTrue(timeline.ready)
    }
    @Test fun optional_sequence_is_retained_and_older_events_still_parse() {
        assertEquals(7L, ServerEvent.from(json.parseToJsonElement("""{"type":"message.delta","seq":7,"payload":{"session_id":"s","text":"x"}}""").jsonObject).seq)
        assertNull(ServerEvent.from(buildJsonObject { put("type", "message.start") }).seq)
    }
}
