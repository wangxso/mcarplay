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
 * F01–F21 的 J 层：真实 [ScreenOwnership] + 可控单线程队列/假钟/假等待，零真实 sleep、
 * 零系统 uptime、零 Android/网络。期望值来自旧控制器语义（docs Phase 2f 第 2/5 节）。
 */
class ScreenOwnershipTest {
    private class FakeClient(
        val name: String = "client",
    ) {
        var generation = 1L
        var currentGeneration: Long? = 1L
        var cancelled = false
        var ownsScreen = true
        var releaseResult = true
        var plainResult = true
        var onAssert: ((Boolean) -> Unit)? = null
        val assertCalls = mutableListOf<Pair<Boolean, Long>>()

        fun isCurrent(generation: Long): Boolean = currentGeneration == generation
    }

    private class FakeRuntime : ScreenOwnershipRuntime<FakeClient> {
        var currentClientValue: FakeClient? = null
        var connectCancelledValue = false
        var sessionStartingValue = false
        var videoRunningValue = true
        var touchCountValue = 0
        var forceKeyFrameCountValue = 0
        var touchReads: ArrayDeque<Int>? = null
        var keyFrameReads: ArrayDeque<Int>? = null
        var recoveryValue: (() -> RecoveryOutcome)? = null
        var recoveryCalls = 0
        var reprimeCount = 0
        var localKeyFrameResult = true
        var localKeyFrameCalls = 0
        val recoveryFailed = mutableListOf<FakeClient>()
        val takeBackDisconnects = mutableListOf<FakeClient>()
        val reconnects = mutableListOf<FakeClient>()
        var receiverPolicyValue = samplePolicy()
        var screenReturnPolicyValue = samplePolicy()
        var receiverPolicyReads = 0
        var screenReturnPolicyReads = 0

        override val currentClient: FakeClient? get() = currentClientValue
        override val connectCancelled: Boolean get() = connectCancelledValue
        override val sessionStarting: Boolean get() = sessionStartingValue
        override val videoRunning: Boolean get() = videoRunningValue
        override val touchCount: Int
            get() = touchReads?.removeFirstOrNull() ?: touchCountValue
        override val forceKeyFrameCount: Int
            get() = keyFrameReads?.removeFirstOrNull() ?: forceKeyFrameCountValue

        override fun readRecovery(): (() -> RecoveryOutcome)? =
            recoveryValue?.let { recover ->
                {
                    recoveryCalls++
                    recover()
                }
            }

        override fun reprime() {
            reprimeCount++
        }

        override fun requestLocalKeyFrame(): Boolean {
            localKeyFrameCalls++
            return localKeyFrameResult
        }

        override fun onRecoveryFailed(client: FakeClient) {
            recoveryFailed += client
        }

        override fun onTakeBackDisconnect(client: FakeClient) {
            takeBackDisconnects += client
        }

        override fun reconnectStreaming(client: FakeClient) {
            reconnects += client
        }

        override fun receiverPolicy(client: FakeClient): ReceiverCompatibility.Policy {
            receiverPolicyReads++
            return receiverPolicyValue
        }

        override fun screenReturnPolicy(client: FakeClient): ReceiverCompatibility.Policy {
            screenReturnPolicyReads++
            return screenReturnPolicyValue
        }
    }

    /** 控制队列与后台命名任务分开；sleep 由假等待驱动，不真实等待。 */
    private class FakeScheduling {
        val trace = mutableListOf<String>()
        val controlTasks = ArrayDeque<() -> Unit>()
        val backgroundTasks = mutableListOf<Pair<String, () -> Unit>>()
        val sleeps = mutableListOf<Long>()
        var time = 10_000L
        var sleepHook: ((Long) -> Unit)? = null
        var sleepThenThrow: InterruptedException? = null
        var uptimeReads = 0

        fun executeControl(task: () -> Unit) {
            trace += "submit-control"
            controlTasks.addLast(task)
        }

        fun runNextControl() {
            val task = controlTasks.removeFirst()
            trace += "run-control"
            task()
        }

        fun runBackground(name: String) {
            val index = backgroundTasks.indexOfFirst { it.first == name }
            check(index >= 0) { "no background task $name in ${backgroundTasks.map { it.first }}" }
            val (_, task) = backgroundTasks.removeAt(index)
            trace += "run-bg:$name"
            task()
        }
    }

