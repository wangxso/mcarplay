package com.mcarplay.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIdentityTest {
    @Test
    fun normalizesCommonSeparatorsToUpperColonForm() {
        listOf("ac:c1:ee:12:34:56", "AC-C1-EE-12-34-56", "acc1ee123456", " AC C1 EE 12 34 56 ").forEach {
            assertEquals("AC:C1:EE:12:34:56", DeviceIdentity.normalizeMac(it))
        }
    }

    @Test
    fun rejectsPlaceholderRandomMulticastAndMalformedAddresses() {
        listOf(
            DeviceIdentity.PLACEHOLDER_MAC,
            "00:00:00:00:00:00",
            "FF:FF:FF:FF:FF:FF",
            "01:11:22:33:44:55",
            "C6:11:22:33:44:55",
            "AC:C1:EE:12:34",
            "AC:C1:EE:12:34:5G",
            "",
        ).forEach { assertNull(it, DeviceIdentity.normalizeMac(it)) }
    }

    @Test
    fun liveReadWinsThenManualThenRemembered() {
        val live = DeviceIdentity.Resolved("AC:C1:EE:00:00:01", DeviceIdentity.Source.SHIZUKU)
        assertEquals(live, DeviceIdentity.choose(live, "AC:C1:EE:00:00:02", "AC:C1:EE:00:00:03"))
        assertEquals(
            DeviceIdentity.Resolved("AC:C1:EE:00:00:02", DeviceIdentity.Source.MANUAL),
            DeviceIdentity.choose(null, "AC:C1:EE:00:00:02", "AC:C1:EE:00:00:03"),
        )
        assertEquals(
            DeviceIdentity.Resolved("AC:C1:EE:00:00:03", DeviceIdentity.Source.REMEMBERED),
            DeviceIdentity.choose(null, null, "AC:C1:EE:00:00:03"),
        )
        assertEquals(
            DeviceIdentity.Resolved(DeviceIdentity.PLACEHOLDER_MAC, DeviceIdentity.Source.NONE),
            DeviceIdentity.choose(null, null, null),
        )
    }
}
