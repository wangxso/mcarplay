package com.mcarplay.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetNetworkPrefsTest {
    @Test
    fun missingOrUnknownValueUsesExistingCarBehaviour() {
        assertEquals(InternetNetwork.CAR, InternetNetworkPrefs.parse(null))
        assertEquals(InternetNetwork.CAR, InternetNetworkPrefs.parse(""))
        assertEquals(InternetNetwork.CAR, InternetNetworkPrefs.parse("unexpected"))
    }

    @Test
    fun parsesStoredValues() {
        assertEquals(InternetNetwork.PHONE, InternetNetworkPrefs.parse("phone"))
        assertEquals(InternetNetwork.CAR, InternetNetworkPrefs.parse("car"))
    }

    @Test
    fun systemWifiRequiresCarModeAndShizuku() {
        assertTrue(InternetNetworkPolicy.useSystemWifi(InternetNetwork.CAR, shizukuReady = true))
        assertFalse(InternetNetworkPolicy.useSystemWifi(InternetNetwork.CAR, shizukuReady = false))
        assertFalse(InternetNetworkPolicy.useSystemWifi(InternetNetwork.PHONE, shizukuReady = true))
    }

    @Test
    fun phoneModeDisablesSystemWifiAndFallbacks() {
        assertFalse(InternetNetworkPolicy.allowExistingSystemWifi(InternetNetwork.PHONE))
        assertFalse(InternetNetworkPolicy.allowSystemFallback(InternetNetwork.PHONE))
        assertTrue(InternetNetworkPolicy.allowExistingSystemWifi(InternetNetwork.CAR))
        assertTrue(InternetNetworkPolicy.allowSystemFallback(InternetNetwork.CAR))
    }
}
