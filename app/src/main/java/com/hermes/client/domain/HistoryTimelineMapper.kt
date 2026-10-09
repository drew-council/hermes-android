package com.hermes.client.domain

import com.hermes.client.data.network.MessagesDto
import kotlinx.serialization.json.*

/** Decode JSON-string sidecars without interpreting their provider-specific timing. */
fun decodedJson(value: JsonElement?): JsonElement? = if (value is JsonPrimitive && value.isString &&
    value.content.trimStart().firstOrNull() in listOf('{', '[', '"')) {
    runCatching { Json.parseToJsonElement(value.content) }.getOrDefault(value)
} else value

fun displayContent(value: JsonElement?): DisplayContent {
    fun parts(element: JsonElement?): List<ContentPart> = when (element) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> if (element.isString) listOf(ContentPart.Text(element.content)) else listOf(ContentPart.Unsupported(element))
        is JsonArray -> element.flatMap { parts(it) }
        is JsonObject -> when ((element["type"] as? JsonPrimitive)?.content) {
            "text", "input_text", "output_text" -> (element["text"] as? JsonPrimitive)?.content?.let { listOf(ContentPart.Text(it)) }
                ?: listOf(ContentPart.Unsupported(element))
            "image", "image_url", "input_image", "audio", "video" -> listOf(ContentPart.Media((element["type"] as JsonPrimitive).content, element))
            else -> listOf(ContentPart.Unsupported(element))
        }
    }
    return DisplayContent(parts(value), value)
}

fun projectRestHistory(page: MessagesDto, requestedSessionId: String, requestedProfile: String?): TimelineSnapshot {
    val session = page.sessionId ?: requestedSessionId
    val profile = page.profile ?: requestedProfile
    val entries = mutableListOf<TimelineEntry>()
    val seen = mutableSetOf<Long>()
    page.messages.forEachIndexed { index, row ->
        if (row.id != null && !seen.add(row.id)) return@forEachIndexed
        if (row.displayKind == "hidden") return@forEachIndexed
        // Missing row ids are explicitly page-local, never used to dedup or prove persistence.
        val key = "history:${profile ?: "default"}:$session:${row.id ?: "unconfirmed-$index"}"
        val source = SourceRef(profile, session, row.id)
        fun add(part: String, body: TimelineBody, evidence: OrderEvidence = OrderEvidence.PERSISTED_ROW) {
            entries += TimelineEntry("$key:$part", source, body, evidence)
        }
        val content = displayContent(row.displayContent ?: row.content)
        if (row.role == "tool") {
            add("result", TimelineBody.ToolResult(row.toolCallId, row.toolName, content, decodedJson(row.displayMetadata)))
            return@forEachIndexed
        }
        val role = when (row.role) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; else -> Role.SYSTEM }
        val sidecars = buildJsonObject {
            row.reasoningContent?.let { put("reasoning_content", decodedJson(it)!!) }
            row.codexMessageItems?.let { put("codex_message_items", decodedJson(it)!!) }
            row.reasoningDetails?.let { put("reasoning_details", decodedJson(it)!!) }
            row.thinkingBlocks?.let { put("thinking_blocks", decodedJson(it)!!) }
            row.codexReasoningItems?.let { put("codex_reasoning_items", decodedJson(it)!!) }
        }
        val calls = decodedJson(row.toolCalls)
        val hasDetails = role == Role.ASSISTANT && (row.reasoning != null || sidecars.isNotEmpty() || calls != null && calls != JsonNull)
        if (hasDetails && (content.parts.isNotEmpty() || row.reasoning != null || sidecars.isNotEmpty())) {
            add("details", TimelineBody.PersistedRowDetails(content, decodedJson(row.reasoning), sidecars, decodedJson(row.displayMetadata)), OrderEvidence.WITHIN_ROW_UNKNOWN)
        } else if (content.parts.isNotEmpty()) {
            if (row.displayKind != null && role == Role.SYSTEM) add("notice", TimelineBody.Notice(row.displayKind, content.text))
            else add("message", TimelineBody.Message(role, content, authored = row.displayKind != "auto_continue",
                displayKind = row.displayKind, metadata = decodedJson(row.displayMetadata)))
        }
        (calls as? JsonArray)?.forEachIndexed { callIndex, call ->
            val obj = call as? JsonObject
            val function = obj?.get("function") as? JsonObject
            add("call-$callIndex", TimelineBody.ToolInvocation(
                (obj?.get("id") as? JsonPrimitive)?.content,
                (function?.get("name") as? JsonPrimitive)?.content ?: "tool",
                decodedJson(function?.get("arguments")),
            ), OrderEvidence.WITHIN_ROW_UNKNOWN)
        }
        if (calls != null && calls != JsonNull && calls !is JsonArray) {
            add("unsupported-calls", TimelineBody.PersistedRowDetails(DisplayContent(emptyList()), null,
                buildJsonObject { put("tool_calls", calls) }, null), OrderEvidence.WITHIN_ROW_UNKNOWN)
        }
    }
    val results = entries.mapNotNull { (it.body as? TimelineBody.ToolResult)?.callId }.toSet()
    val linked = entries.map { entry ->
        val body = entry.body as? TimelineBody.ToolInvocation
        if (body?.callId != null && body.callId in results) entry.copy(body = body.copy(state = ToolState.COMPLETED)) else entry
    }
    return TimelineSnapshot(linked, revision = 1, completeness = HistoryCompleteness(
        mayHaveOlderRows = page.pagination == null || page.pagination.offset > 0 || page.pagination.returned >= page.pagination.limit,
        resolvedSessionId = session, ownerProfile = profile,
    ))
}
