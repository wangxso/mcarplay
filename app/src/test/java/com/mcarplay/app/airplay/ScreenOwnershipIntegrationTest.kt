package com.mcarplay.app.airplay

import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.compat.ScreenReturnStrategy
import com.mcarplay.app.compat.VehiclePreset
import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I 层：真实 [ModeArbiter] 与真实 [com.mcarplay.app.compat.ScreenReturnPolicy] 接入 [ScreenOwnership]，
 * 只把会话生命周期/队列/时钟换成可控假实现。覆盖附录 B-17（让出/拿回/恢复）与 B-11（推流邀请重连）。
 */
class ScreenOwnershipIntegrationTest {
    private class Harness {
        val modes = ModeArbiter()
        val logs = mutableListOf<String>()
        val assertCalls = mutableListOf<Pair<Boolean, Long>>()
        val releasePayloads = mutableListOf<List<Map<String, Any?>>>()
        val sleeps = mutableListOf<Long>()
        val controlTasks = ArrayDeque<() -> Unit>()
        val backgroundTasks = mutableListOf<Pair<String, () -> Unit>>()
        val token = Any()
        var clock = 10_000L
        var currentToken: Any? = token
        var connectCancelled = false
        var sessionStarting = false
        var videoRunning = true
        var touches = 0
        var keyFrames = 0
        var recovery: (() -> RecoveryOutcome)? = null
        var recoveryCalls = 0
        var reprimeCount = 0
        var localFrameCalls = 0
        var screenReturn = ScreenReturnStrategy.OFF
        var reconnectEnabled = false
        val reconnects = mutableListOf<Any>()
        var disconnectCount = 0
        var generation = 7L
        var connectionCurrent = true

        val ownership =
            ScreenOwnership<Any>(
                clients =
                    object : ScreenOwnershipClient<Any> {
                        override fun generation(client: Any) = generation

                        override fun isConnectionCurrent(
                            client: Any,
                            generation: Long,
                        ) = connectionCurrent

                        override fun isCancelled(client: Any) = false

                        override fun phoneOwnsScreen(client: Any) = modes.phoneOwnsScreen

                        override fun modesDescription(client: Any) = modes.describe()

                        override fun assertModes(
                            client: Any,
                            releaseScreen: Boolean,
                            expectedEpoch: Long,
                        ): Boolean {
                            assertCalls += releaseScreen to expectedEpoch
                            if (releaseScreen) releasePayloads += modes.resourcesPayload(releaseScreen = true)
                            return true
                        }
                    },
                runtime =
                    object : ScreenOwnershipRuntime<Any> {
                        override val currentClient: Any? get() = currentToken
                        override val connectCancelled: Boolean get() = this@Harness.connectCancelled
                        override val sessionStarting: Boolean get() = this@Harness.sessionStarting
                        override val videoRunning: Boolean get() = this@Harness.videoRunning
                        override val touchCount: Int get() = touches
                        override val forceKeyFrameCount: Int get() = keyFrames

                        override fun readRecovery(): (() -> RecoveryOutcome)? =
                            recovery?.let { recover ->
                                {
                                    recoveryCalls++
                                    recover()
                                }
                            }

                        override fun reprime() {
                            reprimeCount++
                        }

                        override fun requestLocalKeyFrame(): Boolean {
                            localFrameCalls++
                            return true
                        }

                        override fun onRecoveryFailed(client: Any) {
                            logs += "FAILED"
                        }

                        override fun onTakeBackDisconnect(client: Any) {
                            disconnectCount++
                        }

                        override fun reconnectStreaming(client: Any) {
                            reconnects += client
                        }

                        override fun receiverPolicy(client: Any): ReceiverCompatibility.Policy =
                            policy(reconnectOnStreamingInvite = reconnectEnabled)

                        override fun screenReturnPolicy(client: Any): ReceiverCompatibility.Policy = policy(screenReturn = screenReturn)
                    },
                scheduling =
                    object : ScreenOwnershipScheduling {
                        override fun executeControl(task: () -> Unit) {
                            controlTasks.addLast(task)
                        }

                        override fun runBackground(
                            name: String,
                            task: () -> Unit,
                        ) {
                            backgroundTasks.add(name to task)
                        }

                        override fun uptimeMillis(): Long = clock

                        override fun sleep(millis: Long) {
                            sleeps += millis
                            clock += millis
                        }
                    },
                log = { logs += it },
            )