    private inner class Rig {
        val client = FakeClient()
        val runtime = FakeRuntime()
        val scheduling = FakeScheduling()
        val logs = mutableListOf<String>()
        val ownership =
            ScreenOwnership(
                clients =
                    object : ScreenOwnershipClient<FakeClient> {
                        override fun generation(client: FakeClient) = client.generation

                        override fun isConnectionCurrent(
                            client: FakeClient,
                            generation: Long,
                        ) = client.isCurrent(generation)

                        override fun isCancelled(client: FakeClient) = client.cancelled

                        override fun phoneOwnsScreen(client: FakeClient) = client.ownsScreen

                        override fun modesDescription(client: FakeClient) = "modes(${client.name})"

                        override fun assertModes(
                            client: FakeClient,
                            releaseScreen: Boolean,
                            expectedEpoch: Long,
                        ): Boolean {
                            scheduling.trace += "assert:${if (releaseScreen) "release" else "plain"}:$expectedEpoch"
                            client.assertCalls += releaseScreen to expectedEpoch
                            client.onAssert?.invoke(releaseScreen)
                            return if (releaseScreen) client.releaseResult else client.plainResult
                        }
                    },
                runtime = runtime,
                scheduling =
                    object : ScreenOwnershipScheduling {
                        override fun executeControl(task: () -> Unit) {
                            scheduling.executeControl(task)
                        }

                        override fun runBackground(
                            name: String,
                            task: () -> Unit,
                        ) {
                            scheduling.backgroundTasks.add(name to task)
                        }

                        override fun uptimeMillis(): Long {
                            scheduling.uptimeReads++
                            return scheduling.time
                        }

                        override fun sleep(millis: Long) {
                            scheduling.sleeps += millis
                            scheduling.trace += "sleep:$millis"
                            scheduling.sleepHook?.invoke(millis)
                            scheduling.sleepThenThrow?.let { throw it }
                            scheduling.time += millis
                        }
                    },
                log = { logs += it },
            )

        init {
            runtime.currentClientValue = client
        }

        fun takeBackCycle(
            touches: Int = 0,
            keyFrames: Int = 0,
        ) {
            ownership.enqueueModesAssert(client, "拿回", requestKeyFrame = true)
            scheduling.runNextControl()
            runtime.touchCountValue = touches
            runtime.forceKeyFrameCountValue = keyFrames
            scheduling.runBackground("takeback-check")
        }
    }

    // ---- 队列合并 / 迟到丢弃 ----

    @Test
    fun sameClientRequestsMergeBitwiseAndSubmitOneTask() {
        val r = Rig()
        r.runtime.recoveryValue = { RecoveryOutcome.RESTORED }
        r.ownership.enqueueModesAssert(r.client, "first", requestKeyFrame = true)
        r.ownership.enqueueModesAssert(r.client, "second", requestKeyFrame = false, retakeScreen = true, rebuildStream = true)
        assertEquals("同一 client 只提交一个控制任务", 1, r.scheduling.controlTasks.size)
        assertTrue(r.logs.contains("EVENT: second → 与待执行的 modesChanged 合并"))

        r.scheduling.runNextControl()

        assertEquals("三布尔逐位 OR：retake 让出先于普通 assert", listOf(true to 1L, false to 1L), r.client.assertCalls)
        assertEquals("rebuild 合并后仍执行恢复", 1, r.runtime.recoveryCalls)
        assertTrue("首条 reason 保留", r.logs.any { it.startsWith("EVENT: first → modesChanged") })
        assertFalse("合并请求不单独输出完成日志", r.logs.any { it.startsWith("EVENT: second → modesChanged") })
    }

    @Test
    fun mergedRequestKeepsFirstTakeBackRetriedForRetryDecision() {
        val firstFalse = Rig()
        firstFalse.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.REBUILD_RETRY)
        firstFalse.ownership.enqueueModesAssert(firstFalse.client, "a", requestKeyFrame = true, takeBackRetried = false)
        firstFalse.ownership.enqueueModesAssert(firstFalse.client, "b", requestKeyFrame = true, takeBackRetried = true)
        firstFalse.scheduling.runNextControl()
        firstFalse.scheduling.runBackground("takeback-check")
        assertTrue(
            "首条 takeBackRetried=false：失败后仍允许一次重建",
            firstFalse.logs.contains("PASSAT: 策略「失败后重建一次」——拿回主屏车机没反应，重建主屏流重试"),
        )
        assertEquals(1, firstFalse.scheduling.controlTasks.size)

