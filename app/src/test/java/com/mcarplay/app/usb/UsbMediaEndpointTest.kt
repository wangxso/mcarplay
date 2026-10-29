package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMediaEndpointTest {
    @Test
    fun explicitUsbScopeIsPreservedForControlAndMediaSockets() {
        val endpoint = UsbMediaEndpoint("usb0", "fe80::1234", 17000).receiver
        assertEquals(17000, endpoint.airPlayPort)
        assertTrue(endpoint.ipAddresses.single().endsWith("%usb0"))
        assertTrue(endpoint.passphrase.isEmpty())
    }

    @Test
    fun rejectsWifiNamesPublicAddressesDnsAndForeignScopes() {
        for ((iface, host) in listOf(
            "wlan0" to "fe80::1",
            "../usb0" to "fe80::1",
            "usb0" to "2001:db8::1",
            "usb0" to "192.0.2.1",
            "usb0" to "receiver.local",
            "usb0" to "fe80::1%wlan0",
            "usb0" to "::1",
        )) {
            assertTrue("$iface $host", runCatching { UsbMediaEndpoint(iface, host, 17000) }.isFailure)
        }
    }

    @Test
    fun rejectsInvalidPorts() {
        for (port in listOf(0, -1, 65536)) {
            assertTrue(runCatching { UsbMediaEndpoint("ncm2", "fe80::1", port) }.isFailure)
        }
    }

    @Test
    fun rootSuppliedNumericScopeAvoidsInterfaceNameResolution() {
        val endpoint = UsbMediaEndpoint("usb0", "fe80::1234", 17000, scopeId = 35).receiver
        assertTrue(endpoint.ipAddresses.single().endsWith("%35"))
        assertTrue(runCatching { UsbMediaEndpoint("usb0", "fe80::1234", 17000, scopeId = -1) }.isFailure)
    }
}
