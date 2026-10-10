package com.hermes.client.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermes.client.data.network.*
import com.hermes.client.ui.chat.ServerRequestCard
import com.hermes.client.ui.theme.HermesTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServerRequestCardTest {
    @get:Rule val rule = createComposeRule()
    private fun request(id: String) = PendingServerRequest(id, "vault.code", "fixture-session",
        buildJsonObject { put("session_id", "fixture-session"); put("site", "example.invalid") }, 1, false)

    @Test fun explicit_private_submit_sends_once_and_disposal_never_answers() {
        val shown = mutableStateOf(true)
        val req = request("fixture-wire")
        val sent = mutableListOf<JsonObject>()
        rule.setContent { HermesTheme { if (shown.value) ServerRequestCard(req, { actual, result ->
            assertSame(req, actual); sent.add(result); RespondOutcome.ENQUEUED
        }) } }
        rule.onNode(hasSetTextAction()).performTextInput("SYNTHETIC_CODE")
        rule.onNodeWithText("Send privately").performClick()
        rule.onNodeWithText("Send privately").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, sent.size)
            assertEquals(JsonPrimitive("SYNTHETIC_CODE"), sent.single()["value"])
            shown.value = false
        }
        rule.waitForIdle()
        assertEquals(1, sent.size)
    }

    @Test fun replacement_starts_blank_and_hide_does_not_skip() {
        val req = mutableStateOf(request("old"))
        val shown = mutableStateOf(true)
        val sent = mutableListOf<JsonObject>()
        rule.setContent { HermesTheme { if (shown.value) ServerRequestCard(req.value, { _, result -> sent.add(result); RespondOutcome.ENQUEUED }) } }
        rule.onNode(hasSetTextAction()).performTextInput("SYNTHETIC_OLD_CODE")
        rule.runOnIdle { req.value = request("new") }
        rule.onNodeWithText("Send privately").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
        rule.onNodeWithText("Skip").performClick()
        rule.runOnIdle {
            assertEquals(JsonPrimitive(""), sent.single()["value"])
            shown.value = false
        }
        rule.waitForIdle()
        assertEquals(1, sent.size)
    }
}
