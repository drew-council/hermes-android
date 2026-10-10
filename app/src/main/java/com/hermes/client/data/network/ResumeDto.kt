package com.hermes.client.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** WS display projection is NOT the rich REST row schema. Opaque fields remain source data. */
@Serializable data class ResumeMessageDto(
    val role: String = "unknown", val text: JsonElement? = null, val content: JsonElement? = null,
    @SerialName("row_id") val rowId: Long? = null,
    val name: String? = null, val context: String? = null, val args: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
    @SerialName("display_metadata") val displayMetadata: JsonElement? = null,
    val reasoning: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: JsonElement? = null,
    @SerialName("codex_reasoning_items") val codexReasoningItems: JsonElement? = null,
    @SerialName("codex_message_items") val codexMessageItems: JsonElement? = null,
    @SerialName("reasoning_details") val reasoningDetails: JsonElement? = null,
    @SerialName("thinking_blocks") val thinkingBlocks: JsonElement? = null,
)
@Serializable data class InflightDto(
    val user: String = "", val assistant: String = "", val streaming: Boolean = false,
    val corrections: List<String> = emptyList(),
    @SerialName("correction_offsets") val correctionOffsets: List<Int> = emptyList(),
)
@Serializable data class ResumeDto(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_key") val sessionKey: String? = null,
    @SerialName("stored_session_id") val storedSessionId: String? = null,
    val messages: List<ResumeMessageDto> = emptyList(),
    @SerialName("messages_omitted") val messagesOmitted: Boolean = false,
    val running: Boolean = false, val inflight: InflightDto? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("turn_started_at") val turnStartedAt: Double? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    val status: String? = null,
    val queued: JsonElement? = null,
    @SerialName("pending_approval") val pendingApproval: JsonObject? = null,
    @SerialName("open_requests") val openRequests: JsonElement? = null,
    @SerialName("pending_connection") val pendingConnection: JsonElement? = null,
    val info: JsonObject? = null,
)
