package com.hermes.client.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Display metadata only. Never contains an entered answer; identity is the response authority. */
class PendingServerRequest internal constructor(
    val id: String,
    val method: String,
    val sessionId: String,
    val params: JsonObject,
    val generation: Int,
    val replayed: Boolean,
) {
    override fun toString() = "PendingServerRequest(generation=$generation)"
}

enum class RespondOutcome { ENQUEUED, STALE, DISCONNECTED }

sealed interface ClarificationLockOutcome {
    data class Ok(val remaining: List<String>) : ClarificationLockOutcome
    data object Expired : ClarificationLockOutcome
    data object Stale : ClarificationLockOutcome
    data object Disconnected : ClarificationLockOutcome
}

/** All operations run under the gateway's transport lock, including socket sends. */
internal class ServerRequestCoordinator(private val json: Json) {
    private val requests = linkedMapOf<String, PendingServerRequest>()
    private val terminal = mutableSetOf<String>()
    private var terminalSaturated = false
    private var generation = 0
    private val state = MutableStateFlow<List<PendingServerRequest>>(emptyList())
    val pending = state.asStateFlow()

    fun reset(gen: Int) {
        generation = gen
        requests.clear()
        terminal.clear()
        terminalSaturated = false
        publish()
    }

    private fun publish() { state.value = requests.values.toList() }
    private fun finish(id: String) {
        requests.remove(id)
        // Fail closed for further intake at saturation instead of forgetting settled IDs.
        if (terminal.size < 4096) terminal.add(id) else terminalSaturated = true
        publish()
    }

    fun isCurrent(request: PendingServerRequest) = request.generation == generation && requests[request.id] === request

    fun validateBatchQuestion(request: PendingServerRequest, questionId: String) {
        require(request.method == "clarify" && (request.params["questions"] as? JsonArray)?.any {
            (it as? JsonObject)?.string("qid") == questionId
        } == true) { "Invalid clarification question" }
    }

    fun settle(request: PendingServerRequest) { if (isCurrent(request)) finish(request.id) }

    fun cancel(payload: JsonObject) {
        val id = payload.string("id") ?: return
        val request = requests[id]
        if (request != null && payload.string("session_id") != request.sessionId) return
        finish(id)
    }

    fun receive(frame: RpcServerRequest, replayed: Boolean, available: Boolean, send: (String) -> Boolean) {
        if (frame.id in terminal) return
        if (terminalSaturated) {
            if (!replayed) send(error(frame.id, -32603, "Server request capacity exceeded"))
            return
        }
        if (!available || frame.method !in METHODS) {
            send(error(frame.id, -32601, "Unsupported server request"))
            finish(frame.id)
            return
        }
        val params = frame.params
        // Agent Access must never fall back to collecting a 1Password account/master password.
        if (frame.method == "vault.unlock_prompt" && params != null &&
            listOf("backend", "display_name").any { field ->
                val label = params.string(field)?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()
                "1password" in label || "onepassword" in label || (field == "backend" && label == "op")
            }) {
            send(error(frame.id, -32601, "Unsupported server request"))
            finish(frame.id)
            return
        }
        if (params == null || !validParams(frame.method, params)) {
            send(error(frame.id, -32602, "Invalid server request params"))
            finish(frame.id)
            return
        }
        requests[frame.id]?.let { current ->
            // A snapshot can carry accepted batch locks missing from a preceding live frame.
            // Only merge nonsecret lock metadata; never replace other live display parameters.
            val answers = params["answers"] as? JsonObject
            if (replayed && current.method == "clarify" && frame.method == current.method &&
                current.sessionId == params.string("session_id") && answers != null) {
                val merged = JsonObject(((current.params["answers"] as? JsonObject) ?: JsonObject(emptyMap())) + answers)
                if (merged != current.params["answers"]) {
                    val updated = JsonObject(current.params + ("answers" to merged))
                    requests[frame.id] = PendingServerRequest(current.id, current.method, current.sessionId, updated, generation, current.replayed)
                    publish()
                }
            }
            return // preserve insertion order; old handles become stale only on metadata changes
        }
        if (requests.size >= 128) {
            send(error(frame.id, -32603, "Server request capacity exceeded"))
            finish(frame.id)
            return
        }
        requests[frame.id] = PendingServerRequest(frame.id, frame.method, params.string("session_id")!!, params, generation, replayed)
        publish()
    }

