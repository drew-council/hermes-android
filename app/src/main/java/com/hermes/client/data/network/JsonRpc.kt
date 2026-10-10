package com.hermes.client.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RpcRequest(val id: Long, val method: String, val params: JsonObject) {
    fun encode(json: Json): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}

enum class GatewayRefusalReason { SESSION_NOT_OWNED, MAX_CONCURRENT_SESSIONS, SESSION_COORDINATION_UNAVAILABLE }

data class RpcError(val code: Int, val message: String, val reason: GatewayRefusalReason? = null)

sealed interface RpcInbound
data class RpcResult(val id: Long, val result: JsonElement) : RpcInbound
data class RpcErrorReply(val id: Long, val error: RpcError) : RpcInbound
data class RpcEvent(val event: ServerEvent) : RpcInbound
class RpcServerRequest(val id: String, val method: String, val params: JsonObject?) : RpcInbound {
    override fun toString() = "RpcServerRequest"
}

fun parseInbound(json: Json, line: String): RpcInbound {
    val obj = json.parseToJsonElement(line).jsonObject
    val method = obj["method"]?.jsonPrimitive?.content
    if (method == "event") {
        val params = obj.objOrEmpty("params")
        return RpcEvent(ServerEvent.from(params))
    }
    val wireId = obj["id"] as? JsonPrimitive
    if (method != null && wireId?.isString == true) {
        return RpcServerRequest(wireId.content, method, obj["params"] as? JsonObject)
    }
    require(method == null && wireId != null && !wireId.isString) { "Invalid RPC frame" }
    val id = requireNotNull(wireId.longOrNull) { "Invalid RPC id" }
    obj["error"]?.let {
        val e = it.jsonObject
        return RpcErrorReply(
            id,
            RpcError(
                code = e["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                message = e["message"]?.jsonPrimitive?.content ?: "error",
                // Whitelisted machine-readable reasons only; never expose arbitrary server text/data.
                reason = if ((e["code"] as? JsonPrimitive)?.content == "4090") {
                    val reason = ((e["data"] as? JsonObject)?.get("reason") as? JsonPrimitive)?.content
                    GatewayRefusalReason.entries.firstOrNull { it.name == reason }
                } else null,
            ),
        )
    }
    return RpcResult(id, obj["result"] ?: JsonPrimitive("null"))
}
