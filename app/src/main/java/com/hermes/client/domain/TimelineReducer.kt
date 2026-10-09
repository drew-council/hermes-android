package com.hermes.client.domain

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import kotlinx.serialization.json.*

/** Arrival order is evidence only for the attached epoch. Unknown tool ownership stays orphaned. */
fun TimelineSnapshot.reduceTimeline(event: ServerEvent): TimelineSnapshot {
    if (event.seq != null && event.seq in seenSequences) return this
    var state = this
    val source = SourceRef(null, event.sessionId, turnId = activeTurn, sequence = event.seq)
    fun append(body: TimelineBody, turn: String? = state.activeTurn,
               evidence: OrderEvidence = OrderEvidence.LIVE_ARRIVAL) {
        val id = state.nextLocalId
        state = state.copy(entries = state.entries + TimelineEntry("live-$id", source.copy(turnId = turn), body,
            evidence), nextLocalId = id + 1)
    }
    fun update(index: Int, body: TimelineBody) {
        state = state.copy(entries = state.entries.mapIndexed { i, entry -> if (i == index) entry.copy(body = body) else entry })
    }
    fun seal() {
        state = state.copy(entries = state.entries.map { entry ->
            if (entry.source.turnId != state.activeTurn) entry else when (val body = entry.body) {
                is TimelineBody.Message -> entry.copy(body = body.copy(streaming = false))
                is TimelineBody.Reasoning -> entry.copy(body = body.copy(streaming = false))
                else -> entry
            }
        })
    }
    fun text(value: JsonElement?, reasoning: Boolean, snapshot: Boolean = false) {
        val content = displayContent(value)
        if (content.parts.isEmpty()) return
        if (state.activeTurn == null) {
            append(if (reasoning) TimelineBody.Reasoning(content.text, false) else TimelineBody.Message(Role.ASSISTANT, content), null)
            return
        }
        val last = state.entries.lastOrNull()
        val body = last?.body
        if (last?.source?.turnId == state.activeTurn &&
            ((reasoning && body is TimelineBody.Reasoning && body.streaming) ||
                (!reasoning && body is TimelineBody.Message && body.role == Role.ASSISTANT && body.streaming))) {
            val replacement = if (reasoning) {
                body as TimelineBody.Reasoning
                body.copy(text = if (snapshot) content.text else body.text + content.text)
            } else {
                body as TimelineBody.Message
                body.copy(content = if (snapshot) content else displayContent(JsonPrimitive(body.content.text + content.text)))
            }
            update(state.entries.lastIndex, replacement)
        } else {
            seal()
            append(if (reasoning) TimelineBody.Reasoning(content.text, true) else TimelineBody.Message(Role.ASSISTANT, content, true))
        }
    }
    // reasoning.available completes one block, not all reasoning accumulated in the turn.
    fun reasoningBlock(value: JsonElement?) {
        if (value == null || value == JsonNull) return
        val content = displayContent(value)
        if (content.parts.isEmpty()) return
        val last = state.entries.lastOrNull()
        val body = last?.body as? TimelineBody.Reasoning
        if (state.activeTurn != null && last?.source?.turnId == state.activeTurn && body?.streaming == true) {
            update(state.entries.lastIndex, body.copy(text = content.text, streaming = false))
        } else {
            seal()
            append(TimelineBody.Reasoning(content.text, false))
        }
    }
    // Only terminal reasoning is a whole-turn snapshot; sealed observed blocks keep their positions.
    fun reasoningSnapshot(value: JsonElement?) {
        if (value == null || value == JsonNull) return
        val indices = state.entries.indices.filter { state.entries[it].source.turnId == state.activeTurn && state.entries[it].body is TimelineBody.Reasoning }
        val full = displayContent(value).text
        val accumulated = indices.joinToString("") { (state.entries[it].body as TimelineBody.Reasoning).text }
        // A full snapshot is not a new delta. Preserve disjoint observed blocks if already identical.
        if (full == accumulated) return
        if (indices.size == 1 && (state.entries[indices.single()].body as TimelineBody.Reasoning).streaming)
            update(indices.single(), TimelineBody.Reasoning(full, false))
        else if (indices.isEmpty()) append(TimelineBody.Reasoning(full, false))
        else append(TimelineBody.PersistedRowDetails(DisplayContent(emptyList()), value,
            buildJsonObject { put("snapshot_overlap_unconfirmed", true) }, null))
    }
    when (event.type) {
        "message.start" -> {
            seal()
            val turn = "turn-${state.nextLocalId}"
            val userIndex = state.entries.indexOfLast { val body = it.body as? TimelineBody.Message
                body?.role == Role.USER && it.source.rowId == null && it.source.turnId == null }
            if (userIndex >= 0) state = state.copy(entries = state.entries.mapIndexed { i, entry ->
                if (i == userIndex) entry.copy(source = entry.source.copy(turnId = turn)) else entry })
            state = state.copy(activeTurn = turn, nextLocalId = state.nextLocalId + 1, generating = true)
        }
        "message.delta" -> text(event.payload["text"], false)
        "reasoning.delta" -> text(event.payload["text"], true)
        "reasoning.available" -> reasoningBlock(event.payload["text"])
        "message.interim" -> {
            if (event.bool("already_streamed") != true) { seal(); text(event.payload["text"], false, true) }
            seal()
        }
        "tool.start" -> {
            seal()
            append(TimelineBody.ToolInvocation(event.str("tool_id"), event.str("name") ?: "tool",
                event.payload["args"] ?: event.payload["arguments"], ToolState.RUNNING))
        }
        "tool.complete" -> {
            val callId = event.str("tool_id")
            val explicitTurn = event.str("turn_id")
            val matches = state.entries.indices.filter { i ->
                val entry = state.entries[i]
                val call = entry.body as? TimelineBody.ToolInvocation
                call != null && callId != null && call.callId == callId && entry.source.sessionId == event.sessionId &&
                    (explicitTurn == null || entry.source.turnId == explicitTurn)
            }
            // Without turn identity, reused ids cannot safely select one invocation.
            val index = matches.singleOrNull()
            if (index != null) {
                val body = state.entries[index].body as TimelineBody.ToolInvocation
                update(index, body.copy(state = ToolState.COMPLETED))
            }
            seal()
            append(TimelineBody.ToolResult(callId, event.str("name"), displayContent(event.payload["result"]),
                buildJsonObject {
                    listOf("inline_diff", "duration_s", "summary", "labels", "todos", "metadata", "result_meta").forEach { key ->
                        event.payload[key]?.let { put(key, it) }
                    }
                }), index?.let { state.entries[it].source.turnId })
        }
        "message.complete" -> {
            if (state.activeTurn != null) {
                text(event.payload["text"] ?: event.payload["rendered"], false, true)
                reasoningSnapshot(event.payload["reasoning"])
                seal()
            } else {
                // Text-only producers and stopped-resume races have no proven local turn boundary.
                // Retain the receipt and content without assigning them to another observed turn.
                val final = displayContent(event.payload["text"] ?: event.payload["rendered"])
                if (final.parts.isNotEmpty()) append(TimelineBody.Message(Role.ASSISTANT, final), null, OrderEvidence.RESUMED_UNKNOWN)
                val reasoning = displayContent(event.payload["reasoning"])
                if (reasoning.parts.isNotEmpty()) append(TimelineBody.Reasoning(reasoning.text, false), null, OrderEvidence.RESUMED_UNKNOWN)
            }
            val status = event.str("status")
            if (status == "interrupted" || status == "error") {
                state = state.copy(entries = state.entries.map { entry ->
                    val call = entry.body as? TimelineBody.ToolInvocation
                    if (state.activeTurn != null && entry.source.turnId == state.activeTurn && call?.state == ToolState.RUNNING)
                        entry.copy(body = call.copy(state = ToolState.INTERRUPTED)) else entry
                })
                append(TimelineBody.Notice(status, event.str("error") ?: event.str("failure_reason") ?: status))
            }
            state = state.copy(generating = false, activeTurn = null,
                persistedTurn = event.payload["persisted_turn"] as? JsonObject,
                persistedTurnId = state.activeTurn,
                completeness = state.completeness.copy(overlapUnconfirmed = true))
        }
        "approval.request", "clarify.request" -> {
            seal()
            append(TimelineBody.Interaction(event.type.substringBefore('.'), event.str("request_id"),
                if (event.type == "approval.request") "Approval requested" else event.str("question") ?: "Clarification requested"))
        }
        "user.message", "message.user" -> { seal(); append(TimelineBody.Message(Role.USER, displayContent(event.payload["text"]))) }
        "error" -> { seal(); append(TimelineBody.Notice("error", event.str("message") ?: "Error")); state = state.copy(generating = false) }
    }
    if (state == this) return this
    return state.copy(revision = revision + 1, seenSequences = if (event.seq == null) seenSequences else (seenSequences + event.seq).takeLastSet(2048))
}
private fun Set<Long>.takeLastSet(limit: Int) = toList().takeLast(limit).toSet()

