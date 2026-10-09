package com.hermes.client.ui.chat

import com.hermes.client.data.network.MessagesDto
import com.hermes.client.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PersistedReasoningPresentationTest {
    private fun details(reasoning: JsonElement? = null, sidecars: JsonObject = buildJsonObject {}) =
        TimelineBody.PersistedRowDetails(DisplayContent(emptyList()), reasoning, sidecars, null)

    @Test fun restored_reasoning_content_is_disclosed_inside_original_ambiguous_row() {
        val page = Json.decodeFromString(MessagesDto.serializer(), """{"messages":[
            {"id":1,"role":"user","content":"Fixture prompt"},
            {"id":2,"role":"assistant","content":"Fixture reply","reasoning_content":"Fixture thinking"},
            {"id":3,"role":"tool","content":"Fixture result","tool_call_id":"fixture-call"}
        ]}""")
        val snapshot = projectRestHistory(page, "fixture-session", null)
        val originalKeys = snapshot.entries.map { it.key }
        val row = snapshot.entries[1]
        assertEquals(OrderEvidence.WITHIN_ROW_UNKNOWN, row.orderEvidence)
        assertEquals(listOf(PersistedReasoningDisclosure("reasoning_content", "Fixture thinking")),
            persistedReasoningDisclosures(row.body as TimelineBody.PersistedRowDetails))
        assertEquals(originalKeys, snapshot.entries.map { it.key })
        assertEquals(listOf(1L, 2L, 3L), snapshot.entries.map { it.source.rowId })
    }

    @Test fun distinct_reasoning_fields_are_not_overwritten_or_merged() {
        val body = details(JsonPrimitive("Primary fixture reasoning"), buildJsonObject {
            put("reasoning_content", "Separate fixture reasoning")
        })
        assertEquals(listOf(
            PersistedReasoningDisclosure("reasoning", "Primary fixture reasoning"),
            PersistedReasoningDisclosure("reasoning_content", "Separate fixture reasoning")),
            persistedReasoningDisclosures(body))
        assertEquals(2, persistedReasoningDisclosures(body.copy(reasoning = JsonPrimitive("Separate fixture reasoning"))).size)
    }

    @Test fun unsupported_structured_or_nontext_reasoning_has_only_safe_placeholder() {
        val unsupported = listOf<JsonElement>(
            buildJsonObject { put("private_provider_shape", "SYNTHETIC_OPAQUE_SENTINEL") },
            buildJsonArray { add("SYNTHETIC_OPAQUE_SENTINEL") }, JsonPrimitive(42), JsonPrimitive(true))
        unsupported.forEach { value ->
            val body = details(value, buildJsonObject { put("reasoning_content", value) })
            val disclosures = persistedReasoningDisclosures(body)
            assertEquals(listOf("reasoning", "reasoning_content"), disclosures.map { it.field })
            assertTrue(disclosures.all { it.text == "Stored reasoning details unavailable (unsupported format)" })
            assertFalse(disclosures.toString().contains("SYNTHETIC_OPAQUE_SENTINEL"))
            assertEquals(value, body.sidecars["reasoning_content"])
        }
    }

    @Test fun absent_null_and_blank_text_do_not_create_empty_disclosures() {
        listOf<JsonElement?>(null, JsonNull, JsonPrimitive(""), JsonPrimitive(" \n ")).forEach { value ->
            assertTrue(persistedReasoningDisclosures(details(value, buildJsonObject {
                value?.let { put("reasoning_content", it) }
            })).isEmpty())
        }
    }

    @Test fun opaque_provider_sidecars_are_not_dumped_or_guessed_as_textual_reasoning() {
        val body = details(sidecars = buildJsonObject {
            put("codex_message_items", buildJsonArray { add(buildJsonObject { put("text", "SYNTHETIC_PROVIDER_SENTINEL") }) })
            put("tool_calls", buildJsonObject { put("unknown", "SYNTHETIC_PROVIDER_SENTINEL") })
        })
        assertTrue(persistedReasoningDisclosures(body).isEmpty())
    }
}
