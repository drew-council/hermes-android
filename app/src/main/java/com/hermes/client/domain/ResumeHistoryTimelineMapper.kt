package com.hermes.client.domain

import com.hermes.client.data.network.ResumeDto
import kotlinx.serialization.json.*

/** Compact WS history adapter. Never use this as a replacement for rich REST history.
 * Missing tool output/call rows are not recoverable from this projection.
 */
fun projectResumeHistory(resume: ResumeDto, storedSessionId: String, profile: String?): TimelineSnapshot {
    val entries = resume.messages.mapIndexedNotNull { index, row ->
        if (row.displayKind == "hidden") return@mapIndexedNotNull null
        val source = SourceRef(profile, storedSessionId, row.rowId)
        val body: TimelineBody = if (row.role == "tool") {
            TimelineBody.ToolResult(row.toolCallId, row.name,
                row.content?.let { displayContent(it) } ?: displayContent(JsonPrimitive("[Output unavailable in resume history]")),
                buildJsonObject {
                    row.args?.let { put("args", it) }
                    row.context?.let { put("context", it) }
                    row.displayMetadata?.let { put("display_metadata", it) }
                })
        } else {
            val content = displayContent(row.text)
            val sidecars = buildJsonObject {
                row.reasoningContent?.let { put("reasoning_content", decodedJson(it)!!) }
                row.reasoningDetails?.let { put("reasoning_details", decodedJson(it)!!) }
                row.thinkingBlocks?.let { put("thinking_blocks", decodedJson(it)!!) }
                row.codexReasoningItems?.let { put("codex_reasoning_items", decodedJson(it)!!) }
                row.codexMessageItems?.let { put("codex_message_items", decodedJson(it)!!) }
            }
            if (row.role == "assistant" && (row.reasoning != null || sidecars.isNotEmpty()))
                TimelineBody.PersistedRowDetails(content, decodedJson(row.reasoning), sidecars, row.displayMetadata)
            else {
                if (content.parts.isEmpty()) return@mapIndexedNotNull null
                val role = when (row.role) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; else -> Role.SYSTEM }
                TimelineBody.Message(role, content, authored = row.displayKind != "auto_continue",
                    displayKind = row.displayKind, metadata = row.displayMetadata)
            }
        }
        TimelineEntry("resume:${profile ?: "default"}:$storedSessionId:${row.rowId ?: "unconfirmed-$index"}", source,
            body, if (body is TimelineBody.PersistedRowDetails) OrderEvidence.WITHIN_ROW_UNKNOWN else OrderEvidence.RESUMED_UNKNOWN)
    }
    return TimelineSnapshot(entries, revision = 1, completeness = HistoryCompleteness(resolvedSessionId = storedSessionId, ownerProfile = profile,
        liveEventsIncomplete = true))
}