fun TimelineSnapshot.withAuthoredPrompt(text: String): TimelineSnapshot {
    val entry = TimelineEntry("live-$nextLocalId", SourceRef(null, completeness.resolvedSessionId),
        TimelineBody.Message(Role.USER, displayContent(JsonPrimitive(text))), OrderEvidence.LIVE_ARRIVAL)
    return copy(entries = entries + entry, revision = revision + 1, nextLocalId = nextLocalId + 1)
}
fun TimelineSnapshot.resolveInteraction(kind: String, requestId: String?): TimelineSnapshot {
    val index = entries.indexOfLast { val b = it.body as? TimelineBody.Interaction
        b?.kind == kind && b.state == InteractionState.PENDING && (requestId == null || b.requestId == requestId) }
    if (index < 0) return this
    return copy(entries = entries.mapIndexed { i, entry -> if (i == index) entry.copy(body =
        (entry.body as TimelineBody.Interaction).copy(state = InteractionState.ANSWERED)) else entry }, revision = revision + 1)
}

fun TimelineSnapshot.withNotice(kind: String, text: String): TimelineSnapshot = copy(
    entries = entries + TimelineEntry("live-$nextLocalId", SourceRef(null, completeness.resolvedSessionId),
        TimelineBody.Notice(kind, text), OrderEvidence.LIVE_ARRIVAL),
    revision = revision + 1, nextLocalId = nextLocalId + 1,
)
