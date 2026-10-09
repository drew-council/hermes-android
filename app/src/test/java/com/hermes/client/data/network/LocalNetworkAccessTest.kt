package com.hermes.client.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {
    @Test
    fun olderAndroidDoesNotRequireTheNewPermission() {
        for (sdk in listOf(26, 33, 36)) {
            assertTrue(localNetworkAccessAllowed(sdk, permissionGranted = false))
        }
    }

    @Test
    fun android17AndLaterBlockUntilGranted() {
        for (sdk in listOf(37, 38)) {
            assertFalse(localNetworkAccessAllowed(sdk, permissionGranted = false))
            assertTrue(localNetworkAccessAllowed(sdk, permissionGranted = true))
        }
    }

    @Test
    fun revocationReturnsToTheBlockedState() {
        assertTrue(localNetworkAccessAllowed(37, permissionGranted = true))
        assertFalse(localNetworkAccessAllowed(37, permissionGranted = false))
    }
}
