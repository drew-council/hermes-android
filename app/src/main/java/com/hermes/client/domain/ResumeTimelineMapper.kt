package com.hermes.client.domain

import com.hermes.client.data.network.ResumeDto
import kotlinx.serialization.json.*

/** REST remains the durable base. The warm snapshot is not a complete ordered live event log.
 * Observed deltas do not prove full overlap. Preserve the recovered snapshot/corrections as a
 * separate sealed region when overlap is unproven; never concatenate or text-dedup it.
 */
fun TimelineSnapshot.projectResume(resume: ResumeDto, profile: String?, storedSessionId: String,
                                   hasLiveOverlay: Boolean = false): TimelineSnapshot {
    if ((resume.sessionKey != null && completeness.resolvedSessionId != null && resume.sessionKey != completeness.resolvedSessionId) ||
        (profile != null && completeness.ownerProfile != null && profile != completeness.ownerProfile)) {
        return withNotice("lineage-changed", "Session identity changed during resume").copy(ready = false,
            completeness = completeness.copy(overlapUnconfirmed = true))
    }
    var next = nextLocalId
    val turn = if (resume.running) activeTurn ?: "resumed-turn-${next++}" else null
    val recovered = mutableListOf<TimelineEntry>()
    fun add(body: TimelineBody) {
        recovered += TimelineEntry("live-${next++}", SourceRef(profile, completeness.resolvedSessionId ?: storedSessionId, turnId = turn),
            body, OrderEvidence.RESUMED_UNKNOWN)
    }
    resume.inflight?.let { inflight ->
        val hasLocalUser = entries.any { it.source.rowId == null && it.source.turnId == turn &&
            (it.body as? TimelineBody.Message)?.role == Role.USER }
        if (!hasLocalUser && inflight.user.isNotBlank()) add(TimelineBody.Message(Role.USER, displayContent(JsonPrimitive(inflight.user))))
        if (hasLiveOverlay) add(TimelineBody.Notice("resume-overlap-unconfirmed",
            "Recovered reply and corrections; overlap with observed events is unconfirmed"))
        var offset = 0
        inflight.corrections.forEachIndexed { index, correction ->
            val boundary = inflight.correctionOffsets.getOrNull(index)
            if (boundary != null && boundary in offset..inflight.assistant.length) {
                if (boundary > offset) add(TimelineBody.Message(Role.ASSISTANT,
                    displayContent(JsonPrimitive(inflight.assistant.substring(offset, boundary)))))
                offset = boundary
            }
            add(TimelineBody.Message(Role.USER, displayContent(JsonPrimitive(correction))))
        }
        if (offset < inflight.assistant.length) add(TimelineBody.Message(Role.ASSISTANT,
            displayContent(JsonPrimitive(inflight.assistant.substring(offset))), resume.running && !hasLiveOverlay))
    }
    val pending = (resume.openRequests as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    pending.forEach { request ->
        val method = (request["method"] as? JsonPrimitive)?.content ?: return@forEach
        val kind = when (method) { "approval" -> "approval"; "clarify" -> "clarify"; else -> "request" }
        val requestId = (request["id"] as? JsonPrimitive)?.content
        val exists = (entries + recovered).any { (it.body as? TimelineBody.Interaction)?.let { body ->
            body.requestId == requestId && body.kind == kind && body.state == InteractionState.PENDING } == true }
        if (!exists) add(TimelineBody.Interaction(kind, requestId, "Resumed pending $kind"))
    }
    if (resume.pendingApproval != null && (entries + recovered).none { (it.body as? TimelineBody.Interaction)?.let { b -> b.kind == "approval" && b.state == InteractionState.PENDING } == true }) {
        // Do not retrospectively record command/request payloads or already submitted answers.
        add(TimelineBody.Interaction("approval", null, "Resumed pending approval"))
    }
    val retained = if (resume.running) entries else entries.map { entry ->
        if (entry.source.turnId != activeTurn) entry else when (val body = entry.body) {
            is TimelineBody.Message -> entry.copy(body = body.copy(streaming = false))
            is TimelineBody.Reasoning -> entry.copy(body = body.copy(streaming = false))
            is TimelineBody.ToolInvocation -> if (body.state == ToolState.RUNNING) entry.copy(body = body.copy(state = ToolState.UNKNOWN)) else entry
            else -> entry
        }
    }
    return copy(entries = retained + recovered, revision = revision + 1, ready = true,
        generating = resume.running, activeTurn = turn, nextLocalId = next,
        completeness = completeness.copy(ownerProfile = profile, overlapUnconfirmed = hasLiveOverlay || resume.inflight != null))
}
