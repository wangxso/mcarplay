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

    @Test
    fun audioFramesDroppedBeforeInitAreLoggedInsteadOfSilentlyIgnored() {
        val lines = mutableListOf<String>()
        val events = mutableListOf<CarLifeAudioEvent>()
        val session =
            CarLifeSession(
                Application(),
                onAccessUnit = {},
                onEnded = {},
                onAudioEvent = { _, event -> events += event },
                log = { lines += it },
            )
        val pcm = ByteArray(8) { 1 }
        session.handleAudio(mediaFrame(CarLifeProtocol.MEDIA_DATA, pcm))
        session.handleAudio(mediaFrame(CarLifeProtocol.MEDIA_DATA, pcm))
        assertTrue(events.isEmpty())
        assertEquals(1, lines.count { "MEDIA 首次收到 kind=0x30006" in it })
        assertEquals(1, lines.count { "MEDIA 音频帧未解析 kind=0x30006" in it && "累计 1" in it })

        // rate=44100, channels=2, format=16（未握手时无 AES，按明文解析）
        val init = byteArrayOf(0x08, 0xC4.toByte(), 0xD8.toByte(), 0x02, 0x10, 0x02, 0x18, 0x10)
        session.handleAudio(mediaFrame(CarLifeProtocol.MEDIA_INIT, init))
        session.handleAudio(mediaFrame(CarLifeProtocol.MEDIA_DATA, pcm))
        assertEquals(CarLifeAudioEvent.Init(44100, 2, 16), events.first())
        assertTrue(events.last() is CarLifeAudioEvent.Pcm)
    }

    @Test
    fun malformedAudioInitLogsDecodedFields() {
        val lines = mutableListOf<String>()
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = { lines += it })
        session.handleAudio(mediaFrame(CarLifeProtocol.MEDIA_INIT, byteArrayOf(0x08, 0x05)))
        assertTrue(lines.any { "INIT 字段 rate=5 channels=null format=null" in it })
    }

    private fun mediaFrame(
        kind: Int,
        payload: ByteArray,
    ): CarLifeFrame =
        CarLifeFrame(
            channel = CarLifeProtocol.PORT_MEDIA,
            kind = kind,
            timestamp = 1,
            reserved = 0,
            payload = payload,
        )

    private fun field(name: String) = CarLifeSession::class.java.getDeclaredField(name).apply { isAccessible = true }
}
