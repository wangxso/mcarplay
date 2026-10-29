package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbNcmDiscoveryTest {
    @Test
    fun interfaceAndIfindexParsingRejectDiagnosticNoise() {
        assertEquals("usb0", UsbNcmDiscovery.parseInterfaceName("NCM: ready iface=usb0\nusb1\n"))
        assertEquals(42, UsbNcmDiscovery.parseIfindex("warning\n42\n"))
        assertNull(UsbNcmDiscovery.parseInterfaceName("wlan0\n"))
        assertNull(UsbNcmDiscovery.parseIfindex("0\nmissing\n"))
    }

    @Test
    fun procIpv6ParsingSelectsTheExpectedUsbInterface() {
        val output =
            """
            fe800000000000000000000000000001 00000025 40 00 80 usb0
            20010db8000000000000000000000001 0000000b 40 00 80 wlan0
            """.trimIndent()
        assertEquals("fe80:0000:0000:0000:0000:0000:0000:0001", UsbNcmDiscovery.parseLocalLinkLocal(output, "usb0"))
        assertNull(UsbNcmDiscovery.parseLocalLinkLocal(output, "ncm0"))
        assertNull(UsbNcmDiscovery.parseLocalLinkLocal("fe80::1 usb0", "usb0"))
    }

    @Test
    fun generatedHostAddressesAreLocallyAdministeredUnicastMacs() {
        val mac = UsbNcmDiscovery.formatLocalMac(byteArrayOf(0xff.toByte(), 1, 2, 3, 4, 5))
        assertEquals("fe:01:02:03:04:05", mac)
        val first = mac.substringBefore(':').toInt(16)
        assertEquals(0, first and 1)
        assertEquals(1, (first shr 1) and 1)
    }

    @Test
    fun shellQuotingHandlesSingleQuotesWithoutCreatingAnEmptyArgument() {
        assertEquals("'plain'", UsbNcmDiscovery.shellQuote("plain"))
        assertEquals("'a'\\''b'", UsbNcmDiscovery.shellQuote("a'b"))
        assertTrue(UsbNcmDiscovery.isLinkLocalIpv6("fe80::1"))
        assertTrue(!UsbNcmDiscovery.isLinkLocalIpv6("2001:db8::1"))
    }
}