    fun replay(result: JsonElement, available: Boolean, send: (String) -> Boolean) {
        val entries = (result as? JsonObject)?.get("open_requests") as? JsonArray ?: return
        entries.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val id = obj.string("id") ?: return@forEach
            val method = obj.string("method") ?: return@forEach
            receive(RpcServerRequest(id, method, obj["params"] as? JsonObject), true, available, send)
        }
    }

    fun respond(request: PendingServerRequest, result: JsonObject, connected: Boolean, send: (String) -> Boolean): RespondOutcome {
        if (request.generation != generation || requests[request.id] !== request) return RespondOutcome.STALE
        if (!connected) return RespondOutcome.DISCONNECTED
        require(validResult(request, result)) { "Invalid server request result" }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.id); put("result", result)
        }
        val sent = send(json.encodeToString(JsonObject.serializer(), frame))
        // Neither successful nor failed enqueue retains an answer or permits local resubmission.
        finish(request.id)
        return if (sent) RespondOutcome.ENQUEUED else RespondOutcome.DISCONNECTED
    }

    fun fail(request: PendingServerRequest, send: (String) -> Boolean): RespondOutcome {
        if (request.generation != generation || requests[request.id] !== request) return RespondOutcome.STALE
        val sent = send(error(request.id, -32603, "Server request handler failed"))
        finish(request.id)
        return if (sent) RespondOutcome.ENQUEUED else RespondOutcome.DISCONNECTED
    }

    private fun error(id: String, code: Int, message: String) = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    })

    private fun validParams(method: String, p: JsonObject): Boolean {
        if (p.string("session_id").isNullOrBlank()) return false
        fun required(vararg fields: String) = fields.all { p.string(it) != null }
        return when (method) {
            "vault.code" -> listOf("site", "hint").all { p.optionalString(it) }
            "sudo" -> p.optionalString("command")
            "secret" -> required("env_var", "prompt") && (p["metadata"] == null || p["metadata"] == JsonNull || p["metadata"] is JsonObject)
            "vault.unlock_prompt" -> required("backend", "display_name")
            "vault.save_login" -> required("origin", "site")
            "approval" -> required("request_id") &&
                listOf("command", "description", "tool_name", "gateway_session_id").all { p.optionalString(it) } &&
                listOf("allow_session", "allow_permanent", "smart_denied").all { p.optionalBoolean(it) } &&
                (p["choices"] as? JsonArray)?.let { a -> a.isNotEmpty() && a.all { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content in CHOICES } } == true
            "clarify" -> {
                if (p["questions"] != null && p["questions"] != JsonNull) {
                    val questions = p["questions"] as? JsonArray ?: return false
                    val qids = questions.map { (it as? JsonObject)?.string("qid") }
                    questions.isNotEmpty() && qids.all { !it.isNullOrBlank() } && qids.distinct().size == qids.size && questions.all {
                        val q = it as? JsonObject ?: return@all false
                        q.string("question") != null && q.validChoices() && q.optionalBoolean("multi_select")
                    } && (p["answers"] == null || (p["answers"] as? JsonObject)?.all { (qid, value) -> qid in qids && value.isStringValue() } == true)
                } else p.optionalString("question") && p.validChoices() && p.optionalBoolean("multi_select")
            }
            else -> false
        }
    }

    private fun validResult(r: PendingServerRequest, result: JsonObject): Boolean = when (r.method) {
        "approval" -> {
            val choice = result.string("choice")
            val allowed = (r.params["choices"] as JsonArray).map { (it as JsonPrimitive).content }
            result.keys.all { it in setOf("choice", "all") } && choice in allowed &&
                !(choice == "always" && r.params["allow_permanent"] == JsonPrimitive(false)) &&
                !(choice == "session" && r.params["allow_session"] == JsonPrimitive(false)) && result.optionalBoolean("all")
        }
        "clarify" -> if (r.params["questions"] is JsonArray) {
            val qids = (r.params["questions"] as JsonArray).map { (it as JsonObject).string("qid") }
            result.keys == setOf("answers") && (result["answers"] as? JsonObject)?.all { (id, value) -> id in qids && value.isStringValue() } == true
        } else result.keys == setOf("answer") && result.string("answer") != null
        else -> result.keys == setOf("value") && result.string("value") != null
    }

    companion object {
        val METHODS = setOf("vault.code", "vault.unlock_prompt", "vault.save_login", "sudo", "secret", "approval", "clarify")
        private val CHOICES = setOf("once", "session", "always", "deny")
    }
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement.isStringValue() = this is JsonPrimitive && isString
private fun JsonObject.optionalString(key: String) = this[key] == null || this[key] == JsonNull || string(key) != null
private fun JsonObject.optionalBoolean(key: String) = this[key] == null || this[key] == JsonNull || (this[key] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull != null } == true
private fun JsonObject.validChoices() = this["choices"] == null || this["choices"] == JsonNull || (this["choices"] as? JsonArray)?.all { it.isStringValue() } == true
