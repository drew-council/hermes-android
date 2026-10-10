package com.hermes.client.ui.chat

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hermes.client.data.network.PendingServerRequest
import com.hermes.client.data.network.RespondOutcome
import com.hermes.client.ui.components.SlideToConfirm
import kotlinx.serialization.json.*

internal fun JsonObject.displayString(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun approvalChoices(params: JsonObject): List<String> =
    (params["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter {
        it == "deny" || it == "once" || (it == "session" && params["allow_session"] != JsonPrimitive(false) && params["smart_denied"] != JsonPrimitive(true)) ||
            (it == "always" && params["allow_permanent"] != JsonPrimitive(false) && params["smart_denied"] != JsonPrimitive(true))
    }.orEmpty()

/** Verification codes need a token, not just separators; genuine secrets are sent verbatim. */
internal fun privateInputPresent(method: String, value: CharSequence): Boolean =
    if (method == "vault.code") value.any { it.isLetterOrDigit() } else value.isNotEmpty()

/** Transient drafts are scoped to the exact transport handle, not its reusable wire label.
 * Secure fields disable copy/cut and keyboard learning; nothing is saved or fed to chat.
 */
@Composable
fun ServerRequestCard(request: PendingServerRequest,
                      respond: (PendingServerRequest, JsonObject) -> RespondOutcome,
                      anchored: Boolean = false) {
    key(request) {
        val secret = remember { TextFieldState() }
        val identifier = remember { TextFieldState() }
        var answer by remember { mutableStateOf("") }
        var answers by remember { mutableStateOf(emptyMap<String, String>()) }
        var status by remember { mutableStateOf<String?>(null) }
        var settled by remember { mutableStateOf(false) }
        fun clear() {
            secret.edit { replace(0, length, "") }
            identifier.edit { replace(0, length, "") }
            answer = ""; answers = emptyMap()
        }
        DisposableEffect(request) { onDispose { clear() } }
        fun send(result: JsonObject) {
            if (settled) return
            settled = true
            try {
                status = when (respond(request, result)) {
                    RespondOutcome.ENQUEUED -> "Response sent (not acknowledged)"
                    RespondOutcome.STALE -> "Request expired or replaced"
                    RespondOutcome.DISCONNECTED -> "Disconnected · answer discarded"
                }
            } catch (_: IllegalArgumentException) { status = "Response rejected" }
            finally { clear() }
        }
        val p = request.params
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (request.method) {
                    "vault.code" -> "Verification code"
                    "vault.unlock_prompt" -> "Unlock ${p.displayString("display_name")}"
                    "vault.save_login" -> "Save login"
                    "sudo" -> "Password for elevated command"
                    "secret" -> "Secret input"
                    "approval" -> "Approval needed"
                    else -> "Clarification"
                }, style = MaterialTheme.typography.titleSmall)
                Text(if (anchored) "Position: first local UI observation" else "Restored/current request · original position unavailable",
                    style = MaterialTheme.typography.labelSmall)
                when (request.method) {
                    "approval" -> {
                        Text(p.displayString("command"))
                        Text(p.displayString("description"))
                        if (p["smart_denied"] == JsonPrimitive(true)) Text("Owner override · this operation only")
                        approvalChoices(p).sortedBy { if (it == "deny") 0 else 1 }.forEach { choice ->
                            val action = { send(buildJsonObject { put("choice", choice); put("all", false) }) }
                            if (choice != "deny" && p["allow_permanent"] == JsonPrimitive(false)) {
                                if (!settled) SlideToConfirm(label = "Slide to allow $choice", accent = MaterialTheme.colorScheme.primary,
                                    onConfirm = action)
                            } else if (choice == "deny") Button(enabled = !settled, onClick = action,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Deny") }
                            else TextButton(enabled = !settled, onClick = action) { Text("Allow $choice") }
                        }
                    }
                    "clarify" -> {
                        val questions = p["questions"] as? JsonArray
                        if (questions == null) {
                            Text(p.displayString("question"))
                            ClarificationInput(p, answer, !settled) { answer = it }
                        } else questions.forEach { item ->
                            val q = item as JsonObject
                            val qid = q.displayString("qid")
                            val locked = (p["answers"] as? JsonObject)?.displayString(qid)
                            Text(q.displayString("question"))
                            ClarificationInput(q, answers[qid] ?: locked.orEmpty(), !settled) { answers = answers + (qid to it) }
                        }
                        TextButton(enabled = !settled, onClick = {
                            send(buildJsonObject {
                                if (questions == null) put("answer", answer) else put("answers", buildJsonObject {
                                    (p["answers"] as? JsonObject)?.forEach { (qid, value) -> put(qid, value) }
                                    answers.forEach { (qid, value) -> put(qid, value) }
                                })
                            })
                        }) { Text("Send answer") }
                        TextButton(enabled = !settled, onClick = {
                            send(buildJsonObject { if (questions == null) put("answer", "") else put("answers", buildJsonObject {}) })
                        }) { Text("Cancel clarification") }
                    }
                    else -> {
                        Text(when (request.method) {
                            "vault.code" -> p.displayString("site") + "\n" + p.displayString("hint")
                            "vault.save_login" -> p.displayString("origin")
                            "sudo" -> p.displayString("command")
                            "secret" -> p.displayString("prompt")
                            else -> "Enter only the requested vault unlock secret."
                        })
                        Text("Private input · sent directly, never added to chat", style = MaterialTheme.typography.labelSmall)
                        if (request.method == "vault.save_login") {
                            PrivateInput(identifier, !settled, "Identifier")
                        }
                        val label = if (request.method == "vault.code") "Enter verification code" else "Private secret"
                        PrivateInput(secret, !settled, label)
                        TextButton(enabled = !settled && privateInputPresent(request.method, secret.text) &&
                            (request.method != "vault.save_login" || identifier.text.isNotEmpty()), onClick = {
                            // Recheck the transient state in the callback; an empty Send is never Skip.
                            if (!privateInputPresent(request.method, secret.text) ||
                                (request.method == "vault.save_login" && identifier.text.isEmpty())) {
                                status = "Enter the requested input before sending"
                                return@TextButton
                            }
                            val value = if (request.method == "vault.save_login") buildJsonObject {
                                put("identifier", identifier.text.toString()); put("password", secret.text.toString())
                            }.toString() else secret.text.toString()
                            send(buildJsonObject { put("value", value) })
                        }) { Text("Send privately") }
                        TextButton(enabled = !settled, onClick = { send(buildJsonObject { put("value", "") }) }) { Text("Skip") }
                    }
                }
                status?.let { Text(it) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateInput(state: TextFieldState, enabled: Boolean, label: String) {
    OutlinedSecureTextField(state = state, enabled = enabled,
        label = { Text(label) }, textObfuscationMode = TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = label })
}

@Composable
private fun ClarificationInput(params: JsonObject, answer: String, enabled: Boolean, onChange: (String) -> Unit) {
    val choices = (params["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    choices.forEach { choice ->
        TextButton(enabled = enabled, onClick = {
            if (params["multi_select"] == JsonPrimitive(true)) {
                val selected = answer.split(", ").filter { it.isNotBlank() }.toMutableList()
                if (!selected.remove(choice)) selected.add(choice)
                onChange(selected.joinToString(", "))
            } else onChange(choice)
        }) { Text(choice) }
    }
    OutlinedTextField(value = answer, onValueChange = onChange, enabled = enabled, label = { Text("Answer") })
}
