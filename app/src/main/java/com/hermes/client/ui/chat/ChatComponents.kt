package com.hermes.client.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.hermes.client.ui.theme.LocalProfileAccent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.hermes.client.domain.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.scrollBy as semanticScrollBy
import androidx.compose.ui.semantics.scrollToIndex
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun ChatMessageList(
    state: ChatUiState,
    sessionId: String,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    isGenerating: Boolean = false,
    onEditResend: (String) -> Unit = {},
    onRegenerate: () -> Unit = {},
    isSpeaking: Boolean = false,
    onReadAloud: (String) -> Unit = {},
    onStopReading: () -> Unit = {},
    highlightIndex: Int? = null,
    following: Boolean = true,
    onFollowingChange: (Boolean) -> Unit = {},
    localSendRevision: Int = 0,
    pendingRequests: List<com.hermes.client.data.network.PendingServerRequest> = emptyList(),
    requestAnchors: Map<com.hermes.client.data.network.PendingServerRequest, String> = emptyMap(),
    onLegacyApproval: suspend (ApprovalRequest, ApprovalChoice) -> Boolean = { _, _ -> false },
    onRequestRespond: (com.hermes.client.data.network.PendingServerRequest, kotlinx.serialization.json.JsonObject) -> com.hermes.client.data.network.RespondOutcome = { _, _ -> com.hermes.client.data.network.RespondOutcome.STALE },
) {
    val entries = state.timeline.entries
    val rendered = chatRenderItems(state.timeline, pendingRequests, requestAnchors)
    val latestAssistantIndex = entries.indexOfLast {
        (it.body as? TimelineBody.Message)?.role == Role.ASSISTANT || it.body is TimelineBody.PersistedRowDetails
    }
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
    val currentFollowing by androidx.compose.runtime.rememberUpdatedState(following)
    val currentChange by androidx.compose.runtime.rememberUpdatedState(onFollowingChange)
    val readerScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) currentChange(false)
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !listState.canScrollForward) currentChange(true)
                return Offset.Zero
            }
        }
    }
    // Growth does not change reader intent. Layout observation covers disclosures, late
    // Markdown measurement and IME resizing; ordinary scroll priority yields to user input.
    LaunchedEffect(sessionId, following, state.timeline.revision, localSendRevision, state.messages.size, pendingRequests.size) {
        if (!following) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            Triple(layout.viewportEndOffset, layout.totalItemsCount,
                layout.visibleItemsInfo.lastOrNull()?.let { it.key to (it.offset + it.size) })
        }.collectLatest {
            var stable = 0
            repeat(90) {
                if (!currentFollowing) return@collectLatest
                if (listState.canScrollForward) { listState.scrollBy(100_000f); stable = 0 } else stable++
                if (stable >= 3) return@collectLatest
                withFrameNanos {}
            }
        }
    }

    if (entries.isEmpty() && state.messages.isEmpty() && pendingRequests.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Send a message to start the conversation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        if (!following) TextButton(onClick = { onFollowingChange(true) }) { Text("Jump to latest") }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).nestedScroll(readerScroll).semantics {
                semanticScrollBy { _, y ->
                    currentChange(false)
                    scrollScope.launch {
                        listState.scrollBy(y)
                        if (!listState.canScrollForward) currentChange(true)
                    }
                    true
                }
                scrollToIndex { index ->
                    currentChange(false)
                    scrollScope.launch { listState.scrollToItem(index) }
                    true
                }
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(rendered, key = { _, item -> when (item) {
                is ChatRenderItem.Entry -> item.entry.key
                is ChatRenderItem.Request -> "request:${item.request.generation}:${item.request.id}"
            } }) { renderIndex, item ->
                if (item is ChatRenderItem.Request) {
                    ServerRequestCard(item.request, onRequestRespond, item.anchored)
                } else if (item is ChatRenderItem.Entry) {
                val entry = item.entry
                val index = item.timelineIndex
                when (val body = entry.body) {
                    is TimelineBody.Message -> MessageBubble(
                        ChatMessage(entry.key, body.role, body.content.text, isStreaming = body.streaming),
                        index == latestAssistantIndex && !isGenerating,
                        onEditResend, onRegenerate, isSpeaking, onReadAloud, onStopReading, renderIndex == highlightIndex,
                    )
                    is TimelineBody.Reasoning -> ThinkingCard(body.text)
                    is TimelineBody.ToolInvocation -> TimelineToolCard(body.name, body.state.name.lowercase(), body.args?.toString().orEmpty())
                    is TimelineBody.ToolResult -> TimelineToolCard(body.name ?: "Tool result", "result", body.result.text)
                    is TimelineBody.Interaction -> {
                        val modern = hasModernRequest(body, pendingRequests)
                        val legacy = body.kind == "approval" && legacyApprovalAt(state.timeline, index, state.pendingApproval)
                        val status = if (body.state == InteractionState.ANSWERED) "response sent" else
                            if (modern || legacy) "pending" else "recorded · current status unavailable"
                        Text("${body.kind}: $status · ${body.text}")
                        if (!modern && body.state == InteractionState.PENDING) {
                            if (body.kind == "approval" && legacyApprovalAt(state.timeline, index, state.pendingApproval))
                                state.pendingApproval?.let { LegacyApprovalCard(it, state.timeline.ready, onLegacyApproval) }
                            else Text("Legacy request unsupported · no compatible response transport")
                        }
                    }
                    is TimelineBody.Notice -> Text(body.text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is TimelineBody.PersistedRowDetails -> Column {
                        Text("Stored row details · relative timing unavailable", style = MaterialTheme.typography.labelSmall)
                        persistedReasoningDisclosures(body).forEach { disclosure ->
                            androidx.compose.runtime.key(disclosure.field) {
                                ThinkingCard(disclosure.text, label = "Thinking · ${disclosure.field}")
                            }
                        }
                        MessageBubble(ChatMessage(entry.key, Role.ASSISTANT, body.content.text),
                            index == latestAssistantIndex && !isGenerating, onEditResend, onRegenerate,
                            isSpeaking, onReadAloud, onStopReading, renderIndex == highlightIndex)
                    }
                }
                }
            }
            // Legacy-only callers do not establish timeline order.
            if (entries.isEmpty()) itemsIndexed(state.messages, key = { index, msg -> "$index:${msg.id}" }) { index, msg ->
                MessageBubble(msg, false, onEditResend, onRegenerate, isSpeaking, onReadAloud, onStopReading, index == highlightIndex)
            }
        }
    }
}