        fun runNextControl() {
            val task = controlTasks.removeFirst()
            task()
        }

        fun runBackground(name: String) {
            val index = backgroundTasks.indexOfFirst { it.first == name }
            check(index >= 0) { "no background task $name" }
            val (_, task) = backgroundTasks.removeAt(index)
            task()
        }
    }

    @Test
    fun requestUiAfterReturningToCarRestoresStreamAndClearsRelinquished() {
        val h = Harness()
        h.modes.onUserReturnedToCar()
        assertFalse(h.modes.phoneOwnsScreen)
        val ui =
            h.modes.onUserRequestedUi(takeAudio = false)
        assertTrue("归还后重新进入要求重建", ui.rebuildStream)
        assertFalse("主屏本归车机时不需要先让出", ui.retake)

        h.recovery = {
            h.modes.onScreenStreamRestored()
            RecoveryOutcome.RESTORED
        }
        h.ownership.enqueueModesAssert(
            h.token,
            "requestUI",
            requestKeyFrame = ui.allowed,
            retakeScreen = ui.retake,
            rebuildStream =
                com.mcarplay.app.compat.ScreenReturnPolicy
                    .rebuildStream(h.screenReturn, ui.rebuildStream),
        )
        h.runNextControl()

        assertEquals(1, h.recoveryCalls)
        assertEquals(1, h.reprimeCount)
        assertTrue(h.modes.phoneOwnsScreen)
        assertEquals("恢复后调度 3 秒检查", 1, h.backgroundTasks.size)
        assertTrue(h.logs.any { it.startsWith("EVENT: requestUI → modesChanged 已应答") })

        // 恢复已清 relinquished：再次 requestUI（主屏仍归手机）不再要求重建
        assertFalse(h.modes.onUserRequestedUi(takeAudio = false).rebuildStream)
    }

    @Test
    fun abortedRestoreKeepsRelinquishedPending() {
        val h = Harness()
        h.modes.onUserReturnedToCar()
        val ui = h.modes.onUserRequestedUi(takeAudio = false)
        h.recovery = { RecoveryOutcome.ABORTED }
        h.ownership.enqueueModesAssert(
            h.token,
            "requestUI",
            requestKeyFrame = ui.allowed,
            rebuildStream =
                com.mcarplay.app.compat.ScreenReturnPolicy
                    .rebuildStream(h.screenReturn, ui.rebuildStream),
        )
        h.runNextControl()
        assertEquals(1, h.recoveryCalls)
        assertEquals("ABORTED 不清理归还标记", 0, h.reprimeCount)
        assertTrue("恢复未成功，仍要求重建", h.modes.onUserRequestedUi(takeAudio = false).rebuildStream)
    }

    @Test
    fun alreadyOwningScreenRetakesByReleasingFirst() {
        val h = Harness()
        val ui = h.modes.onUserRequestedUi(takeAudio = false)
        assertTrue("主屏本就归手机", ui.retake)
        h.ownership.enqueueModesAssert(h.token, "requestUI", requestKeyFrame = true, retakeScreen = ui.retake)
        h.runNextControl()

        assertEquals(listOf(true to 7L, false to 7L), h.assertCalls)
        assertEquals("让出成功固定间隔 200ms", listOf(200L), h.sleeps)
        assertEquals("让出报文声明主屏归车机", ModeArbiter.Entity.ACCESSORY.code, h.releasePayloads.single().first()["entity"])
        assertTrue("让出只改报文，不改内部归属", h.modes.phoneOwnsScreen)
    }

