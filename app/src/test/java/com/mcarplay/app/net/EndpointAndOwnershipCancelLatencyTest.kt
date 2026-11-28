package com.mcarplay.app.net

import com.mcarplay.app.WifiPromptBroker
import com.mcarplay.app.airplay.RecoveryOutcome
import com.mcarplay.app.airplay.ScreenOwnership
import com.mcarplay.app.airplay.ScreenOwnershipClient
import com.mcarplay.app.airplay.ScreenOwnershipRuntime
import com.mcarplay.app.airplay.ScreenOwnershipScheduling
import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.compat.VehiclePreset
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.session.RealAttemptScope
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 3b J 层：端点邀请等待、ScreenOwnership 三段等待与弹窗等待在真实 scope cancel 后
 * 必须秒醒（A8、A21–A23、A6–A7）。
 */
class EndpointAndOwnershipCancelLatencyTest {
    private fun policy(): ReceiverCompatibility.Policy =
        ReceiverCompatibility.resolve(ReceiverProfile.Signals(), preset = VehiclePreset.GENERIC)

    private fun awaitCancelledWithin(
        scope: RealAttemptScope,
        budgetMs: Long = 150L,
        action: () -> Unit,
    ): Long {
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
        scope.cancel(DiagnosticCode.USER_STOP)
        assertTrue("取消后等待未在 ${budgetMs}ms 预算外退场", done.await(budgetMs + 350, TimeUnit.MILLISECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        worker.join(500)
        return elapsedMs
    }

    @Test(timeout = 10_000L)
    fun awaitCtrlInviteStopsWhenAttemptScopeCancels() {
        val scope = RealAttemptScope("ctrl-invite", null)
        val resolver =
            EndpointResolver(
                uptimeMillis = { android.os.SystemClock.uptimeMillis() },
                sleepMillis = { ms -> Thread.sleep(ms) },
                isConnectCancelled = { !scope.isActive },
                captureInviteView = { null },
                awaitEndpoint = { null },
                forceAnnounce = {},
                gatewayCandidates = { emptyList() },
                directDiscoveryPolicy = { _, _ -> false },
                log = {},
                resolveWaitMillis = 3_000L,
            )
        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "CarAP",
                passphrase = "12345678",
                channel = 36,
                securityType = 2,
                ipAddresses = emptyList(),
                airPlayPort = 0,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                receiverSignals = ReceiverProfile.Signals(),
                observation = null,
            )
        val elapsed =
            awaitCancelledWithin(scope) {
                resolver.awaitCtrlInvite(endpoint, 9_000L) { ms ->
                    if (!scope.sleep(ms)) throw InterruptedException("scope cancelled")
                }
            }
        assertTrue("awaitCtrlInvite 取消响应 ${elapsed}ms 超过 150ms", elapsed < 150L)
    }

    @Test(timeout = 10_000L)
    fun awaitCtrlInviteStopsWhenSessionSignalCancels() {
        val signal =
            com.mcarplay.app.session
                .SessionCancelSignal()
        val resolver =
            EndpointResolver(
                uptimeMillis = { android.os.SystemClock.uptimeMillis() },
                sleepMillis = { ms -> if (!signal.sleep(ms)) throw InterruptedException("signal cancelled") },
                isConnectCancelled = { signal.isCancelled },
                captureInviteView = { null },
                awaitEndpoint = { null },
                forceAnnounce = {},
                gatewayCandidates = { emptyList() },
                directDiscoveryPolicy = { _, _ -> false },
                log = {},
                resolveWaitMillis = 3_000L,
            )
        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "CarAP",
                passphrase = "12345678",
                channel = 36,
                securityType = 2,
                ipAddresses = emptyList(),
                airPlayPort = 0,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                receiverSignals = ReceiverProfile.Signals(),
                observation = null,
            )
        val done = CountDownLatch(1)
        val worker =
            Thread {
                try {
                    resolver.awaitCtrlInvite(endpoint, 9_000L)
                } finally {
                    done.countDown()
                }
            }
        worker.start()
        Thread.sleep(150)
        val start = System.nanoTime()
        signal.cancel()
        assertTrue(done.await(500, TimeUnit.MILLISECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 150L)
        worker.join(500)
    }

    @Test(timeout = 10_000L)
    fun schedulingSleepForRoutesToCancelledAttemptScope() {
        val scope = RealAttemptScope("screen", null)
        val cancelledDone = CountDownLatch(1)
        val worker =
            Thread {
                scope.sleep(30_000L)
                cancelledDone.countDown()
            }
        worker.start()
        Thread.sleep(100)
        val start = System.nanoTime()
        scope.cancel(DiagnosticCode.USER_STOP)
        assertTrue(cancelledDone.await(1, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 150L)
        worker.join(500)
    }

    @Test(timeout = 10_000L)
    fun screenOwnershipModesReclaimStopsWithin150ms() {
        val scope = RealAttemptScope("reclaim", null)
        val backgroundTasks = ArrayDeque<Pair<String, () -> Unit>>()
        val ownership =
            ScreenOwnership<Any>(
                clients =
                    object : ScreenOwnershipClient<Any> {
                        override fun generation(client: Any) = 1L

                        override fun isConnectionCurrent(
                            client: Any,
                            generation: Long,
                        ) = true

                        override fun isCancelled(client: Any) = !scope.isActive

                        override fun phoneOwnsScreen(client: Any) = false

                        override fun modesDescription(client: Any) = ""

                        override fun assertModes(
                            client: Any,
                            releaseScreen: Boolean,
                            expectedEpoch: Long,
                        ) = false
                    },
                runtime =
                    object : ScreenOwnershipRuntime<Any> {
                        override val currentClient: Any? = null
                        override val connectCancelled: Boolean = false
                        override val sessionStarting: Boolean = false
                        override val videoRunning: Boolean = true
                        override val touchCount: Int = 0
                        override val forceKeyFrameCount: Int = 0

                        override fun readRecovery(): (() -> RecoveryOutcome)? = null

                        override fun reprime() = Unit

                        override fun requestLocalKeyFrame(): Boolean = false

                        override fun onRecoveryFailed(client: Any) = Unit

                        override fun onTakeBackDisconnect(client: Any) = Unit

                        override fun reconnectStreaming(client: Any) = Unit

                        override fun receiverPolicy(client: Any): ReceiverCompatibility.Policy = policy()

                        override fun screenReturnPolicy(client: Any): ReceiverCompatibility.Policy = policy()
                    },
                scheduling =
                    object : ScreenOwnershipScheduling {
                        override fun executeControl(task: () -> Unit) = task()

                        override fun runBackground(
                            name: String,
                            task: () -> Unit,
                        ) {
                            backgroundTasks.addLast(name to task)
                        }

                        override fun uptimeMillis(): Long = android.os.SystemClock.uptimeMillis()

                        override fun sleep(millis: Long) = Thread.sleep(millis)

                        override fun sleepFor(
                            client: Any,
                            millis: Long,
                        ) {
                            scope.sleep(millis)
                        }
                    },
                log = {},
            )
        ownership.scheduleModesReclaim(Unit)
        val task = backgroundTasks.removeFirst().second
        val done = CountDownLatch(1)
        val worker =
            Thread {
                try {
                    task()
                } finally {
                    done.countDown()
                }
            }
        worker.start()
        Thread.sleep(150)
        val start = System.nanoTime()
        scope.cancel(DiagnosticCode.BT_LINK_CLOSED)
        assertTrue(done.await(500, TimeUnit.MILLISECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("scheduleModesReclaim 取消响应 ${elapsedMs}ms 超过 150ms", elapsedMs < 150L)
        worker.join(500)
    }

    @Test(timeout = 10_000L)
    fun wifiPromptBrokerAwaitStopsWhenScopeCancelled() {
        val scope = RealAttemptScope("prompt", null)
        val broker = WifiPromptBroker<String, Any> { a, b -> a == b }
        val owner = broker.claim("use-current", Any()) as WifiPromptBroker.Claim.Owner
        val elapsed =
            awaitCancelledWithin(scope, budgetMs = 150L) {
                broker.await(owner.future, 90_000L, isExpired = { !scope.isActive }, awaitLatch = { latch, ms -> scope.await(latch, ms) })
            }
        assertTrue("弹窗等待取消响应 ${elapsed}ms 超过 150ms", elapsed < 150L)
    }
}