// Hybrid layout: the user's own turns stay as compact right-aligned bubbles, while the agent's
// turns render full-width and document-style (like the desktop / modern AI apps) so long answers,
// code, and tool traces have room to breathe and read as a transcript rather than an SMS thread.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    canRegenerate: Boolean,
    onEditResend: (String) -> Unit,
    onRegenerate: () -> Unit,
    isSpeaking: Boolean,
    onReadAloud: (String) -> Unit,
    onStopReading: () -> Unit,
    highlighted: Boolean = false,
) {
    when (msg.role) {
        Role.USER -> UserBubble(msg, onEditResend, highlighted = highlighted)
        else -> AssistantTurn(msg, canRegenerate, onRegenerate, isSpeaking, onReadAloud, onStopReading, highlighted = highlighted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: ChatMessage, onEditResend: (String) -> Unit, highlighted: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (msg.isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer
    val accent = LocalProfileAccent.current.accent
    val userShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    // Asymmetric corners (a small "tail" corner) mark this as the sender's bubble.
                    .clip(userShape)
                    .background(bg)
                    .then(if (highlighted) Modifier.background(accent.copy(alpha = 0.18f)).border(1.5.dp, accent, userShape) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            ) {
                if (msg.text.isNotBlank()) Text(msg.text, style = MaterialTheme.typography.bodyLarge)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = { copyToClipboard(msg.text, clipboard, context); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text("Edit & resend") },
                    onClick = { onEditResend(msg.text); menuOpen = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantTurn(
    msg: ChatMessage,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    isSpeaking: Boolean,
    onReadAloud: (String) -> Unit,
    onStopReading: () -> Unit,
    highlighted: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val speakable = remember(msg.text) { speechText(msg.text).isNotBlank() }
    val accent = LocalProfileAccent.current.accent
    val hlShape = RoundedCornerShape(12.dp)
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                // No conditional padding here: background/border draw within existing bounds, so
                // toggling the highlight causes no layout shift (a conditional .padding would).
                .then(if (highlighted) Modifier.clip(hlShape).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, hlShape) else Modifier)
                .padding(vertical = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
        ) {
            if (msg.thinking.isNotBlank()) ThinkingCard(msg.thinking)
            msg.tools.forEach { ToolCard(it) }
            if (msg.text.isNotBlank()) {
                if (msg.isError) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            msg.text,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    val mdComponents = remember { chatMarkdownComponents() }
                    Markdown(
                        content = msg.text,
                        colors = markdownColor(),
                        typography = markdownTypography(),
                        components = mdComponents,
                    )
                }
            }
            if (msg.isStreaming && msg.text.isBlank() && msg.tools.isEmpty()) {
                TypingIndicator()
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = { copyToClipboard(msg.text, clipboard, context); menuOpen = false },
            )
            if (canRegenerate) {
                DropdownMenuItem(
                    text = { Text("Regenerate") },
                    onClick = { onRegenerate(); menuOpen = false },
                )
            }
            if (speakable && !msg.isError) {
                DropdownMenuItem(
                    text = { Text(if (isSpeaking) "Stop" else "Read aloud") },
                    onClick = {
                        if (isSpeaking) onStopReading() else onReadAloud(msg.text)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

private fun copyToClipboard(
    text: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
) {
    if (text.isNotBlank()) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Markdown component set that renders code blocks/fences with a copy button. The library's fence
 * renderer already extracts the clean code text and hands it to this block, so we just overlay a
 * copy affordance on the default code rendering.
 */
private fun chatMarkdownComponents(): MarkdownComponents =
    markdownComponents(
        codeFence = { m ->
            MarkdownCodeFence(m.content, m.node, style = m.typography.code) { code, language, style ->
                CodeWithCopy(code, language, style)
            }
        },
        codeBlock = { m ->
            MarkdownCodeBlock(m.content, m.node, style = m.typography.code) { code, language, style ->
                CodeWithCopy(code, language, style)
            }
        },
    )

@Composable
private fun CodeWithCopy(code: String, @Suppress("UNUSED_PARAMETER") language: String?, style: TextStyle) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = code,
            style = style,
            modifier = Modifier
                // Reserve the copy-button area OUTSIDE the scroll so long code never slides under it.
                .padding(end = 44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(code))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = "Copy code",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Three pulsing dots while the agent composes its first token — replaces the literal "…". */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun ThinkingCard(text: String, label: String = "Thinking") {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = !expanded },
        label = { Text(if (expanded) { if (label == "Thinking") "Hide thinking" else "Hide $label" } else label) },
    )
    if (expanded) Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TimelineToolCard(name: String, status: String, output: String) {
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("$name · $status", style = MaterialTheme.typography.labelMedium)
            if (com.hermes.client.ui.theme.LocalToolCallTechnical.current && output.isNotBlank())
                Text(output, style = MaterialTheme.typography.bodySmall, maxLines = 6)
        }
    }
}

@Composable
private fun ToolCard(tool: ToolCall) {
    // Product mode hides raw tool payloads; Technical shows them.
    val technical = com.hermes.client.ui.theme.LocalToolCallTechnical.current
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (tool.status == ToolStatus.RUNNING) Icons.Rounded.PlayArrow else Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalProfileAccent.current.accent,
                )
                Text(
                    text = if (tool.status == ToolStatus.RUNNING) "${tool.name}…" else tool.name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (technical && tool.output.isNotBlank()) {
                Text(
                    tool.output,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

