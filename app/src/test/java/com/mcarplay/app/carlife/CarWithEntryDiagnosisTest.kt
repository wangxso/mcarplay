package com.mcarplay.app.carlife

import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWithEntryDiagnosisTest {
    private val unknown = CarWithNotificationSnapshot()
    private val connected = CarWithNotificationSnapshot(probeState = NotificationProbeState.CONNECTED)
    private val observed =
        CarWithNotificationSnapshot(
            probeState = NotificationProbeState.CONNECTED,
            postEvents = 1L,
            foregroundPostEvents = 1L,
            activeNotifications = 1,
            activeForegroundNotifications = 1,
        )

    @Test
    fun backgroundTimeoutWithoutObservationKeepsBlockedEntryAdvice() {
        assertEquals(
            DiagnosticCode.CARWITH_ENTRY_BLOCKED,
            classify(listenersTimedOut = true, background = true, notification = unknown),
        )
    }

    @Test
    fun foregroundTimeoutWithoutProbeIsExplicitlyUnknown() {
        assertEquals(
            DiagnosticCode.CARWITH_ENTRY_STATUS_UNKNOWN,
            classify(listenersTimedOut = true, background = false, notification = unknown),
        )
    }

    @Test
    fun connectedProbeWithoutCarWithNotificationIsNotObserved() {
        assertEquals(
            DiagnosticCode.CARWITH_ENTRY_NOT_OBSERVED,
            classify(listenersTimedOut = true, background = false, notification = connected),
        )
    }

    @Test
    fun visibleCarWithNotificationMeansListenersArePending() {
        assertEquals(
            DiagnosticCode.CARWITH_LISTENERS_PENDING,
            classify(listenersTimedOut = true, background = true, notification = observed),
        )
    }

    @Test
    fun newNotificationAfterBaselineCountsEvenIfAlreadyDismissed() {
        val baseline = CarWithNotificationSnapshot(probeState = NotificationProbeState.CONNECTED)
        val dismissedButSeen =
            connected.copy(
                postEvents = 1L,
                foregroundPostEvents = 1L,
                activeNotifications = 0,
                activeForegroundNotifications = 0,
            )
        assertEquals(
            DiagnosticCode.CARWITH_LISTENERS_PENDING,
            classify(
                listenersTimedOut = true,
                background = false,
                notification = dismissedButSeen,
                baseline = baseline,
            ),
        )
    }

    @Test
    fun connectedPortsWithoutAnyProtocolFrameAreProtocolSilent() {
        assertEquals(
            DiagnosticCode.CARWITH_PROTOCOL_SILENT,
            classify(
                listenersTimedOut = false,
                background = false,
                notification = observed,
                connectedOnce = true,
                commandFrames = 0L,
            ),
        )
    }

    @Test
    fun protocolOrLaterFailureKeepsGenericCode() {
        assertEquals(
            DiagnosticCode.CARWITH_FAILED,
            classify(
                listenersTimedOut = false,
                background = false,
                notification = observed,
                connectedOnce = true,
                commandFrames = 2L,
            ),
        )
    }

    @Test
    fun baselineNotificationSelectionDoesNotTreatOldRemovedPostAsRunEvidence() {
        val old = observed.copy(activeNotifications = 0, activeForegroundNotifications = 0)
        val evidence =
            CarWithEntryDiagnosis.Evidence(
                listenersTimedOut = true,
                listenersConnectedOnce = false,
                commandFramesReceived = 0L,
                entryLaunchedInBackground = false,
                notification = old,
                notificationBaseline = old,
            )
        assertFalse(evidence.notificationSeenDuringRun)
        assertTrue(evidence.notification.probeState == NotificationProbeState.CONNECTED)
    }

    private fun classify(
        listenersTimedOut: Boolean,
        background: Boolean,
        notification: CarWithNotificationSnapshot,
        baseline: CarWithNotificationSnapshot? = null,
        connectedOnce: Boolean = false,
        commandFrames: Long = 0L,
    ): DiagnosticCode =
        CarWithEntryDiagnosis.classify(
            CarWithEntryDiagnosis.Evidence(
                listenersTimedOut = listenersTimedOut,
                listenersConnectedOnce = connectedOnce,
                commandFramesReceived = commandFrames,
                entryLaunchedInBackground = background,
                notification = notification,
                notificationBaseline = baseline,
            ),
        )
}
