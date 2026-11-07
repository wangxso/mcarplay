package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbNcmIdentityModeTest {
    @Test
    fun `iPhone compatibility falls back to generic NCM`() {
        assertEquals(
            listOf(UsbNcmIdentityMode.IPHONE_COMPAT, UsbNcmIdentityMode.GENERIC),
            UsbNcmIdentityMode.attempts(preferIphoneIdentity = true),
        )
    }

    @Test
    fun `generic identity can be selected without an Apple attempt`() {
        assertEquals(
            listOf(UsbNcmIdentityMode.GENERIC),
            UsbNcmIdentityMode.attempts(preferIphoneIdentity = false),
        )
    }
}
