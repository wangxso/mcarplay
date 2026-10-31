package com.mcarplay.app

import android.app.Application
import com.mcarplay.app.usb.UsbMediaEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerUsbCleanupTest {
    @Test
    fun disconnectWhileWaitingForUsbReleasesStartingGate() {
        val endpointField = SessionController::class.java.getDeclaredField("usbMediaEndpoint").apply { isAccessible = true }
        val old = endpointField.get(SessionController)
        try {
            endpointField.set(SessionController, UsbMediaEndpoint("usb0", "fe80::1", 7000))
            SessionController.sessionStarting.set(true)
            SessionController.disconnect(Application())
            assertFalse(SessionController.isUsbMediaBenchConnected())
            assertFalse(SessionController.sessionStarting.get())
            assertTrue(SessionController.sessionStarting.compareAndSet(false, true))
        } finally {
            endpointField.set(SessionController, old)
            SessionController.sessionStarting.set(false)
        }
    }
}
