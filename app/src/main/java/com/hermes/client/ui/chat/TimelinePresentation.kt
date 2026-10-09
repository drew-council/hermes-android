package com.hermes.client.ui.chat

import com.hermes.client.domain.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal data class PersistedReasoningDisclosure(val field: String, val text: String)

/** Fields belong to one stored row, not an event clock. Preserve distinct fields without
 * asserting their relative timing or interpreting provider-specific structured payloads.
 */
internal fun persistedReasoningDisclosures(body: TimelineBody.PersistedRowDetails): List<PersistedReasoningDisclosure> =
    listOf("reasoning" to body.reasoning, "reasoning_content" to body.sidecars["reasoning_content"])
        .mapNotNull { (field, value) ->
            when {
                value == null || value == JsonNull -> null
                value is JsonPrimitive && value.isString -> value.content.takeIf { it.isNotBlank() }
                    ?.let { PersistedReasoningDisclosure(field, it) }
                else -> PersistedReasoningDisclosure(field, "Stored reasoning details unavailable (unsupported format)")
            }
        }

/** Search/actions use the same entry indices as the lazy renderer, never legacy bucket indices. */
fun timelineSearchText(body: TimelineBody): String = when (body) {
    is TimelineBody.Message -> body.content.text
    is TimelineBody.PersistedRowDetails -> body.content.text
    is TimelineBody.Notice -> body.text
    else -> ""
}
fun timelineMatchIndices(snapshot: TimelineSnapshot, query: String): List<Int> =
    if (query.isBlank()) emptyList() else snapshot.entries.mapIndexedNotNull { index, entry ->
        index.takeIf { timelineSearchText(entry.body).contains(query, ignoreCase = true) }
    }

sealed interface ChatRenderItem {
    data class Entry(val entry: TimelineEntry, val timelineIndex: Int) : ChatRenderItem
    data class Request(val request: com.hermes.client.data.network.PendingServerRequest, val anchored: Boolean) : ChatRenderItem
}

/** Anchors represent first UI observation, not server event ordering. Replays never invent a past position. */
fun chatRenderItems(snapshot: TimelineSnapshot,
                    requests: List<com.hermes.client.data.network.PendingServerRequest>,
                    anchors: Map<com.hermes.client.data.network.PendingServerRequest, String>): List<ChatRenderItem> {
    val keys = snapshot.entries.map { it.key }.toSet()
    return buildList {
        requests.filter { !it.replayed && anchors[it] == "^start" }.forEach { add(ChatRenderItem.Request(it, true)) }
        snapshot.entries.forEachIndexed { index, entry ->
            add(ChatRenderItem.Entry(entry, index))
            requests.filter { anchors[it] == entry.key && !it.replayed }.forEach { add(ChatRenderItem.Request(it, true)) }
        }
        requests.filter { it.replayed || (anchors[it] !in keys && anchors[it] != "^start") }.forEach { add(ChatRenderItem.Request(it, false)) }
    }
}

fun hasModernRequest(body: TimelineBody.Interaction,
                     requests: List<com.hermes.client.data.network.PendingServerRequest>): Boolean =
    body.requestId != null && requests.any { req ->
        req.method == body.kind && (req.id == body.requestId ||
            (body.kind == "approval" && req.params.displayString("request_id") == body.requestId))
    }

fun legacyApprovalAt(snapshot: TimelineSnapshot, index: Int, request: ApprovalRequest?): Boolean {
    if (request == null || request.requestId.isNullOrBlank()) return false
    return index == snapshot.entries.indexOfLast {
        val body = it.body as? TimelineBody.Interaction
        body?.kind == "approval" && body.state == InteractionState.PENDING && body.requestId == request.requestId
    }
}

/** Follow intent is independent of geometry after streamed growth. */
data class ChatScrollIntent(val following: Boolean = true) {
    fun readerScroll(atBottom: Boolean) = copy(following = atBottom)
    fun search() = copy(following = false)
    fun latest() = copy(following = true)
}
