package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WiredCarPlayStartFileTest {
    @Test
    fun roundTripsTheReceiverEndpoint() {
        val start =
            WiredIap2Session.CarPlayStart(
                addresses = listOf("fe80::1234:5678:9abc:def0"),
                port = 7000,
                deviceIdentifier = "42:b5:14:45:f9:bf",
                publicKeyLength = 64,
                sourceVersion = "320.17",
                publicKey = "ab".repeat(32),
            )
        val decoded = WiredCarPlayStartFile.decode(WiredCarPlayStartFile.encode(start))!!
        assertEquals(start.addresses, decoded.addresses)
        assertEquals(7000, decoded.port)
        assertEquals("42:b5:14:45:f9:bf", decoded.deviceIdentifier)
        assertEquals(start.publicKey, decoded.publicKey)
        assertEquals("320.17", decoded.sourceVersion)
    }

    @Test
    fun stripsScopesAndRejectsUnusableFiles() {
        val scoped = WiredCarPlayStartFile.decode("port=7000\naddress=fe80::1%tun0\naddress=fe80::1\n")!!
        assertEquals(listOf("fe80::1"), scoped.addresses)
        assertNull(WiredCarPlayStartFile.decode("port=0\naddress=fe80::1\n"))
        assertNull(WiredCarPlayStartFile.decode("port=7000\n"))
        assertNull(WiredCarPlayStartFile.decode("port=7000\naddress=fe80::1;reboot\n"))
    }
}
