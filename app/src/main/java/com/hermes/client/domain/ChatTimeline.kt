package com.hermes.client.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Render entries in supplied order, keyed by key. Revision also changes on in-place growth.
 * messages is a legacy selector only: it cannot represent this timeline's ordering evidence.
 */
data class TimelineSnapshot(
    val entries: List<TimelineEntry> = emptyList(),
    val revision: Long = 0,
    val ready: Boolean = false,
    val generating: Boolean = false,
    val completeness: HistoryCompleteness = HistoryCompleteness(),
    val activeTurn: String? = null,
    val nextLocalId: Long = 0,
    val seenSequences: Set<Long> = emptySet(),
    val persistedTurn: JsonObject? = null,
    val persistedTurnId: String? = null,
)
data class HistoryCompleteness(
    val mayHaveOlderRows: Boolean = true,
    val excludesCompacted: Boolean = true,
    val resolvedSessionId: String? = null,
    val ownerProfile: String? = null,
    val overlapUnconfirmed: Boolean = false,
    val liveEventsIncomplete: Boolean = false,
)
data class SourceRef(val profile: String?, val sessionId: String?, val rowId: Long? = null,
                     val turnId: String? = null, val sequence: Long? = null)
enum class OrderEvidence { PERSISTED_ROW, WITHIN_ROW_UNKNOWN, LIVE_ARRIVAL, RESUMED_UNKNOWN }
data class TimelineEntry(val key: String, val source: SourceRef, val body: TimelineBody,
                         val orderEvidence: OrderEvidence)
enum class ToolState { RUNNING, COMPLETED, INTERRUPTED, UNKNOWN }
enum class InteractionState { PENDING, ANSWERED }
sealed interface TimelineBody {
    data class Message(val role: Role, val content: DisplayContent, val streaming: Boolean = false,
                       val authored: Boolean = true, val displayKind: String? = null,
                       val metadata: JsonElement? = null) : TimelineBody
    data class Reasoning(val text: String, val streaming: Boolean = false) : TimelineBody
    data class ToolInvocation(val callId: String?, val name: String, val args: JsonElement?,
                              val state: ToolState = ToolState.UNKNOWN) : TimelineBody
    data class ToolResult(val callId: String?, val name: String?, val result: DisplayContent,
                          val metadata: JsonElement? = null) : TimelineBody
    data class Interaction(val kind: String, val requestId: String?, val text: String,
                           val state: InteractionState = InteractionState.PENDING) : TimelineBody
    data class Notice(val kind: String?, val text: String) : TimelineBody
    /** Sidecars share a row, not an event clock. Do not sort/flatten this into asserted chronology. */
    data class PersistedRowDetails(val content: DisplayContent, val reasoning: JsonElement?,
                                   val sidecars: JsonObject, val metadata: JsonElement?) : TimelineBody
}
data class DisplayContent(val parts: List<ContentPart>, val original: JsonElement? = null) {
    val text: String get() = parts.joinToString("\n") { when (it) {
        is ContentPart.Text -> it.text
        is ContentPart.Media -> "[${it.kind}]"
        is ContentPart.Unsupported -> "[Unsupported content]"
    } }
}
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class Media(val kind: String, val value: JsonElement) : ContentPart
    data class Unsupported(val value: JsonElement) : ContentPart
}
fun lastUserPrompt(snapshot: TimelineSnapshot): String? = snapshot.entries.asReversed().firstNotNullOfOrNull {
    (it.body as? TimelineBody.Message)?.takeIf { body -> body.role == Role.USER && body.authored }?.content?.text
}

/** Deliberately lossy compatibility view for non-timeline consumers, never used for reconciliation. */
fun TimelineSnapshot.legacyMessages(): List<ChatMessage> = entries.mapNotNull { entry ->
    when (val body = entry.body) {
        is TimelineBody.Message -> ChatMessage(entry.key, body.role, body.content.text, isStreaming = body.streaming)
        is TimelineBody.PersistedRowDetails -> ChatMessage(entry.key, Role.ASSISTANT, body.content.text)
        is TimelineBody.Notice -> ChatMessage(entry.key, Role.SYSTEM, body.text)
        else -> null
    }
}
