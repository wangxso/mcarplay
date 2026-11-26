package com.mcarplay.app.net

import com.mcarplay.app.session.RealAttemptScope
import com.mcarplay.app.session.SessionCancelSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 3b J 层：CarWifiJoiner 的所有长步进等待在真实 scope / session signal 取消后
 * 必须 <150 ms 退场，并释放 wifiBindLock（含 A10–A20）。
 */
class CarWifiJoinerCancelLatencyTest {
    private data class WifiId(
        val id: String,
    ) {
        override fun toString(): String = id
    }

    private class FakeLink : CarWifiLink<WifiId> {
        override var currentNetwork: WifiId? = null
        var ssid: String? = "CarAP"
        var verified: WifiId? = null
        var createNetworkOnBind: WifiId? = null

        override fun currentWifiNetwork(): WifiId? = currentNetwork

        override fun currentWifiSsid(): String? = ssid

        override fun hasExistingWifi(): Boolean = true

        override fun singleWifiNetwork(): WifiId? = null

        override fun systemActiveWifiState(ssid: String): CarWifiLink.SystemWifiState = CarWifiLink.SystemWifiState.NONE

        override fun verifiedWifiNetwork(ssid: String): WifiId? = verified

        override fun joinCarPlayNetwork(
            ssid: String,
            passphrase: String,
            securityType: Int,
            timeoutMillis: Long,
            isCancelled: () -> Boolean,
            quickFallback: (() -> WifiId?)?,
            deepFallback: (() -> WifiId?)?,
            userChoicePending: ((Long) -> Boolean)?,
            awaitLatch: (CountDownLatch, Long) -> Boolean,
        ): CarWifiLink.JoinResult<WifiId> = CarWifiLink.JoinResult.Unavailable

        override fun bindCurrentWifi(
            ssidHint: String?,
            expectedSsid: String?,
            onResult: (Boolean) -> Unit,
        ) = Unit

        override fun bindVerifiedNetwork(
            network: WifiId,
            ssidHint: String?,
        ): Boolean = true

        override fun release() = Unit
    }

    private class Rig(
        private val sleepFn: (Long) -> Unit,
        private val awaitLatchFn: (CountDownLatch, Long) -> Boolean,
    ) {
        val link = FakeLink()
        val lock = Any()
        var shellEnabled = true
        var shellSsid: String? = "Office"
        var shellReady = true
        var enableOnCommand = true

        val joiner =
            CarWifiJoiner<WifiId, Any>(
                link = { link },
                state = CarWifiState(),
                bindingLock = lock,
                globalCancelled = { false },
                ui =
                    object : CarWifiUiPort {
                        override fun phase(hint: String?) = Unit

                        override fun hint(message: () -> String) = Unit
                    },
                text = { id, _ -> id.toString() },
                askChoice = { _, _, _, _ -> null },
                captureToken = { null },
                matchesToken = { _, _, _ -> false },
                shellReady = { shellReady },
                shell =
                    CarWifiShellPort { command, _ ->
                        when {
                            command == "cmd wifi status" ->
                                CarWifiShellResult(
                                    0,
                                    if (shellEnabled) {
                                        "Wifi is enabled\nWifi is connected to \"${shellSsid ?: "none"}\""
                                    } else {
                                        "Wifi is disabled"
                                    },
                                )
                            command == "cmd wifi list-networks" -> CarWifiShellResult(0, "")
                            command.startsWith("cmd wifi connect-network ") -> CarWifiShellResult(0, "")
                            command == "cmd wifi set-wifi-enabled enabled" -> {
                                if (enableOnCommand) shellEnabled = true
                                CarWifiShellResult(0, "")
                            }
                            else -> CarWifiShellResult(0, "")
                        }
                    },
                readPreference = { CarWifiInternetPreference.CAR },
                bindVerifiedAndRegister = { _, _, _, _ -> false },
                verifiedNetworkFor = { null },
                reannounce = { _, _, _ -> },
                background = { _, block -> block() },
                uptimeMillis = {
                    uptime
                },
                sleep = sleepFn,
                awaitLatch = awaitLatchFn,
                openWlanSettings = {},
                copyHotspotPsk = {},
                log = {},
                invitedWifiLabel = "invited",
            )

        @Volatile
        var uptime = 0L
    }

    private fun wifiConfig(ssid: String = "CarAP") =
        com.mcarplay.app.iap2.Iap2SenderSession.WifiConfiguration(
            ssid = ssid,
            passphrase = "12345678",
            channel = 36,
            securityType = 2,
        )

