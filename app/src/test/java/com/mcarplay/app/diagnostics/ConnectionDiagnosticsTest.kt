package com.mcarplay.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test
    fun selfTestAndCarPlayAttemptsKeepSeparateRecords() {
        val carPlay = ConnectionDiagnostics.begin("user_start")
        val check = ConnectionDiagnostics.selfTest.begin("carwith_self_test")
        ConnectionDiagnostics.selfTest.stage(check, ConnectionStage.SELF_TEST)

        // A receiver endpoint during the self-test continues the CarPlay attempt, not a new one over the self-test.
        assertEquals(carPlay, ConnectionDiagnostics.receiverAttempt())
        assertEquals("waiting", ConnectionDiagnostics.selfTest.snapshot()!!.status)

        // Disconnecting CarPlay ends only the CarPlay record; the self-test is still running.
        ConnectionDiagnostics.finishCurrent(DiagnosticCode.USER_STOP, false)
        assertEquals("stopped", ConnectionDiagnostics.timeline.snapshot()!!.status)
        assertEquals("waiting", ConnectionDiagnostics.selfTest.snapshot()!!.status)

        ConnectionDiagnostics.selfTest.finish(check, DiagnosticCode.USER_STOP, false)
    }

    @Test
    fun showingPermissionDialogRecordsNothingAndOnlyDenialIsAFailure() {
        val before = ConnectionDiagnostics.begin("user_start")
        ConnectionDiagnostics.permissionResult(granted = true, deniedCode = DiagnosticCode.WIFI_PERMISSION)
        assertEquals(before, ConnectionDiagnostics.timeline.snapshot()!!.id)
        assertEquals("waiting", ConnectionDiagnostics.timeline.snapshot()!!.status)

        ConnectionDiagnostics.permissionResult(granted = false, deniedCode = DiagnosticCode.WIFI_PERMISSION)
        val denied = ConnectionDiagnostics.timeline.snapshot()!!
        assertNotEquals(before, denied.id)
        assertEquals(DiagnosticCode.WIFI_PERMISSION, denied.code)
        assertEquals("failed", denied.status)
    }
}
