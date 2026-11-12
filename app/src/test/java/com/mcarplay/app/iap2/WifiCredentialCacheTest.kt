package com.mcarplay.app.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiCredentialCacheTest {
    private val wifi = Iap2SenderSession.WifiConfiguration("Car_AP", "secret", 36, 2)

    @Test
    fun lookupRequiresBothTargetAndSsidToMatch() {
        val cache = WifiCredentialCache()
        assertTrue(cache.remember("target-a", wifi))

        assertEquals(wifi, cache.lookup("target-a", "Car_AP"))
        assertNull("同名 SSID 不能跨目标复用密码", cache.lookup("target-b", "Car_AP"))
        assertNull(cache.lookup("target-a", "Other_AP"))
    }

    @Test
    fun incompleteOrAnonymousEntriesAreRejected() {
        val cache = WifiCredentialCache()

        assertFalse(cache.remember(null, wifi))
        assertFalse(cache.remember("target-a", wifi.copy(ssid = " ", securityType = 2)))
        assertFalse(cache.remember("target-a", wifi.copy(passphrase = "", securityType = 2)))
        assertFalse(cache.remember("target-a", wifi.copy(securityType = 5)))
        assertNull(cache.lookup("target-a", "Car_AP"))
    }

    @Test
    fun explicitOpenCredentialsCanBeCached() {
        val cache = WifiCredentialCache()
        val open = Iap2SenderSession.WifiConfiguration("Open_AP", "", 6, 0)

        assertTrue(cache.remember("target-a", open))
        assertEquals(open, cache.lookup("target-a", "Open_AP"))
    }

    @Test
    fun ssidWhitespaceIsPreservedAndCannotMergeDifferentNetworks() {
        val cache = WifiCredentialCache()
        val spaced = wifi.copy(ssid = " Car_AP ")

        assertTrue(cache.remember("target-a", spaced))
        assertEquals(spaced, cache.lookup("target-a", " Car_AP "))
        assertNull("不能把去掉空格后的同名网络当成同一热点", cache.lookup("target-a", "Car_AP"))
        assertNull(cache.lookup("target-a", "   "))
    }

    @Test
    fun clearRemovesTheOnlyEntry() {
        val cache = WifiCredentialCache()
        cache.remember("target-a", wifi)

        cache.clear()

        assertNull(cache.lookup("target-a", "Car_AP"))
    }

    @Test
    fun complete4301EndpointIsCachedForTheNextSsidOnlyRound() {
        val cache = WifiCredentialCache()
        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "Car_AP",
                passphrase = "secret",
                channel = 36,
                securityType = 2,
                airPlayPort = 7000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = "320.17.7",
                ipAddresses = emptyList(),
                wifiCredentialSource = WifiCredentialSource.ACCESSORY,
            )

        assertTrue(cache.rememberEndpoint("target-a", endpoint))
        assertEquals(wifi, cache.lookup("target-a", "Car_AP"))
    }

    @Test
    fun ssidOnlyEndpointDoesNotBecomeACompleteCacheEntry() {
        val cache = WifiCredentialCache()
        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "Car_AP",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = 0,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = emptyList(),
                wifiCredentialSource = WifiCredentialSource.KNOWN_SSID,
            )

        assertFalse(cache.rememberEndpoint("target-a", endpoint))
        assertNull(cache.lookup("target-a", "Car_AP"))
    }
}
