package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GadgetPlanTest {
    private val plan = GadgetPlan(AppleUsbIdentity(product = "Bob's iPhone", serialNumber = "abc123"))

    @Test
    fun prepareWritesAppleIdentityAndMountsFunctionFsWithoutBinding() {
        val script = plan.prepare()

        assertTrue(script.contains("echo 0x05ac > /config/usb_gadget/mcarplay/idVendor"))
        assertTrue(script.contains("echo 0x12a8 > /config/usb_gadget/mcarplay/idProduct"))
        assertTrue(script.contains("mount -t functionfs mcarplay_iap /dev/usb-ffs/mcarplay_iap"))
        // 描述符写入前不得绑定 UDC
        assertFalse(script.any { it.contains("mcarplay/UDC") })
        assertEquals("setprop sys.usb.config none", script.first())
        assertEquals("/dev/usb-ffs/mcarplay_iap/ep0", plan.ep0Path)
    }

    @Test
    fun stringsAreShellQuoted() {
        assertTrue(plan.prepare().contains("echo 'Bob'\\''s iPhone' > /config/usb_gadget/mcarplay/strings/0x409/product"))
    }

    @Test
    fun bindRejectsSuspiciousUdcNames() {
        assertEquals(listOf("echo a600000.dwc3 > /config/usb_gadget/mcarplay/UDC"), plan.bind("a600000.dwc3"))
        assertTrue(runCatching { plan.bind("x; reboot") }.isFailure)
    }

    @Test
    fun prepareUnbindsWhicheverGadgetHoldsTheUdc() {
        val unbind = plan.prepare().single { it.startsWith("for g in") }
        assertTrue(unbind.contains("/config/usb_gadget/*"))
        assertTrue(unbind.contains("[ \"\$g\" = /config/usb_gadget/mcarplay ] && continue"))
    }

    @Test
    fun teardownRestoresLegacyConfigThenResetsGadgetHal() {
        val script = plan.teardown("mtp,adb")
        assertEquals("setprop sys.usb.config mtp,adb", script[script.size - 2])
        assertEquals(RESET_HAL, script.last())
    }

    @Test
    fun teardownOnGadgetHalDevicesOnlyResets() {
        // 小米 17：进入前 sys.usb.config=none
        for (previous in listOf("none", "", "adb;reboot")) {
            val script = plan.teardown(previous)
            assertFalse(script.any { it.startsWith("setprop") })
            assertEquals(RESET_HAL, script.last())
        }
    }

    @Test
    fun teardownForHostModeLeavesAndroidGadgetUnbound() {
        val script = plan.teardown("mtp,adb", restoreAndroid = false)
        assertFalse(script.any { it.startsWith("setprop") || it.startsWith("svc") })
        assertTrue(script.first().contains("mcarplay/UDC"))
    }

    private companion object {
        const val RESET_HAL = "svc usb resetUsbGadget 2>/dev/null || true"
    }

    @Test
    fun quoteHandlesEmbeddedQuotes() {
        assertEquals("'a'\\''b'", GadgetPlan.quote("a'b"))
    }
}
