package com.mcarplay.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIdentityTest {
    @Test
    fun quotedSsidMatchesLiveWifiValue() {
        assertTrue(WifiIdentity.matches("TEST_5G", "\"TEST_5G\""))
        assertTrue(WifiIdentity.matches("\"TEST_5G\"", "\"TEST_5G\""))
    }

    @Test
    fun differentOrRedactedSsidNeverMatches() {
        assertFalse(WifiIdentity.matches("TEST_5G", "\"TEST_2G\""))
        assertFalse(WifiIdentity.matches("TEST_5G", "<unknown ssid>"))
        assertFalse(WifiIdentity.matches("TEST_5G", "0x"))
        assertFalse(WifiIdentity.matches("TEST_5G", "0x0"))
        assertFalse(WifiIdentity.matches("TEST_5G", null))
        assertFalse(WifiIdentity.matches(null, "\"TEST_5G\""))
    }

    @Test
    fun unavailableSsidIsNotReturnedAsAValue() {
        assertNull(WifiIdentity.normalizeSsid(null))
        assertNull(WifiIdentity.normalizeSsid(""))
        assertNull(WifiIdentity.normalizeSsid("<unknown ssid>"))
    }
}
