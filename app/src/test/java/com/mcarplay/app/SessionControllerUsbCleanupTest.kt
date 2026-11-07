package com.mcarplay.app

import android.app.Application
import com.mcarplay.app.diagnostics.DiagnosticCode
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
            // A non-user reason keeps disconnect off AutoConnectPrefs (no SharedPreferences in JVM tests).
            SessionController.disconnect(Application(), DiagnosticCode.VIDEO_CLOSED)
            assertFalse(SessionController.isUsbMediaBenchConnected())
            assertFalse(SessionController.sessionStarting.get())
            assertTrue(SessionController.sessionStarting.compareAndSet(false, true))
        } finally {
            // Background teardown clears the completion latch; later tests must not see a disconnect in flight.
            val deadline = System.currentTimeMillis() + 5_000L
            while (SessionController.isDisconnecting() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            endpointField.set(SessionController, old)
            SessionController.sessionStarting.set(false)
        }
    }
}
