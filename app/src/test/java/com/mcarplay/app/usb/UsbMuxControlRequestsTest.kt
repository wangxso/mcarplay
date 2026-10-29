package com.mcarplay.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbMuxControlRequestsTest {
    private fun setup(
        type: Int,
        request: Int,
        length: Int,
    ) = UsbSetupPacket(type, request, value = 0, index = 0, length = length)

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
    fun acknowledgesVendorOutRequestsAndStallsUnsupportedInRequests() {
        assertEquals(Ep0Reply.AckOut(0), UsbMuxControlRequests.replyTo(setup(0x40, 0x40, 0)))
        assertEquals(Ep0Reply.AckOut(4), UsbMuxControlRequests.replyTo(setup(0x40, 0x77, 4)))
        assertEquals(Ep0Reply.Stall, UsbMuxControlRequests.replyTo(setup(0xC0, 0x77, 1)))
        assertEquals(Ep0Reply.Stall, UsbMuxControlRequests.replyTo(setup(0x80, 0x06, 18)))
    }
}
