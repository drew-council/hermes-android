package com.hermes.client.ui.chat

import org.junit.Assert.*
import org.junit.Test

class PrivateInputValidationTest {
    @Test fun verification_code_requires_token_not_empty_or_separators() {
        listOf("", " ", " - \u2003-- ", ".../").forEach { assertFalse(privateInputPresent("vault.code", it)) }
        listOf("123456", "123 456", "AB-CD").forEach { assertTrue(privateInputPresent("vault.code", it)) }
    }
    @Test fun genuine_nonempty_secret_is_not_trimmed_or_restricted_to_code_format() {
        assertFalse(privateInputPresent("secret", ""))
        assertTrue(privateInputPresent("secret", " !@# "))
        assertTrue(privateInputPresent("sudo", " "))
    }
}
