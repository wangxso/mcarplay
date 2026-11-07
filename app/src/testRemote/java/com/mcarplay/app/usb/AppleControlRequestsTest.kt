package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleControlRequestsTest {
    private fun setup(
        type: Int,
        request: Int,
        length: Int = 0,
    ) = UsbSetupPacket(type, request, 1, 0, length)

    @Test
    fun vendorOutRequestsAreClassified() {
        assertEquals(AppleControlRequests.Kind.ROLE_SWITCH, AppleControlRequests.classify(setup(0x40, 0x51)))
        assertEquals(AppleControlRequests.Kind.POWER, AppleControlRequests.classify(setup(0x40, 0x40)))
        assertEquals(AppleControlRequests.Kind.OTHER_VENDOR, AppleControlRequests.classify(setup(0x40, 0x52)))
    }

    @Test
    fun vendorInWithRoleSwitchNumberIsNotARoleSwitch() {
        assertEquals(AppleControlRequests.Kind.OTHER_VENDOR, AppleControlRequests.classify(setup(0xC0, 0x51)))
    }

    @Test
    fun standardAndClassRequestsAreNotVendor() {
        assertEquals(AppleControlRequests.Kind.NON_VENDOR, AppleControlRequests.classify(setup(0x00, 0x51)))
        assertEquals(AppleControlRequests.Kind.NON_VENDOR, AppleControlRequests.classify(setup(0x21, 0x51)))
    }

    @Test
    fun repliesAckOutReadingDataStageAndStallUnknownIn() {
        assertEquals(Ep0Reply.AckOut(0), AppleControlRequests.replyTo(setup(0x40, 0x51)))
        assertEquals(Ep0Reply.AckOut(4), AppleControlRequests.replyTo(setup(0x40, 0x77, length = 4)))
        assertEquals(Ep0Reply.Stall, AppleControlRequests.replyTo(setup(0xC0, 0x45, length = 1)))
        assertEquals(Ep0Reply.Stall, AppleControlRequests.replyTo(setup(0x80, 0x06, length = 18)))
    }
}
