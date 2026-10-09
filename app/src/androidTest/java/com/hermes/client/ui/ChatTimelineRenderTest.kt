package com.hermes.client.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermes.client.domain.*
import com.hermes.client.ui.chat.*
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatTimelineRenderTest {
    @get:Rule val rule = createComposeRule()
    private fun entry(key: String, body: TimelineBody) = TimelineEntry(key, SourceRef(null, "fixture"), body, OrderEvidence.LIVE_ARRIVAL)
    private fun text(key: String, text: String) = entry(key, TimelineBody.Message(Role.ASSISTANT, DisplayContent(listOf(ContentPart.Text(text)))))

    @Test fun interleaved_typed_cards_keep_order_and_hide_raw_output() {
        val timeline = TimelineSnapshot(entries = listOf(
            text("a", "Before tool"), entry("r", TimelineBody.Reasoning("fixture reasoning")),
            entry("t", TimelineBody.ToolInvocation("fixture-call", "Fixture tool", null)),
            text("b", "After tool"), entry("result", TimelineBody.ToolResult("fixture-call", "Fixture tool", DisplayContent(listOf(ContentPart.Text("SYNTHETIC_RAW_OUTPUT"))))),
        ))
        val technical = mutableStateOf(false)
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(com.hermes.client.ui.theme.LocalToolCallTechnical provides technical.value) {
            HermesTheme { ChatMessageList(ChatUiState(timeline = timeline), "fixture", following = false) }
        } }
        rule.onNodeWithText("SYNTHETIC_RAW_OUTPUT").assertDoesNotExist()
        val before = rule.onNodeWithText("Before tool").fetchSemanticsNode().boundsInRoot.top
        val thinking = rule.onNodeWithText("Thinking").fetchSemanticsNode().boundsInRoot.top
        val tool = rule.onNodeWithText("Fixture tool · unknown").fetchSemanticsNode().boundsInRoot.top
        val after = rule.onNodeWithText("After tool").fetchSemanticsNode().boundsInRoot.top
        assertTrue(before < thinking && thinking < tool && tool < after)
        rule.runOnIdle { technical.value = true }
        rule.onNodeWithText("SYNTHETIC_RAW_OUTPUT").assertIsDisplayed()
    }

    @Test fun streamed_tall_tail_reaches_absolute_bottom_and_reader_pause_survives_growth() {
        val list = LazyListState()
        val following = mutableStateOf(true)
        val timeline = mutableStateOf(TimelineSnapshot(entries = listOf(text("tail", "Initial text"))))
        rule.setContent {
            HermesTheme { ChatMessageList(ChatUiState(timeline = timeline.value), "fixture", listState = list,
                following = following.value, onFollowingChange = { following.value = it }) }
        }
        rule.runOnIdle { timeline.value = timeline.value.copy(entries = listOf(text("tail", (1..120).joinToString("\n\n") { "Fixture paragraph $it" })), revision = 1) }
        rule.waitUntil(5000) { !list.canScrollForward && list.firstVisibleItemScrollOffset > 0 }
        rule.onNodeWithText("Fixture paragraph 1", substring = true).performTouchInput { swipeDown() }
        rule.waitUntil(5000) { !following.value }
        rule.waitUntil(5000) { !list.isScrollInProgress }
        rule.waitForIdle()
        val readerOffset = list.firstVisibleItemScrollOffset
        rule.runOnIdle { timeline.value = timeline.value.copy(entries = timeline.value.entries + text("new", "New fixture tail"), revision = 2) }
        rule.waitForIdle()
        assertEquals(readerOffset, list.firstVisibleItemScrollOffset)
        rule.onNodeWithText("Jump to latest").performClick()
        rule.waitUntil(5000) { following.value && !list.canScrollForward }
    }

    @Test fun restored_reasoning_content_and_distinct_reasoning_are_disclosed_in_ambiguous_bundle() {
        val page = kotlinx.serialization.json.Json.decodeFromString(
            com.hermes.client.data.network.MessagesDto.serializer(),
            """{"messages":[{"id":1,"role":"assistant","content":"Restored fixture reply",
                "reasoning":"Primary fixture thinking","reasoning_content":"Sidecar fixture thinking"}]}""")
        val timeline = projectRestHistory(page, "fixture", null)
        rule.setContent { HermesTheme { ChatMessageList(ChatUiState(timeline = timeline), "fixture", following = false) } }
        rule.onNodeWithText("Stored row details · relative timing unavailable").assertIsDisplayed()
        rule.onNodeWithText("Thinking · reasoning").performClick()
        rule.onNodeWithText("Thinking · reasoning_content").performClick()
        rule.onNodeWithText("Primary fixture thinking").assertIsDisplayed()
        rule.onNodeWithText("Sidecar fixture thinking").assertIsDisplayed()
        rule.onNodeWithText("Restored fixture reply").assertIsDisplayed()
    }

    @Test fun structured_reasoning_content_discloses_safe_placeholder_not_raw_sidecar() {
        val page = kotlinx.serialization.json.Json.decodeFromString(
            com.hermes.client.data.network.MessagesDto.serializer(),
            """{"messages":[{"id":1,"role":"assistant","content":"Structured fixture reply",
                "reasoning_content":{"unsupported":"SYNTHETIC_RAW_SIDECAR"}}]}""")
        val timeline = projectRestHistory(page, "fixture", null)
        rule.setContent { HermesTheme { ChatMessageList(ChatUiState(timeline = timeline), "fixture", following = false) } }
        rule.onNodeWithText("Thinking · reasoning_content").performClick()
        rule.onNodeWithText("Stored reasoning details unavailable (unsupported format)").assertIsDisplayed()
        rule.onNodeWithText("SYNTHETIC_RAW_SIDECAR", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Structured fixture reply").assertIsDisplayed()
        rule.onNodeWithText("Stored row details · relative timing unavailable").assertIsDisplayed()
    }

    @Test fun clarification_legacy_marker_is_non_actionable() {
        val timeline = TimelineSnapshot(entries = listOf(entry("p", TimelineBody.Interaction("clarify", "legacy", "Fixture question"))))
        rule.setContent { HermesTheme { ChatMessageList(ChatUiState(timeline = timeline), "fixture") } }
        rule.onNodeWithText("Legacy request unsupported · no compatible response transport").assertIsDisplayed()
        rule.onNodeWithText("Send answer").assertDoesNotExist()
    }
}
