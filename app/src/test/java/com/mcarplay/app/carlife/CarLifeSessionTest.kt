package com.mcarplay.app.carlife

import android.app.ActivityManager
import android.app.Application
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CarLifeSessionTest {
    @Test
    fun stoppingSleepingHeartbeatDoesNotReachUncaughtHandler() {
        repeat(20) {
            val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
            val running = field("running").get(session) as AtomicBoolean
            running.set(true)
            CarLifeSession::class.java
                .getDeclaredMethod("startHeartbeat")
                .apply { isAccessible = true }
                .invoke(session)
            val heartbeat = field("heartbeatThread").get(session) as Thread
            val uncaught = AtomicReference<Throwable?>()
            heartbeat.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (heartbeat.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertTrue("Heartbeat must be sleeping before cancellation", heartbeat.state == Thread.State.TIMED_WAITING)
            } finally {
                session.stop()
                heartbeat.join(2_000)
            }
            assertFalse("Stopped heartbeat must terminate", heartbeat.isAlive)
            assertFalse(session.isRunning)
            assertNull("Normal stop must not produce a FATAL exception", uncaught.get())
            assertNull(field("heartbeatThread").get(session))
            session.stop()
        }
    }

    @Test
    fun heartbeatStartedAfterStopExitsWithoutSending() {
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
        CarLifeSession::class.java
            .getDeclaredMethod("startHeartbeat")
            .apply { isAccessible = true }
            .invoke(session)
        val heartbeat = field("heartbeatThread").get(session) as Thread
        heartbeat.join(2_000)
        assertFalse(heartbeat.isAlive)
        assertFalse(session.isRunning)
    }

    @Test
    fun listenerTimeoutAfterBackgroundEntryIsReportedAsBlockedEntry() {
        val timeout = CarLifeListenerTimeoutException("CarLife listener wait deadline: ECONNREFUSED")
        assertTrue(CarLifeSession.isEntryBlockedSuspected(timeout, launchedInBackground = true))
        val message = CarLifeSession.clientFailureMessage(timeout, entryBlockedSuspected = true)
        assertTrue(message.startsWith(CarLifeSession.ENTRY_BLOCKED_HINT))
        assertTrue("Original cause must stay in the report", message.contains("ECONNREFUSED"))
    }

    @Test
    fun foregroundEntryOrOtherErrorsKeepGenericFailure() {
        val timeout = CarLifeListenerTimeoutException("CarLife listener wait deadline: ECONNREFUSED")
        assertFalse(CarLifeSession.isEntryBlockedSuspected(timeout, launchedInBackground = false))
        assertFalse(CarLifeSession.isEntryBlockedSuspected(IOException("CarLife connection cancelled"), true))
        assertFalse(CarLifeSession.isEntryBlockedSuspected(ConnectException("reset"), true))
        assertEquals(
            "CarLife 客户端失败：reset",
            CarLifeSession.clientFailureMessage(ConnectException("reset"), entryBlockedSuspected = false),
        )
    }

    @Test
    fun newSessionEndsWithGenericCarWithCode() {
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
        assertEquals(DiagnosticCode.CARWITH_FAILED, session.endDiagnosticCode)
    }

    @Test
    fun onlyTopImportanceCountsAsForeground() {
        assertTrue(CarLifeLauncher.isForegroundImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue("Unfilled importance must not raise a false warning", CarLifeLauncher.isForegroundImportance(0))
        assertFalse(CarLifeLauncher.isForegroundImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertFalse(CarLifeLauncher.isForegroundImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        assertFalse(CarLifeLauncher.isForegroundImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED))
    }

    @Test
    fun onlyExplicitMiuiDenialTriggersBackgroundStartGuidance() {
        assertEquals(true, CarLifeLauncher.isBackgroundStartModeAllowed(android.app.AppOpsManager.MODE_ALLOWED))
        assertEquals(false, CarLifeLauncher.isBackgroundStartModeAllowed(android.app.AppOpsManager.MODE_IGNORED))
        assertNull(
            "Unknown op on non-MIUI must not prompt",
            CarLifeLauncher.isBackgroundStartModeAllowed(android.app.AppOpsManager.MODE_ERRORED),
        )
        assertNull(CarLifeLauncher.isBackgroundStartModeAllowed(android.app.AppOpsManager.MODE_DEFAULT))
    }

    private fun field(name: String) = CarLifeSession::class.java.getDeclaredField(name).apply { isAccessible = true }
}
