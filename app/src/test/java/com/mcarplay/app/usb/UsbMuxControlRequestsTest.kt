package com.mcarplay.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMuxControlRequestsTest {
    private fun setup(
        type: Int,
        request: Int,
        length: Int,
        index: Int = 0,
    ) = UsbSetupPacket(type, request, value = 0, index = index, length = length)

    @Test
    fun reportsCurrentMode() {
        val reply = UsbMuxControlRequests.replyTo(setup(0xC0, UsbMuxControlRequests.REQUEST_GET_MODE, 4))
        assertArrayEquals(UsbMuxProtocol.INITIAL_MODE, (reply as Ep0Reply.WriteIn).data)
    }

    @Test
    fun keepsTheCurrentCompositeConfigurationForModeSwitch() {
        val reply = UsbMuxControlRequests.replyTo(setup(0xC0, UsbMuxControlRequests.REQUEST_SET_MODE, 1))
        assertArrayEquals(byteArrayOf(1), (reply as Ep0Reply.WriteIn).data)
    }

    @Test
    fun promisesReenumerationOnlyForAnAllowedCarPlayModeRequest() {
        val usbmuxd = setup(0xC0, UsbMuxControlRequests.REQUEST_SET_MODE, 1, index = 3)
        val carPlay = setup(0xC0, UsbMuxControlRequests.REQUEST_SET_MODE, 1, index = 4)
        assertTrue(UsbMuxControlRequests.isCarPlayModeRequest(carPlay))
        assertFalse(UsbMuxControlRequests.isCarPlayModeRequest(usbmuxd))
        assertArrayEquals(
            byteArrayOf(0),
            (UsbMuxControlRequests.replyTo(carPlay, reenumerateForCarPlay = true) as Ep0Reply.WriteIn).data,
        )
        assertArrayEquals(
            byteArrayOf(1),
            (UsbMuxControlRequests.replyTo(carPlay, reenumerateForCarPlay = false) as Ep0Reply.WriteIn).data,
        )
        assertArrayEquals(
            byteArrayOf(1),
            (UsbMuxControlRequests.replyTo(usbmuxd, reenumerateForCarPlay = true) as Ep0Reply.WriteIn).data,
        )
    }

    @Test
    fun acknowledgesVendorOutRequestsAndStallsUnsupportedInRequests() {
        assertEquals(Ep0Reply.AckOut(0), UsbMuxControlRequests.replyTo(setup(0x40, 0x40, 0)))
        assertEquals(Ep0Reply.AckOut(4), UsbMuxControlRequests.replyTo(setup(0x40, 0x77, 4)))
        assertEquals(Ep0Reply.Stall, UsbMuxControlRequests.replyTo(setup(0xC0, 0x77, 1)))
        assertEquals(Ep0Reply.Stall, UsbMuxControlRequests.replyTo(setup(0x80, 0x06, 18)))
    }
}
