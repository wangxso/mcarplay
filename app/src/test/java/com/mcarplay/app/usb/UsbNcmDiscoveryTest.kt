package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

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
            fe800000000000000000000000000003 00000025 40 00 c4 usb0
            fe800000000000000000000000000004 00000025 40 00 08 usb0
            fe800000000000000000000000000002 00000025 40 00 40 usb0
            20010db8000000000000000000000001 0000000b 40 00 80 wlan0
            """.trimIndent()
        assertEquals("fe80:0000:0000:0000:0000:0000:0000:0001", UsbNcmDiscovery.parseLocalLinkLocal(output, "usb0"))
        assertNull(UsbNcmDiscovery.parseLocalLinkLocal(output, "ncm0"))
        assertNull(UsbNcmDiscovery.parseLocalLinkLocal("fe80::1 usb0", "usb0"))
    }

    @Test
    fun procIpv6ParsingAcceptsDadCompleteAddressWithoutPermanentFlag() {
        val output = "fe800000000000000000000000000001 00000025 40 00 02 usb0"
        assertEquals(
            "fe80:0000:0000:0000:0000:0000:0000:0001",
            UsbNcmDiscovery.parseLocalLinkLocal(output, "usb0"),
        )
    }

    @Test
    fun localAndNeighborParsingSupportsIpv4Fallback() {
        val local =
            """
            usb0
            42
            fe800000000000000000000000000001 0000002a 40 00 82 usb0
            IPV4=169.254.77.9
            """.trimIndent()
        val addresses = UsbNcmDiscovery.parseLocalAddresses(local, "usb0")
        assertEquals("169.254.77.9", addresses.ipv4)
        assertEquals(
            listOf("fe80:0000:0000:0000:0000:0000:0000:0001", "169.254.77.9"),
            addresses.mdnsBindAddresses,
        )

        val neighbors =
            """
            fe80::1234 dev usb0 lladdr 02:aa:bb:cc:dd:ee STALE
            169.254.1.2 dev usb0 lladdr 02:aa:bb:cc:dd:ee REACHABLE
            192.0.2.9 dev usb0 lladdr 02:aa:bb:cc:dd:ee FAILED
            169.254.9.9 dev wlan0 lladdr 02:11:22:33:44:55 REACHABLE
            """.trimIndent()
        assertEquals(
            listOf(InetAddress.getByName("fe80::1234").hostAddress, "169.254.1.2"),
            UsbNcmDiscovery.parseNeighborAddresses(neighbors, "usb0"),
        )
    }

    @Test
    fun mdnsCandidatesRejectWifiPrivateAddressesEvenForTheExpectedPort() {
        val scope = 42
        val expectedIpv6 =
            checkNotNull(
                UsbNcmDiscovery.endpointHostFromMdns(
                    InetAddress.getByName("fe80::1234"),
                    scope,
                ),
            )
        assertEquals(
            "169.254.9.8",
            UsbNcmDiscovery.endpointHostFromMdns(InetAddress.getByName("169.254.9.8"), scope),
        )
        assertEquals(
            InetAddress.getByName("fe80::1234").hostAddress,
            expectedIpv6,
        )
        assertNull(UsbNcmDiscovery.endpointHostFromMdns(InetAddress.getByName("10.0.0.199"), scope))
        assertNull(UsbNcmDiscovery.endpointHostFromMdns(InetAddress.getByName("fdcc::199"), scope))
    }

    @Test
    fun manualAddressesAllowUsbLocalRangesAndRejectPublicOrDnsValues() {
        assertEquals("169.254.9.8", UsbNcmDiscovery.normalizePeerAddress("169.254.9.8%usb0"))
        assertEquals(InetAddress.getByName("fd00::1").hostAddress, UsbNcmDiscovery.normalizePeerAddress("[fd00::1]"))
        assertEquals("100.64.1.2", UsbNcmDiscovery.normalizePeerAddress("100.64.1.2"))
        assertNull(UsbNcmDiscovery.normalizePeerAddress("192.0.2.1"))
        assertNull(UsbNcmDiscovery.normalizePeerAddress("8.8.8.8"))
        assertNull(UsbNcmDiscovery.normalizePeerAddress("receiver.local"))
        assertNull(UsbNcmDiscovery.normalizePeerAddress("::1"))
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
