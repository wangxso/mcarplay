package com.mcarplay.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun hidesCredentialsAndShortSessionKeys() {
        val safe =
            DiagnosticRedactor().redact(
                "psk=VerySecret123 password=\"two words\" readKey=0123456789abcdef writeKey=fedcba9876543210 nonce=challenge",
            )
        listOf("VerySecret123", "two words", "0123456789abcdef", "fedcba9876543210", "challenge").forEach {
            assertFalse(safe.contains(it))
        }
        assertTrue(safe.contains("psk=<redacted>"))
    }

    @Test
    fun removesBearerCredentialsAndPemBlocks() {
        val pem = "-----BEGIN " + "PRIVATE KEY-----\nprivate-base64-material\n-----END PRIVATE KEY-----"
        val safe = DiagnosticRedactor().redact("Authorization: Bearer special-access-token\n$pem")
        assertFalse(safe.contains("special-access-token"))
        assertFalse(safe.contains("private-base64-material"))
    }

    @Test
    fun omitsWireDumpsThatCouldContainHexEncodedCredentials() {
        val safe = DiagnosticRedactor().redact("[12:34:56.000] RAW tx: 70617373776f7264\nbodyHex=deadbeef\n0x4301 原始体=secret")
        assertFalse(safe.contains("70617373776f7264"))
        assertFalse(safe.contains("deadbeef"))
        assertTrue(safe.contains("[12:34:56.000] [wire payload omitted]"))
    }

    @Test
    fun preservesTimesVersionsPortsErrorCodesAndStackFrames() {
        val safe =
            DiagnosticRedactor().redact(
                "[12:34:56.789] version=0.1.0 port=7240 stage=CARWITH error=ECONNREFUSED\n" +
                    " at com.mcarplay.app.carlife.CarLifeSession.start(CarLifeSession.kt:42)",
            )
        assertTrue(safe.contains("12:34:56.789"))
        assertTrue(safe.contains("version=0.1.0 port=7240"))
        assertTrue(safe.contains("ECONNREFUSED"))
        assertTrue(safe.contains("CarLifeSession.kt:42"))
    }

    @Test
    fun correlatesAddressesWithoutRevealingThem() {
        val safe =
            DiagnosticRedactor().redact(
                "10.2.3.4:7240 10.2.3.4:8240 AA:BB:CC:DD:EE:FF aa:bb:cc:dd:ee:ff [fe80::12ab%wlan0]:7000",
            )
        assertFalse(safe.contains("10.2.3.4"))
        assertFalse(safe.contains("fe80::12ab"))
        assertFalse(safe.contains("AA:BB"))
        assertEquals(2, Regex("<IP-1>").findAll(safe).count())
        assertEquals(2, Regex("<MAC-1>").findAll(safe).count())
    }

    @Test
    fun hidesNamesEvenWhenLaterMentionedWithoutLabels() {
        val safe = DiagnosticRedactor().redact("ssid=PrivateHome\n正在加入 PrivateHome…\n目标「Family Pad」\nFamily Pad disconnected")
        assertFalse(safe.contains("PrivateHome"))
        assertFalse(safe.contains("Family Pad"))
        assertEquals(2, Regex("<NAME-1>").findAll(safe).count())
    }

    @Test
    fun ssidWithSpacesIsHiddenWithoutRemovingFollowingTechnicalFields() {
        val safe = DiagnosticRedactor().redact("ssid=Private Family WiFi channel=48 security=2\njoining Private Family WiFi")
        assertFalse(safe.contains("Private Family WiFi"))
        assertFalse(safe.contains("Family"))
        assertTrue(safe.contains("channel=48 security=2"))
    }

    @Test
    fun usesKnownNamesWhenRollingLogNoLongerContainsTheirDeclaration() {
        assertFalse(DiagnosticRedactor(listOf("FamilyCar")).redact("连接 FamilyCar 失败").contains("FamilyCar"))
    }

    @Test
    fun hidesContactInformationLocationsUrlsAndFullDigestsInUntrustedLogs() {
        val safe =
            DiagnosticRedactor().redact(
                "mail=example@example.test phone=13912345678 latitude=30.123 longitude=120.456 " +
                    "https://example.test/?token=hidden 0123456789abcdef0123456789abcdef",
            )
        listOf("example@example.test", "13912345678", "30.123", "120.456", "example.test", "0123456789abcdef").forEach {
            assertFalse(safe.contains(it))
        }
    }
}
