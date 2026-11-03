package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverPairingStoreTest {
    private val endpoint =
        Iap2SenderSession.CarPlayEndpoint(
            "test-network",
            "",
            0,
            0,
            7000,
            "AA:BB:CC:DD:EE:FF",
            null,
            null,
            listOf("127.0.0.1"),
        )

    @Test
    fun pairingKeySurvivesAddressPortAndNetworkChanges() {
        val changed =
            endpoint.copy(
                ssid = "other-network",
                airPlayPort = 7100,
                ipAddresses = listOf("127.0.0.2"),
                deviceIdentifier = "aa:bb:cc:dd:ee:ff",
            )
        assertEquals(ReceiverPairingStore.key(endpoint, "controller"), ReceiverPairingStore.key(changed, "controller"))
    }

    @Test
    fun pairingKeyIsScopedToControllerAndReceiverIdentity() {
        val key = ReceiverPairingStore.key(endpoint, "controller")
        assertNotNull(key)
        assertNotEquals(key, ReceiverPairingStore.key(endpoint, "other-controller"))
        assertNotEquals(key, ReceiverPairingStore.key(endpoint.copy(deviceIdentifier = "other-receiver"), "controller"))
    }

    @Test
    fun publicKeyCanIdentifyReceiverWithoutDeviceId() {
        val identified = endpoint.copy(deviceIdentifier = null, publicKey = "AABB")
        assertNotNull(ReceiverPairingStore.key(identified, "controller"))
        assertEquals(
            ReceiverPairingStore.key(identified, "controller"),
            ReceiverPairingStore.key(identified.copy(publicKey = "aabb"), "controller"),
        )
    }

    @Test
    fun sharedNetworkOrIpIsNotReceiverIdentity() {
        assertNull(ReceiverPairingStore.key(endpoint.copy(deviceIdentifier = null, publicKey = null), "controller"))
    }
}