    @Test
    fun highConstraintNeverIsNotTaken() {
        val h = Harness()
        h.modes.applyInitial(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to ModeArbiter.SCREEN,
                            "transferType" to ModeArbiter.TRANSFER_TAKE,
                            "transferPriority" to ModeArbiter.CONSTRAINT_NEVER,
                            "takeConstraint" to ModeArbiter.CONSTRAINT_NEVER,
                        ),
                    ),
            ),
        )
        assertFalse(h.modes.phoneOwnsScreen)
        val ui = h.modes.onUserRequestedUi(takeAudio = false)
        assertFalse("约束高于 userInitiated 不抢", ui.allowed)
        assertFalse(ui.retake)

        h.ownership.enqueueModesAssert(h.token, "requestUI", requestKeyFrame = ui.allowed, retakeScreen = ui.retake)
        h.runNextControl()
        assertTrue("asserted=false 时不重建/不补帧", h.recoveryCalls == 0 && h.localFrameCalls == 0)
        assertTrue(h.controlTasks.isEmpty())
    }

    @Test
    fun threeSecondCheckUsesStrategyAtExpiryNotAtRequest() {
        val h = Harness()
        h.screenReturn = ScreenReturnStrategy.OFF
        h.ownership.enqueueModesAssert(h.token, "requestUI", requestKeyFrame = true)
        h.runNextControl()
        assertEquals("请求时 OFF 也会先安排检查", 1, h.backgroundTasks.size)

        h.screenReturn = ScreenReturnStrategy.REBUILD_RETRY
        h.runBackground("takeback-check")
        assertTrue(
            "3 秒到期才读策略：窗口内切到 REBUILD_RETRY 后应触发重建",
            h.logs.contains("PASSAT: 策略「失败后重建一次」——拿回主屏车机没反应，重建主屏流重试"),
        )
        assertEquals(1, h.controlTasks.size)
    }

    @Test
    fun enabledStreamingInviteReconnectsCapturedClientAndDebounces() {
        val h = Harness()
        h.reconnectEnabled = true
        h.clock = 20_000L
        h.ownership.onStreamingInvite(h.token)
        assertEquals(1, h.backgroundTasks.size)

        // 后台任务尚未执行时客户端已更替：组件仍用捕获的 client，不换最新
        h.currentToken = Any()
        h.runBackground("streaming-invite-reconnect")
        assertEquals(listOf<Any>(h.token), h.reconnects)

        h.clock = 34_999L
        h.ownership.onStreamingInvite(h.token)
        assertTrue(h.logs.any { it.startsWith("CTRL: 推流中收到车机邀请——距上次邀请重连 14999ms") })
        assertTrue(h.backgroundTasks.isEmpty())
    }

    @Test
    fun notEnabledStreamingInviteOnlyLogs() {
        val h = Harness()
        h.reconnectEnabled = false
        h.ownership.onStreamingInvite(h.token)
        assertTrue(h.logs.contains("CTRL: 推流中收到车机邀请——该车机未登记邀请重连，保持当前会话"))
        assertTrue(h.backgroundTasks.isEmpty())
        assertTrue(h.reconnects.isEmpty())
    }

    @Test
    fun nonStreamingInviteNeverReadsDecisionClock() {
        val h = Harness()
        h.videoRunning = false
        val before = h.clock
        h.ownership.onStreamingInvite(h.token)
        assertEquals(before, h.clock)
        assertTrue(h.backgroundTasks.isEmpty())
    }
}

private fun policy(
    screenReturn: ScreenReturnStrategy = ScreenReturnStrategy.OFF,
    reconnectOnStreamingInvite: Boolean = false,
): ReceiverCompatibility.Policy =
    ReceiverCompatibility
        .resolve(ReceiverProfile.Signals(), preset = VehiclePreset.GENERIC)
        .copy(screenReturn = screenReturn, reconnectOnStreamingInvite = reconnectOnStreamingInvite)