        val firstTrue = Rig()
        firstTrue.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.REBUILD_RETRY)
        firstTrue.ownership.enqueueModesAssert(firstTrue.client, "a", requestKeyFrame = true, takeBackRetried = true)
        firstTrue.ownership.enqueueModesAssert(firstTrue.client, "b", requestKeyFrame = true, takeBackRetried = false)
        firstTrue.scheduling.runNextControl()
        firstTrue.scheduling.runBackground("takeback-check")
        assertFalse(
            "首条 takeBackRetried=true：不得因合并被“纠正”为 false 再重试",
            firstTrue.logs.contains("PASSAT: 策略「失败后重建一次」——拿回主屏车机没反应，重建主屏流重试"),
        )
        assertTrue(firstTrue.logs.contains("PASSAT: 策略 rebuild_retry：拿回主屏车机没反应（连续 1 次）"))
        assertTrue(firstTrue.scheduling.controlTasks.isEmpty())
    }

    @Test
    fun olderSubmittedActionDoesNotClearNewerSlot() {
        val r = Rig()
        val a = FakeClient("a")
        val b = FakeClient("b")
        r.runtime.currentClientValue = b
        r.ownership.enqueueModesAssert(a, "a", requestKeyFrame = true)
        r.ownership.enqueueModesAssert(b, "b", requestKeyFrame = true)
        assertEquals(2, r.scheduling.controlTasks.size)

        r.scheduling.runNextControl()
        assertTrue("旧 A 依门禁丢弃", r.logs.contains("EVENT: a → 会话已结束或更替，丢弃迟到的 modesChanged"))

        r.ownership.enqueueModesAssert(b, "b2", requestKeyFrame = false)
        assertEquals("B 的槽未被 A 出队清掉，仍可合并", 1, r.scheduling.controlTasks.size)

        r.scheduling.runNextControl()
        assertEquals(listOf(false to 1L), b.assertCalls)
        assertTrue(a.assertCalls.isEmpty())
    }

    @Test
    fun runningRequestKeepsSnapshotButAllowsNextSlot() {
        val r = Rig()
        r.runtime.recoveryValue = {
            // 任务已出队、槽已清空：此刻再入队必须形成下一条任务，而不是并进正在运行的任务
            r.ownership.enqueueModesAssert(r.client, "during", requestKeyFrame = true, rebuildStream = false)
            RecoveryOutcome.RESTORED
        }
        r.ownership.enqueueModesAssert(r.client, "first", requestKeyFrame = true, rebuildStream = true)
        r.scheduling.runNextControl()
        assertEquals("运行中不合并，形成下一条", 1, r.scheduling.controlTasks.size)
        assertFalse(r.logs.contains("EVENT: during → 与待执行的 modesChanged 合并"))
    }

    @Test
    fun firstGateDropsLateRequestsWithoutSideEffects() {
        listOf("connectCancelled", "clientReplaced", "generationChanged").forEach { how ->
            val r = Rig()
            r.runtime.recoveryValue = { RecoveryOutcome.RESTORED }
            r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, retakeScreen = true, rebuildStream = true)
            when (how) {
                "connectCancelled" -> r.runtime.connectCancelledValue = true
                "clientReplaced" -> r.runtime.currentClientValue = FakeClient("other")
                "generationChanged" -> {
                    r.client.generation = 2
                    r.client.currentGeneration = 2
                }
            }
            r.scheduling.runNextControl()
            assertTrue("$how: 必须记录迟到丢弃", r.logs.contains("EVENT: x → 会话已结束或更替，丢弃迟到的 modesChanged"))
            assertTrue("$how: 零 assert", r.client.assertCalls.isEmpty())
            assertEquals("$how: 零恢复", 0, r.runtime.recoveryCalls)
            assertEquals("$how: 零补帧", 0, r.runtime.localKeyFrameCalls)
            assertTrue("$how: 零检查", r.scheduling.backgroundTasks.isEmpty())
        }
    }

    @Test
    fun mergedRequestKeepsOriginalGenerationAndIsDropped() {
        val r = Rig()
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true)
        r.client.generation = 2
        r.client.currentGeneration = 2
        r.ownership.enqueueModesAssert(r.client, "y", requestKeyFrame = true)
        assertTrue(r.logs.contains("EVENT: y → 与待执行的 modesChanged 合并"))
        r.scheduling.runNextControl()
        assertTrue("旧 generation 整条迟到丢弃，不因新请求刷新", r.logs.contains("EVENT: x → 会话已结束或更替，丢弃迟到的 modesChanged"))
        assertTrue(r.client.assertCalls.isEmpty())
    }

    // ---- 让出 / 拿回 ----

    @Test
    fun retakeReleasesThenWaitsGapThenAssertsPlain() {
        val r = Rig()
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, retakeScreen = true)
        r.scheduling.runNextControl()
        assertEquals(listOf(true to 1L, false to 1L), r.client.assertCalls)
        assertEquals("让出成功后固定间隔 200ms", listOf(200L), r.scheduling.sleeps)
        assertTrue(
            "顺序：让出 → 200ms → 普通 assert",
            r.scheduling.trace.indexOf("assert:release:1") < r.scheduling.trace.indexOf("sleep:200") &&
                r.scheduling.trace.indexOf("sleep:200") < r.scheduling.trace.indexOf("assert:plain:1"),
        )
    }

    @Test
    fun releaseFailureStillAssertsWithoutGap() {
        val r = Rig()
        r.client.releaseResult = false
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, retakeScreen = true)
        r.scheduling.runNextControl()
        assertEquals(listOf(true to 1L, false to 1L), r.client.assertCalls)
        assertTrue("让出失败不 sleep，仍尝试普通 assert", r.scheduling.sleeps.isEmpty())
    }

    @Test
    fun interruptedGapAbortsWithoutPlainAssert() {
        val r = Rig()
        r.scheduling.sleepHook = { if (it == 200L) throw InterruptedException() }
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, retakeScreen = true)
        r.scheduling.runNextControl()
        assertEquals(listOf(true to 1L), r.client.assertCalls)
        assertEquals(0, r.runtime.recoveryCalls)
        assertTrue(r.logs.none { it.startsWith("EVENT: x → modesChanged") })
    }

    @Test
    fun retakeRequiresPhoneOwnership() {
        val r = Rig()
        r.client.ownsScreen = false
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, retakeScreen = true)
        r.scheduling.runNextControl()
        assertEquals("屏不归手机时不发让出", listOf(false to 1L), r.client.assertCalls)
        assertTrue(r.scheduling.sleeps.isEmpty())
    }

    @Test
    fun clientCancelledGateAfterRecoveryBlocksRestoreEffects() {
        val r = Rig()
        // 恢复闭包先于 client.cancelled 门禁执行（旧顺序）；取消发生在恢复期间，
        // 之后的门禁只能由独有条件的 client.cancelled 拦住。
        r.runtime.recoveryValue = {
            r.client.cancelled = true
            RecoveryOutcome.RESTORED
        }
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, rebuildStream = true)
        r.scheduling.runNextControl()
        assertEquals(1, r.runtime.recoveryCalls)
        assertEquals("恢复后取消：不 reprime", 0, r.runtime.reprimeCount)
        assertEquals("恢复后取消：不补帧", 0, r.runtime.localKeyFrameCalls)
        assertTrue(r.logs.none { it.startsWith("EVENT: x → modesChanged") })
    }

    @Test
    fun lateCancellationBetweenReleaseAndPlainAssertStops() {
        val r = Rig()
        r.client.onAssert = { release -> if (release) r.runtime.connectCancelledValue = true }
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, retakeScreen = true)
        r.scheduling.runNextControl()
        assertEquals("让出后的门禁短路，普通 assert 未发", listOf(true to 1L), r.client.assertCalls)
    }

    // ---- 恢复三态 / 完成日志 ----

    @Test
    fun recoveryOutcomesMapToContinueAbortAndFailure() {
        val restored = Rig()
        restored.runtime.recoveryValue = { RecoveryOutcome.RESTORED }
        restored.ownership.enqueueModesAssert(restored.client, "x", requestKeyFrame = true, rebuildStream = true)
        restored.scheduling.runNextControl()
        assertTrue(restored.logs.any { it.startsWith("EVENT: x → modesChanged 已应答") })
        assertEquals(1, restored.runtime.reprimeCount)

        val aborted = Rig()
        aborted.runtime.recoveryValue = { RecoveryOutcome.ABORTED }
        aborted.ownership.enqueueModesAssert(aborted.client, "x", requestKeyFrame = true, rebuildStream = true)
        aborted.scheduling.runNextControl()
        assertEquals("ABORTED 静默终止", 0, aborted.runtime.reprimeCount)
        assertTrue(aborted.logs.none { it.startsWith("EVENT: x → modesChanged") })
        assertTrue(aborted.scheduling.backgroundTasks.isEmpty())

        val failed = Rig()
        failed.runtime.recoveryValue = { RecoveryOutcome.FAILED }
        failed.ownership.enqueueModesAssert(failed.client, "x", requestKeyFrame = true, rebuildStream = true)
        failed.scheduling.runNextControl()
        assertEquals("FAILED 走原失败出口", listOf(failed.client), failed.runtime.recoveryFailed)
        assertEquals(0, failed.runtime.reprimeCount)
        assertTrue(failed.logs.none { it.startsWith("EVENT: x → modesChanged") })
    }

    @Test
    fun nullRecoveryIsNotFailureAndStillRestores() {
        val r = Rig()
        r.runtime.recoveryValue = null
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, rebuildStream = true)
        r.scheduling.runNextControl()
        assertEquals(0, r.runtime.recoveryCalls)
        assertEquals("null 不视为失败，继续 reprime", 1, r.runtime.reprimeCount)
        assertEquals(1, r.scheduling.backgroundTasks.size)
        assertTrue(r.logs.any { it.startsWith("EVENT: x → modesChanged 已应答") })
    }

    @Test
    fun restoreScreenRequiresKeyFrameAssertedAndOwnership() {
        fun run(
            requestKeyFrame: Boolean,
            asserted: Boolean,
            owns: Boolean,
            localFrame: Boolean = true,
        ): Rig {
            val r = Rig()
            r.client.plainResult = asserted
            r.client.ownsScreen = owns
            r.runtime.localKeyFrameResult = localFrame
            r.runtime.recoveryValue = { RecoveryOutcome.RESTORED }
            r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = requestKeyFrame, rebuildStream = true)
            r.scheduling.runNextControl()
            return r
        }

        assertFalse(
            run(requestKeyFrame = false, asserted = true, owns = true).logs.any { it.startsWith("EVENT: x → modesChanged 已应答，关键帧") },
        )
        assertEquals(0, run(requestKeyFrame = true, asserted = false, owns = true).runtime.recoveryCalls)
        assertEquals(0, run(requestKeyFrame = true, asserted = false, owns = true).runtime.localKeyFrameCalls)
        assertEquals(0, run(requestKeyFrame = true, asserted = true, owns = false).runtime.recoveryCalls)

        val ok = run(requestKeyFrame = true, asserted = true, owns = true)
        assertEquals(1, ok.runtime.recoveryCalls)
        assertEquals(1, ok.runtime.localKeyFrameCalls)
        assertTrue(ok.logs.any { it.startsWith("EVENT: x → modesChanged 已应答，关键帧已请求") })

        val noFrame = run(requestKeyFrame = true, asserted = true, owns = true, localFrame = false)
        assertTrue(noFrame.logs.any { it.startsWith("EVENT: x → modesChanged 已应答，关键帧未就绪") })
    }

    @Test
    fun ownershipIsReadLiveAcrossRecovery() {
        val r = Rig()
        r.runtime.recoveryValue = {
            r.client.ownsScreen = false
            RecoveryOutcome.RESTORED
        }
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true, rebuildStream = true)
        r.scheduling.runNextControl()
        assertEquals(1, r.runtime.recoveryCalls)
        assertEquals("恢复中归属变化：不 reprime", 0, r.runtime.reprimeCount)
        assertEquals("恢复中归属变化：不安排检查", 0, r.scheduling.backgroundTasks.size)
        assertEquals("恢复中归属变化：不补帧", 0, r.runtime.localKeyFrameCalls)
        assertTrue("完成日志仍按 requestKeyFrame 带未就绪后缀", r.logs.any { it.startsWith("EVENT: x → modesChanged 已应答，关键帧未就绪") })
    }

    @Test
    fun localKeyFrameIsNotCountedAsReceiverRequest() {
        val r = Rig()
        r.runtime.forceKeyFrameCountValue = 4
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true)
        r.scheduling.runNextControl()
        assertEquals("自己补帧不增加车机要帧计数", 4, r.runtime.forceKeyFrameCountValue)
        assertEquals(1, r.runtime.localKeyFrameCalls)
    }

    // ---- 3 秒检查 ----

    @Test
    fun takeBackCheckCapturesCountsBeforeSleepAndLogsAfterIt() {
        val r = Rig()
        r.takeBackCycle(touches = 8, keyFrames = 3)
        assertEquals("固定 3000ms", listOf(3_000L), r.scheduling.sleeps)
        assertTrue(
            r.logs.any {
                it.startsWith("SESSION: 拿回主屏 3000ms 后：车机触控 +8 条、要关键帧 +3 次（modes(client)；起因 拿回）")
            },
        )
    }

    @Test
    fun takeBackCheckUsesLiveCountReadsForLogAndVerdict() {
        val r = Rig()
        // 三次读取：基线 5；日志 6（+1）；判据 5（+0 → 失败）
        r.runtime.touchReads = ArrayDeque(listOf(5, 6, 5))
        r.runtime.keyFrameReads = ArrayDeque(listOf(0, 0, 0))
        r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.REBUILD_RETRY)
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true)
        r.scheduling.runNextControl()
        r.scheduling.runBackground("takeback-check")
        assertTrue("日志与会话判据分别活读", r.logs.any { it.contains("车机触控 +1 条、要关键帧 +0 次") })
        assertTrue("判据用独立读取：+0 → 失败", r.logs.contains("PASSAT: 策略「失败后重建一次」——拿回主屏车机没反应，重建主屏流重试"))
    }

    @Test
    fun takeBackCheckOnlyRequiresSameClientAtExpiry() {
        val sameClient = Rig()
        sameClient.ownership.enqueueModesAssert(sameClient.client, "x", requestKeyFrame = true)
        sameClient.scheduling.runNextControl()
        sameClient.client.generation = 9
        sameClient.client.currentGeneration = 9
        sameClient.client.cancelled = true
        sameClient.runtime.connectCancelledValue = true
        sameClient.scheduling.runBackground("takeback-check")
        assertTrue("同 client 新 generation/取消/connectCancelled 不凭新增门禁丢弃", sameClient.logs.any { it.startsWith("SESSION:") })

        val switched = Rig()
        switched.ownership.enqueueModesAssert(switched.client, "x", requestKeyFrame = true)
        switched.scheduling.runNextControl()
        switched.runtime.currentClientValue = FakeClient("other")
        switched.scheduling.runBackground("takeback-check")
        assertFalse(switched.logs.any { it.startsWith("SESSION:") })
        assertEquals(0, switched.runtime.screenReturnPolicyReads)
    }

    @Test
    fun interruptedTakeBackCheckExitsSilently() {
        val r = Rig()
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true)
        r.scheduling.runNextControl()
        r.scheduling.sleepThenThrow = InterruptedException()
        r.scheduling.runBackground("takeback-check")
        assertFalse(r.logs.any { it.startsWith("SESSION:") })
        assertEquals(0, r.runtime.screenReturnPolicyReads)
    }

    // ---- 回屏策略动作 ----

    @Test
    fun offStrategyNeverActs() {
        val r = Rig()
        r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.OFF)
        r.takeBackCycle(touches = 0, keyFrames = 0)
        assertTrue(r.logs.any { it.startsWith("SESSION:") })
        assertFalse(r.logs.any { it.startsWith("PASSAT:") })
        assertTrue(r.scheduling.controlTasks.isEmpty())
        assertTrue(r.runtime.takeBackDisconnects.isEmpty())
    }

    @Test
    fun noRebuildOnlyLogsCount() {
        val r = Rig()
        r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.NO_REBUILD)
        r.takeBackCycle(touches = 0, keyFrames = 0)
        assertTrue(r.logs.contains("PASSAT: 策略 no_rebuild：拿回主屏车机没反应（连续 1 次）"))
        assertTrue(r.scheduling.controlTasks.isEmpty())
    }

    @Test
    fun disconnectFiresAtSecondConsecutiveFailure() {
        val r = Rig()
        r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.DISCONNECT_AFTER_FAILURES)
        r.takeBackCycle(touches = 0, keyFrames = 0)
        assertTrue(r.runtime.takeBackDisconnects.isEmpty())
        assertTrue(r.logs.contains("PASSAT: 策略 disconnect：拿回主屏车机没反应（连续 1 次）"))
        r.takeBackCycle(touches = 0, keyFrames = 0)
        assertEquals(listOf(r.client), r.runtime.takeBackDisconnects)
        assertTrue(r.logs.contains("PASSAT: 策略「连续失败后断开」——连续 2 次拿回主屏车机没反应，结束本轮，请重新连接"))
    }

    @Test
    fun successResetsFailureCount() {
        val r = Rig()
        r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.DISCONNECT_AFTER_FAILURES)
        r.takeBackCycle(touches = 0, keyFrames = 0)
        r.takeBackCycle(touches = 1, keyFrames = 0)
        assertTrue(r.runtime.takeBackDisconnects.isEmpty())
        r.takeBackCycle(touches = 0, keyFrames = 0)
        assertTrue("成功清零后下一次失败仍记 1 次", r.logs.contains("PASSAT: 策略 disconnect：拿回主屏车机没反应（连续 1 次）"))
        assertTrue(r.runtime.takeBackDisconnects.isEmpty())
    }

    @Test
    fun takeBackFailureUsesNonPositiveBothCounts() {
        listOf(-1 to 0, 0 to -1, 0 to 0).forEach { (touches, keyFrames) ->
            val r = Rig()
            r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.NO_REBUILD)
            r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true)
            r.scheduling.runNextControl()
            r.runtime.touchCountValue = touches
            r.runtime.forceKeyFrameCountValue = keyFrames
            r.scheduling.runBackground("takeback-check")
            assertTrue("$touches/$keyFrames 应判失败", r.logs.any { it.startsWith("PASSAT:") })
        }
        val r = Rig()
        r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.NO_REBUILD)
        r.ownership.enqueueModesAssert(r.client, "x", requestKeyFrame = true)
        r.scheduling.runNextControl()
        r.runtime.touchCountValue = 1
        r.runtime.forceKeyFrameCountValue = 0
        r.scheduling.runBackground("takeback-check")
        assertFalse("有触控即成功", r.logs.any { it.startsWith("PASSAT:") })
    }

    // ---- 邀请防抖 ----

    @Test
    fun nonStreamingDoesNotReadPolicyOrClock() {
        listOf("connectCancelled", "starting", "notRunning").forEach { how ->
            val r = Rig()
            when (how) {
                "connectCancelled" -> r.runtime.connectCancelledValue = true
                "starting" -> r.runtime.sessionStartingValue = true
                "notRunning" -> r.runtime.videoRunningValue = false
            }
            r.ownership.onStreamingInvite(r.client)
            assertEquals("$how: 非推流不读策略", 0, r.runtime.receiverPolicyReads)
            assertEquals("$how: 非推流不读时钟", 0, r.scheduling.uptimeReads)
            assertTrue("$how: 非推流不调度", r.scheduling.backgroundTasks.isEmpty())
        }
    }

    @Test
    fun notEnabledOnlyLogs() {
        val r = Rig()
        r.runtime.receiverPolicyValue = samplePolicy(reconnectOnStreamingInvite = false)
        r.ownership.onStreamingInvite(r.client)
        assertEquals(1, r.runtime.receiverPolicyReads)
        assertTrue(r.logs.contains("CTRL: 推流中收到车机邀请——该车机未登记邀请重连，保持当前会话"))
        assertTrue(r.scheduling.backgroundTasks.isEmpty())
    }

    @Test
    fun enabledInviteReconnectsAndDebouncesAtFifteenSeconds() {
        val r = Rig()
        r.runtime.receiverPolicyValue = samplePolicy(reconnectOnStreamingInvite = true)
        r.scheduling.time = 10_000L
        r.ownership.onStreamingInvite(r.client)
        assertEquals("首次邀请立即重连", 1, r.scheduling.backgroundTasks.size)
        r.scheduling.runBackground("streaming-invite-reconnect")
        assertEquals(listOf(r.client), r.runtime.reconnects)

        r.scheduling.time = 24_999L // gap 14_999
        r.ownership.onStreamingInvite(r.client)
        assertTrue(r.logs.any { it.startsWith("CTRL: 推流中收到车机邀请——距上次邀请重连 14999ms，不足 15s，忽略") })
        assertTrue(r.scheduling.backgroundTasks.isEmpty())

        r.scheduling.time = 25_000L // gap 15_000
        r.ownership.onStreamingInvite(r.client)
        assertEquals("边界 15000ms 允许重连", 1, r.scheduling.backgroundTasks.size)
    }

    @Test
    fun zeroSentinelStaysZeroAndDoesNotDebounce() {
        val r = Rig()
        r.runtime.receiverPolicyValue = samplePolicy(reconnectOnStreamingInvite = true)
        r.scheduling.time = 0L
        r.ownership.onStreamingInvite(r.client)
        r.ownership.onStreamingInvite(r.client)
        assertEquals("now=0 记录后仍是哨兵，第二次直接重连", 2, r.scheduling.backgroundTasks.size)
    }

    @Test
    fun droppedBackgroundTaskDoesNotRollBackRecordedTime() {
        val r = Rig()
        r.runtime.receiverPolicyValue = samplePolicy(reconnectOnStreamingInvite = true)
        r.scheduling.time = 20_000L
        r.ownership.onStreamingInvite(r.client)
        val scheduled = r.scheduling.backgroundTasks.size
        // 不运行已调度的后台任务；时间已写入，下一次邀请在窗口内仍应被防抖
        r.scheduling.time = 34_999L
        r.ownership.onStreamingInvite(r.client)
        assertEquals("丢弃任务不回滚已记录时刻", scheduled, r.scheduling.backgroundTasks.size)
        assertTrue(r.logs.any { it.startsWith("CTRL: 推流中收到车机邀请——距上次邀请重连 14999ms") })
    }

    @Test
    fun resetClearsInviteDebounceSentinel() {
        val r = Rig()
        r.runtime.receiverPolicyValue = samplePolicy(reconnectOnStreamingInvite = true)
        r.scheduling.time = 10_000L
        r.ownership.onStreamingInvite(r.client)
        r.ownership.resetInviteReconnectAt()
        r.scheduling.time = 10_001L
        r.ownership.onStreamingInvite(r.client)
        assertEquals("清零后立即允许再次重连", 2, r.scheduling.backgroundTasks.size)
    }

    // ---- 资源重申 ----

    @Test
    fun reclaimUsesAbsoluteDeadlinesFromWorkerStart() {
        val r = Rig()
        r.scheduling.time = 1_000L
        r.ownership.scheduleModesReclaim(r.client)
        assertTrue("不占控制 executor", r.scheduling.controlTasks.isEmpty())
        r.scheduling.runBackground("modes-reclaim")

        assertEquals(4, r.client.assertCalls.size)
        assertEquals(listOf(false to 1L), r.client.assertCalls.distinct())
        assertTrue(r.logs.contains("AIRPLAY: modesChanged 资源重申（burst 后 +1s，AADisplay r17 时序）"))
        assertTrue(r.logs.contains("AIRPLAY: modesChanged 资源重申（burst 后 +4s，AADisplay r17 时序）"))
        assertTrue(r.logs.contains("AIRPLAY: modesChanged 资源重申（burst 后 +12s，AADisplay r17 时序）"))
        assertTrue(r.logs.contains("AIRPLAY: modesChanged 资源重申（burst 后 +30s，AADisplay r17 时序）"))
        assertTrue("每次 sleep 不超过 200ms 轮询", r.scheduling.sleeps.all { it <= 200L })
        assertEquals("结束时刻 = worker 起点 + 30s", 31_000L, r.scheduling.time)
    }

    @Test
    fun newBurstStopsPreviousReclaimWorker() {
        val r = Rig()
        r.scheduling.time = 1_000L
        r.ownership.scheduleModesReclaim(r.client)
        r.ownership.scheduleModesReclaim(r.client)
        r.scheduling.runBackground("modes-reclaim")
        assertTrue("旧 epoch 任务立即停止", r.client.assertCalls.isEmpty())
        r.scheduling.runBackground("modes-reclaim")
        assertEquals(4, r.client.assertCalls.size)
    }

    @Test
    fun reclaimStopsOnCancellationAndStaleGeneration() {
        listOf("clientCancelled", "connectCancelled", "generation").forEach { how ->
            val r = Rig()
            r.scheduling.time = 1_000L
            r.scheduling.sleepHook = {
                when (how) {
                    "clientCancelled" -> r.client.cancelled = true
                    "connectCancelled" -> r.runtime.connectCancelledValue = true
                    "generation" -> r.client.currentGeneration = 99
                }
            }
            r.ownership.scheduleModesReclaim(r.client)
            r.scheduling.runBackground("modes-reclaim")
            assertTrue("$how: 取消后零 assert", r.client.assertCalls.isEmpty())
        }
    }

    @Test
    fun reclaimStopsAfterFailedAssert() {
        val r = Rig()
        r.client.plainResult = false
        r.scheduling.time = 1_000L
        r.ownership.scheduleModesReclaim(r.client)
        r.scheduling.runBackground("modes-reclaim")
        assertEquals("首次 assert 失败即停止剩余重申", 1, r.client.assertCalls.size)
        assertTrue(r.logs.none { it.startsWith("AIRPLAY: modesChanged 资源重申") })
    }

    // ---- 重置 ----

    @Test
    fun resetTakeBackFailuresOnlyClearsCounter() {
        val r = Rig()
        r.runtime.screenReturnPolicyValue = samplePolicy(ScreenReturnStrategy.NO_REBUILD)
        r.takeBackCycle(touches = 0, keyFrames = 0)
        assertTrue(r.logs.contains("PASSAT: 策略 no_rebuild：拿回主屏车机没反应（连续 1 次）"))
        r.ownership.resetTakeBackFailures()
        r.takeBackCycle(touches = 0, keyFrames = 0)
        assertTrue("reset 后计数从头开始", r.logs.contains("PASSAT: 策略 no_rebuild：拿回主屏车机没反应（连续 1 次）"))
    }
}

private fun samplePolicy(
    screenReturn: ScreenReturnStrategy = ScreenReturnStrategy.OFF,
    reconnectOnStreamingInvite: Boolean = false,
): ReceiverCompatibility.Policy =
    ReceiverCompatibility
        .resolve(ReceiverProfile.Signals(), preset = VehiclePreset.GENERIC)
        .copy(screenReturn = screenReturn, reconnectOnStreamingInvite = reconnectOnStreamingInvite)
