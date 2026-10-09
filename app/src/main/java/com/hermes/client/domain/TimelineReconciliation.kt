package com.hermes.client.domain

import kotlinx.serialization.json.*

/** Only a complete receipt plus fetched row identities can retire an unconfirmed overlay.
 * Repeated text/timestamps and absent rows are never identity evidence.
 */
fun TimelineSnapshot.reconcileHistory(fetched: TimelineSnapshot): TimelineSnapshot {
    if ((completeness.resolvedSessionId != null && fetched.completeness.resolvedSessionId != completeness.resolvedSessionId) ||
        (completeness.ownerProfile != null && fetched.completeness.ownerProfile != completeness.ownerProfile)) {
        // A tip/lineage redirect must be visible; do not merge local data into another segment.
        return copy(completeness = completeness.copy(overlapUnconfirmed = true), entries = entries + TimelineEntry(
            "lineage-$nextLocalId", SourceRef(null, fetched.completeness.resolvedSessionId),
            TimelineBody.Notice("lineage-changed", "Session history moved to another segment"), OrderEvidence.RESUMED_UNKNOWN),
            nextLocalId = nextLocalId + 1, revision = revision + 1)
    }
    val receipt = persistedTurn
    val ids = (receipt?.get("row_ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
    val fetchedIds = fetched.entries.mapNotNull { it.source.rowId }.toSet()
    val userId = (receipt?.get("user_row_id") as? JsonPrimitive)?.longOrNull
    val finalId = (receipt?.get("final_assistant_row_id") as? JsonPrimitive)?.longOrNull
    val complete = (receipt?.get("complete") as? JsonPrimitive)?.booleanOrNull == true && ids.isNotEmpty() &&
        userId != null && finalId != null && userId in ids && finalId in ids && fetchedIds.containsAll(ids) && persistedTurnId != null
    val overlay = entries.filter { it.source.rowId == null &&
        (it.orderEvidence == OrderEvidence.LIVE_ARRIVAL || it.orderEvidence == OrderEvidence.RESUMED_UNKNOWN) &&
        !(complete && it.source.turnId == persistedTurnId && it.body !is TimelineBody.Interaction) }
    return copy(entries = fetched.entries + overlay, revision = revision + 1,
        completeness = fetched.completeness.copy(overlapUnconfirmed = overlay.isNotEmpty(),
            liveEventsIncomplete = completeness.liveEventsIncomplete || fetched.completeness.liveEventsIncomplete),
        persistedTurn = if (complete) null else receipt, persistedTurnId = if (complete) null else persistedTurnId)
}
