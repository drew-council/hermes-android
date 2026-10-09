package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.hermes.client.ui.components.SlideToConfirm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun legacyApprovalChoices(request: ApprovalRequest): List<ApprovalChoice> {
    val choices = request.choices.ifEmpty { listOf("once", "deny") +
        (if (request.allowSession) listOf("session") else emptyList()) +
        (if (request.allowPermanent) listOf("always") else emptyList()) }
    return ApprovalChoice.entries.filter { choice ->
        choice.name.lowercase() in choices &&
            (choice != ApprovalChoice.SESSION || request.allowSession) &&
            (choice != ApprovalChoice.ALWAYS || request.allowPermanent) &&
            (!request.smartDenied || choice == ApprovalChoice.ONCE || choice == ApprovalChoice.DENY)
    }
}

/** Legacy approvals have one current slot. Hide/navigation never settles it. */
@Composable
fun LegacyApprovalCard(request: ApprovalRequest, ready: Boolean,
                       respond: suspend (ApprovalRequest, ApprovalChoice) -> Boolean) {
    key(request) {
        val scope = rememberCoroutineScope()
        val actionable = ready && !request.requestId.isNullOrBlank()
        var submitted by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        fun send(choice: ApprovalChoice) {
            if (submitted || !actionable) return
            submitted = true
            scope.launch {
                try {
                    status = if (respond(request, choice)) "Approval sent" else "Could not send approval · request remains pending"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "Could not send approval · request remains pending" }
                // Failure stays disabled: no blind retry when delivery is unconfirmed.
            }
        }
        Column {
            Text(request.command)
            Text(request.description)
            legacyApprovalChoices(request).sortedBy { if (it == ApprovalChoice.DENY) 0 else 1 }.forEach { choice ->
                if (choice != ApprovalChoice.DENY && !request.allowPermanent) {
                    if (!submitted && actionable) SlideToConfirm(label = "Slide to allow ${choice.name.lowercase()}",
                        accent = MaterialTheme.colorScheme.primary, onConfirm = { send(choice) })
                } else if (choice == ApprovalChoice.DENY) Button(enabled = actionable && !submitted,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { send(choice) }) { Text("Deny") }
                else TextButton(enabled = actionable && !submitted, onClick = { send(choice) }) { Text("Allow ${choice.name.lowercase()}") }
            }
            if (request.requestId.isNullOrBlank()) Text("Legacy request identity unavailable · actions disabled")
            else if (!ready) Text("Disconnected · actions unavailable")
            status?.let { Text(it) }
        }
    }
}
