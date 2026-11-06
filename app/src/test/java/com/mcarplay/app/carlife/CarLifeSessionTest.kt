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
    fun stopPreservesLastListenerFailureForReportAfterConnectorIsReleased() {
        var now = 0L
        val connector =
            CarLifeChannelConnector(
                connect = { throw ConnectException("connection refused") },
                sleep = { now += 2 },
                nowMs = { now },
            )
        org.junit.Assert.assertThrows(CarLifeListenerTimeoutException::class.java) { connector.connectAll(timeoutMs = 1) }
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
        field("connector").set(session, connector)
        (field("running").get(session) as AtomicBoolean).set(true)
        session.stop()
        val summary = session.diagnosticSummary()
        assertNull(field("connector").get(session))
        assertTrue(summary, summary.contains("lastFailedPort=7240 lastFailure=REFUSED"))
        assertTrue(summary, summary.contains("timedOut=true stopped=true"))
    }

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
    fun blockedEntryMessageKeepsOriginalCauseAndAction() {
        val timeout = CarLifeListenerTimeoutException("CarLife listener wait deadline: ECONNREFUSED")
        val message = CarLifeSession.clientFailureMessage(timeout, DiagnosticCode.CARWITH_ENTRY_BLOCKED)
        assertTrue(message.startsWith(CarLifeSession.ENTRY_BLOCKED_HINT))
        assertTrue("Original cause must stay in the report", message.contains("ECONNREFUSED"))
    }

    @Test
    fun protocolSilentMessageExplainsPortsAreNotAValidSession() {
        val message =
            CarLifeSession.clientFailureMessage(
                "CarLife 关键通道 VIDEO 已断开",
                DiagnosticCode.CARWITH_PROTOCOL_SILENT,
            )
        assertTrue(message.startsWith(CarLifeSession.PROTOCOL_SILENT_HINT))
    }

    @Test
    fun genericFailureDoesNotAddCarWithEntryAdvice() {
        assertEquals(
            "CarLife 客户端失败：reset",
            CarLifeSession.clientFailureMessage(ConnectException("reset"), DiagnosticCode.CARWITH_FAILED),
        )
        assertFalse(
            CarLifeSession.clientFailureMessage(IOException("cancelled"), DiagnosticCode.CARWITH_FAILED).contains("七路"),
        )
    }

    @Test
    fun listenerTimeoutWithObservedNotificationEndsAsListenersPending() {
        var ended: String? = null
        val session =
            CarLifeSession(
                Application(),
                onAccessUnit = {},
                onEnded = { ended = it },
                log = {},
                notificationSnapshot = {
                    CarWithNotificationSnapshot(
                        probeState = NotificationProbeState.CONNECTED,
                        postEvents = 1,
                        foregroundPostEvents = 1,
                        activeNotifications = 1,
                        activeForegroundNotifications = 1,
                    )
                },
            )
        (field("running").get(session) as AtomicBoolean).set(true)
        invokeFail(session, CarLifeListenerTimeoutException("CarLife listener wait deadline: ECONNREFUSED"))

        assertEquals(DiagnosticCode.CARWITH_LISTENERS_PENDING, session.endDiagnosticCode)
        assertTrue(ended.orEmpty().startsWith(CarLifeSession.LISTENERS_PENDING_HINT))
        assertFalse(session.entryBlockedSuspected)
    }

    @Test
    fun allPortsWithoutCommandFramesEndsAsProtocolSilent() {
        val connector = CarLifeChannelConnector(connect = { java.net.Socket() })
        connector.connectAll(timeoutMs = 100)
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
        field("connector").set(session, connector)
        (field("running").get(session) as AtomicBoolean).set(true)
        invokeFail(session, "CarLife 关键通道 VIDEO 已断开")

        assertEquals(DiagnosticCode.CARWITH_PROTOCOL_SILENT, session.endDiagnosticCode)
    }

    @Test
    fun newSessionEndsWithGenericCarWithCode() {
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
        assertEquals(DiagnosticCode.CARWITH_FAILED, session.endDiagnosticCode)
    }

    @Test
    fun cancellationDuringEntryRequestDoesNotLaunchLegacyServiceOrSettings() {
        lateinit var session: CarLifeSession
        session =
            CarLifeSession(
                Application(),
                onAccessUnit = {},
                onEnded = {},
                log = {},
                requestPublicEntry = {
                    session.stop()
                    CarLifeLauncher.EntryLaunchResult.MISSING
                },
                requestLegacyEntry = { error("must not launch after stop") },
                requestSettingsEntry = { error("must not launch after stop") },
            )
        (field("running").get(session) as AtomicBoolean).set(true)
        CarLifeSession::class.java
            .getDeclaredMethod("requestCompatibleEntry")
            .apply { isAccessible = true }
            .invoke(session)
        assertFalse(session.isRunning)
    }

    @Test
    fun attachedSessionWithoutVideoRequestsTheEntryOnce() {
        var requests = 0
        val session =
            CarLifeSession(
                Application(),
                onAccessUnit = {},
                onEnded = {},
                log = {},
                requestPublicEntry = {
                    requests++
                    CarLifeLauncher.EntryLaunchResult.REQUESTED
                },
                requestLegacyEntry = { error("public entry succeeded") },
                requestSettingsEntry = { error("public entry succeeded") },
                attachedFirstFrameWaitMs = 10,
            )
        (field("running").get(session) as AtomicBoolean).set(true)
        repeat(2) {
            startAttachWatch(session).join(2_000)
        }
        assertEquals(1, requests)
        assertEquals(CarLifeSession.EntryRoute.CARWITH_ENTRY, field("entryRoute").get(session))
    }

    @Test
    fun stoppedAttachedSessionNeverRequestsTheEntry() {
        val session =
            CarLifeSession(
                Application(),
                onAccessUnit = {},
                onEnded = {},
                log = {},
                requestPublicEntry = { error("must not launch after stop") },
                attachedFirstFrameWaitMs = 60_000,
            )
        (field("running").get(session) as AtomicBoolean).set(true)
        val watch = startAttachWatch(session)
        session.stop()
        watch.join(2_000)
        assertFalse(watch.isAlive)
    }

    private fun startAttachWatch(session: CarLifeSession): Thread {
        CarLifeSession::class.java
            .getDeclaredMethod("startAttachedFirstFrameWatch")
            .apply { isAccessible = true }
            .invoke(session)
        return field("attachWatchThread").get(session) as Thread
    }

    private fun invokeFail(
        session: CarLifeSession,
        failure: Exception,
    ) {
        CarLifeSession::class.java
            .getDeclaredMethod("fail", Exception::class.java)
            .apply { isAccessible = true }
            .invoke(session, failure)
    }

    private fun invokeFail(
        session: CarLifeSession,
        message: String,
    ) {
        CarLifeSession::class.java
            .getDeclaredMethod("fail", String::class.java, Exception::class.java)
            .apply { isAccessible = true }
            .invoke(session, message, null)
    }

    @Test
    fun defaultLaunchStrategyAttachesBeforeRequestingAnEntry() {
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
        assertEquals(CarLifeLaunchStrategy.COMPATIBLE, field("launchStrategy").get(session))
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
    fun compatibleStrategyUsesRootLegacyServiceWhenCarWithEntryIsMissing() {
        // CarWith 3.1.2 之前没有 CarlifeAdbActivity；有 uid 0 时直接启动原厂内部服务。
        val plan =
            CarLifeSession.planCompatibleEntry(
                carWithEntry = CarLifeLauncher.EntryLaunchResult.MISSING,
                legacyEntry = CarLifeLauncher.EntryLaunchResult.REQUESTED,
                settingsEntry = CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED,
            )
        assertEquals(CarLifeLauncher.EntryLaunchResult.REQUESTED, plan.result)
        assertEquals(CarLifeSession.EntryRoute.LEGACY_ROOT_SERVICE, plan.route)
    }

    @Test
    fun compatibleStrategyKeepsCarWithEntryWhenItWorks() {
        val plan =
            CarLifeSession.planCompatibleEntry(
                carWithEntry = CarLifeLauncher.EntryLaunchResult.REQUESTED,
                legacyEntry = CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED,
                settingsEntry = CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED,
            )
        assertEquals(CarLifeLauncher.EntryLaunchResult.REQUESTED, plan.result)
        assertEquals(CarLifeSession.EntryRoute.CARWITH_ENTRY, plan.route)
    }

    @Test
    fun compatibleStrategyOpensCarWithSettingsWhenPrivilegedLaunchFails() {
        val plan =
            CarLifeSession.planCompatibleEntry(
                carWithEntry = CarLifeLauncher.EntryLaunchResult.MISSING,
                legacyEntry = CarLifeLauncher.EntryLaunchResult.PERMISSION_DENIED,
                settingsEntry = CarLifeLauncher.EntryLaunchResult.REQUESTED,
            )
        assertEquals(CarLifeLauncher.EntryLaunchResult.REQUESTED, plan.result)
        assertEquals(CarLifeSession.EntryRoute.CARWITH_SETTINGS, plan.route)
    }

    @Test
    fun compatibleStrategyReportsLastFailureWhenEveryRouteFails() {
        val plan =
            CarLifeSession.planCompatibleEntry(
                carWithEntry = CarLifeLauncher.EntryLaunchResult.MISSING,
                legacyEntry = CarLifeLauncher.EntryLaunchResult.PERMISSION_DENIED,
                settingsEntry = CarLifeLauncher.EntryLaunchResult.FAILED,
            )
        assertEquals(CarLifeLauncher.EntryLaunchResult.FAILED, plan.result)
        assertEquals(CarLifeSession.EntryRoute.NONE, plan.route)
    }

    @Test
    fun legacyLaunchCommandMatchesCarWithNormalWirelessServicePath() {
        assertEquals(
            "am start-foreground-service -n com.miui.carlink/com.miui.carlink.castfwk.CarlinkService " +
                "--ei startType 4 --es carlife_wireless_type Baidu_ap " +
                "--es carlife_wireless_type_name mcarplay",
            CarLifeLauncher.legacyCarWithServiceCommand(),
        )
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
