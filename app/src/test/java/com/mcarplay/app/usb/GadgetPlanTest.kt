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
    fun teardownRestoresPreviousAndroidConfig() {
        assertEquals("setprop sys.usb.config mtp,adb", plan.teardown("mtp,adb").last())
        assertEquals("setprop sys.usb.config ${GadgetPlan.DEFAULT_USB_CONFIG}", plan.teardown("none").last())
        assertEquals("setprop sys.usb.config ${GadgetPlan.DEFAULT_USB_CONFIG}", plan.teardown("adb;reboot").last())
    }

    @Test
    fun teardownForHostModeLeavesAndroidGadgetUnbound() {
        val script = plan.teardown("mtp,adb", restoreAndroid = false)
        assertFalse(script.any { it.startsWith("setprop") })
        assertTrue(script.first().contains("mcarplay/UDC"))
    }

    @Test
    fun quoteHandlesEmbeddedQuotes() {
        assertEquals("'a'\\''b'", GadgetPlan.quote("a'b"))
    }
}