    private fun awaitCancelWithin150ms(
        signal: SessionCancelSignal,
        scope: RealAttemptScope,
        action: () -> Unit,
    ) {
        val done = CountDownLatch(1)
        val worker =
            Thread {
                try {
                    action()
                } finally {
                    done.countDown()
                }
            }
        worker.start()
        Thread.sleep(150)
        val start = System.nanoTime()
        scope.cancel(com.mcarplay.app.diagnostics.DiagnosticCode.USER_STOP)
        signal.cancel()
        assertTrue("取消后等待线程未在 150ms 内退场", done.await(500, TimeUnit.MILLISECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("取消响应 ${elapsedMs}ms 超过 150ms", elapsedMs < 150L)
        worker.join(500)
    }

    @Test(timeout = 12_000L)
    fun manualJoinCarWifiFiveSecondStepStopsPromptly() {
        val signal = SessionCancelSignal()
        val scope = RealAttemptScope("manual", null)
        val rig =
            Rig(
                sleepFn = { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
                awaitLatchFn = { latch, ms -> scope.await(latch, ms) },
            )
        rig.link.ssid = "Other"
        awaitCancelWithin150ms(signal, scope) {
            rig.joiner.manualJoinCarWifi(wifiConfig(), { !scope.isActive }, { ms -> if (!scope.sleep(ms)) throw InterruptedException() })
        }
    }

    @Test(timeout = 12_000L)
    fun waitForWifiEnabledTwoSecondStepStopsPromptly() {
        val scope = RealAttemptScope("wifi-off", null)
        lateinit var rig: Rig
        rig =
            Rig(
                sleepFn = { ms ->
                    if (!scope.sleep(ms)) throw InterruptedException()
                    rig.advanceClock(ms)
                },
                awaitLatchFn = { latch, ms -> scope.await(latch, ms) },
            )
        rig.shellEnabled = false
        rig.enableOnCommand = false
        awaitCancelWithin150ms(SessionCancelSignal(), scope) {
            rig.joiner.waitForWifiEnabled(
                { !scope.isActive },
                { ms ->
                    if (!scope.sleep(ms)) throw InterruptedException()
                    rig.advanceClock(ms)
                },
            )
        }
    }

    private fun Rig.advanceClock(millis: Long) {
        uptime += millis
    }

    @Test(timeout = 12_000L)
    fun waitForKnownWifiThenContinueOneSecondStepStopsPromptly() {
        val scope = RealAttemptScope("known", null)
        val rig =
            Rig(
                sleepFn = { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
                awaitLatchFn = { latch, ms -> scope.await(latch, ms) },
            )
        rig.link.ssid = "Office"
        rig.shellSsid = "Office"
        awaitCancelWithin150ms(SessionCancelSignal(), scope) {
            rig.joiner.waitForKnownWifiThenContinue(
                "CarAP",
                "test",
                null,
                { !scope.isActive },
                { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
            ) {}
        }
    }

    @Test(timeout = 12_000L)
    fun waitForManualWifiThenContinueOneSecondStepStopsPromptly() {
        val scope = RealAttemptScope("manual-continue", null)
        val rig =
            Rig(
                sleepFn = { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
                awaitLatchFn = { latch, ms -> scope.await(latch, ms) },
            )
        rig.link.ssid = "Office"
        rig.shellSsid = "Office"
        awaitCancelWithin150ms(SessionCancelSignal(), scope) {
            rig.joiner.waitForManualWifiThenContinue(
                wifiConfig(),
                { !scope.isActive },
                { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
            ) {}
        }
    }

    @Test(timeout = 12_000L)
    fun waitForCarSsidHoldingBindLockStopsAndReleasesLock() {
        val scope = RealAttemptScope("ssid", null)
        val rig =
            Rig(
                sleepFn = { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
                awaitLatchFn = { latch, ms -> scope.await(latch, ms) },
            )
        rig.link.ssid = "Other"
        val lockAcquiredAfterCancel = AtomicBoolean(false)
        awaitCancelWithin150ms(SessionCancelSignal(), scope) {
            try {
                synchronized(rig.lock) {
                    rig.joiner.waitForCarSsid(
                        "CarAP",
                        25_000L,
                        { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
                        { !scope.isActive },
                    )
                }
            } catch (_: InterruptedException) {
                // 取消即以中断唤醒；随后验证 wifiBindLock 已释放。
            }
            synchronized(rig.lock) {
                lockAcquiredAfterCancel.set(true)
            }
        }
        assertTrue("取消后必须释放 wifiBindLock", lockAcquiredAfterCancel.get())
    }

    @Test(timeout = 12_000L)
    fun specifierDeepFallbackHoldingBindLockStopsAndReleasesLock() {
        val scope = RealAttemptScope("deep", null)
        val rig =
            Rig(
                sleepFn = { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
                awaitLatchFn = { latch, ms -> scope.await(latch, ms) },
            )
        rig.link.ssid = "Other"
        awaitCancelWithin150ms(SessionCancelSignal(), scope) {
            synchronized(rig.lock) {
                rig.joiner.specifierDeepFallback(
                    wifiConfig(),
                    { ms -> if (!scope.sleep(ms)) throw InterruptedException() },
                    { !scope.isActive },
                )
            }
        }
    }

    @Test(timeout = 12_000L)
    fun sessionSignalCancellationDoesNotDependOnAttemptScope() {
        val wired = SessionCancelSignal()
        val scopeCancelled = AtomicBoolean(false)
        val rig =
            Rig(
                sleepFn = { ms -> wired.sleep(ms).also { ok -> if (!ok) throw InterruptedException() } },
                awaitLatchFn = { latch, ms -> wired.await(latch, ms) },
            )
        rig.link.ssid = "Other"
        val done = CountDownLatch(1)
        val worker =
            Thread {
                try {
                    rig.joiner.waitForManualWifiThenContinue(
                        wifiConfig(),
                        { false },
                        { ms -> if (!wired.sleep(ms)) throw InterruptedException() },
                    ) {}
                } finally {
                    done.countDown()
                }
            }
        worker.start()
        Thread.sleep(150)
        scopeCancelled.set(true)
        val cancelledScope = RealAttemptScope("unrelated", null)
        cancelledScope.cancel(com.mcarplay.app.diagnostics.DiagnosticCode.BT_LINK_CLOSED)
        assertFalse("0x5703 预入网不得被无关轮次取消误伤", done.await(300, TimeUnit.MILLISECONDS))
        val start = System.nanoTime()
        wired.cancel()
        assertTrue(done.await(500, TimeUnit.MILLISECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("session cancel 响应 ${elapsedMs}ms 超过 150ms", elapsedMs < 150L)
        worker.join(500)
        assertTrue(scopeCancelled.get())
    }
}
