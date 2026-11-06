package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWithNotificationTrackerTest {
    private var now = 1_000L
    private val tracker = CarWithNotificationTracker(nowElapsedMs = { now })

    @Test
    fun startsUnknownAndDoesNotClaimCarWithNeverStarted() {
        val snapshot = tracker.snapshot()
        assertEquals(NotificationProbeState.UNKNOWN, snapshot.probeState)
        assertFalse(snapshot.anyObserved)
        assertFalse(snapshot.foregroundObserved)
    }

    @Test
    fun recordsOnlyCarWithPackageAndForegroundFlag() {
        tracker.onPosted(
            key = "0|com.example.music|1|private-tag",
            packageName = "com.example.music",
            foreground = true,
        )
        tracker.onPosted(
            key = "0|com.miui.carlink|42|private-tag",
            packageName = CarLifeLauncher.CARWITH_PACKAGE,
            foreground = true,
        )
        now = 2_500L

        val snapshot = tracker.snapshot()
        assertEquals(1L, snapshot.postEvents)
        assertEquals(1L, snapshot.foregroundPostEvents)
        assertEquals(1, snapshot.activeNotifications)
        assertEquals(1, snapshot.activeForegroundNotifications)
        assertTrue(snapshot.diagnosticSummary(now).contains("foregroundPosts=1"))
        assertFalse(snapshot.diagnosticSummary(now).contains("private-tag"))
    }

    @Test
    fun connectedListenerAdoptsAlreadyVisibleNotifications() {
        now = 4_000L
        tracker.onListenerConnected(
            listOf(
                CarWithNotificationEntry("foreground", true),
                CarWithNotificationEntry("other", false),
            ),
        )

        val snapshot = tracker.snapshot()
        assertEquals(NotificationProbeState.CONNECTED, snapshot.probeState)
        assertEquals(2, snapshot.activeNotifications)
        assertEquals(1, snapshot.activeForegroundNotifications)
        assertEquals(4_000L, snapshot.firstSeenElapsedMs)
        assertEquals(4_000L, snapshot.lastSeenElapsedMs)
    }

    @Test
    fun removalKeepsHistoricalEvidenceButClearsActiveState() {
        tracker.onPosted("carwith", CarLifeLauncher.CARWITH_PACKAGE, foreground = true)
        tracker.onRemoved("carwith")

        val snapshot = tracker.snapshot()
        assertTrue(snapshot.anyObserved)
        assertTrue(snapshot.foregroundObserved)
        assertEquals(0, snapshot.activeNotifications)
        assertEquals(1L, snapshot.removalEvents)
    }

    @Test
    fun disconnectClearsActiveStateAndMarksProbeUnavailable() {
        tracker.onListenerConnected(listOf(CarWithNotificationEntry("carwith", true)))
        tracker.onListenerDisconnected()

        val snapshot = tracker.snapshot()
        assertEquals(NotificationProbeState.DISCONNECTED, snapshot.probeState)
        assertTrue(snapshot.anyObserved)
        assertEquals(0, snapshot.activeNotifications)
        assertTrue(snapshot.diagnosticSummary(now).contains("active=unknown"))
    }

    @Test
    fun baselineSeparatesOldNotificationsFromNewOnes() {
        tracker.onPosted("old", CarLifeLauncher.CARWITH_PACKAGE, foreground = true)
        val baseline = tracker.snapshot()
        now = 8_000L
        tracker.onPosted("new", CarLifeLauncher.CARWITH_PACKAGE, foreground = false)

        val delta = tracker.snapshot().deltaSince(baseline)
        assertEquals(1L, delta.postEvents)
        assertEquals(0L, delta.foregroundPostEvents)
    }
}
