package com.mcarplay.app.airplay

import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.compat.ScreenReturnPolicy
import com.mcarplay.app.compat.ScreenReturnStrategy
import com.mcarplay.app.net.StreamingInviteReconnect

/**
 * 屏幕归属（modesChanged 队列、资源重申、拿回主屏检查与回屏策略）从会话编排层抽出的组件。
 *
 * 边界（docs/ARCHITECTURE-REFACTOR.md Phase 2f）：
 *  - 只接管模式队列、burst 后的绝对时刻重申、拿回主屏后的 3 秒反应检查、回屏策略动作与推流中邀请防抖决策；
 *  - 所有会话线程、锁段、诊断、跨轮退役与视频恢复仍由编排层的窄端口实现（[ScreenOwnershipRuntime]）；
 *  - 不引用 SessionController、UI、settings、service 等上层，也不识别车型：只读中性 [ReceiverCompatibility.Policy]；
 *  - 视频恢复闭包本体与邀请重连的退役/端点重交付留在编排层（2g/后续阶段）。
 *
 * 组件实例与编排层同寿命，绝不每个 client 新建，也不因退役重置 pending 或防抖时刻。
 */
internal class ScreenOwnership<C : Any>(
    private val clients: ScreenOwnershipClient<C>,
    private val runtime: ScreenOwnershipRuntime<C>,
    private val scheduling: ScreenOwnershipScheduling,
    private val log: (String) -> Unit,
) {
    /**
     * 资源声明（modesChanged）的串行执行器：事件读取线程只负责接收、应答与投递。
     * assertModes 要等控制通道的 RTSP 回应，放在事件线程上会挡住同一连接后续的
     * 触摸/iAP 消息（2026-11-01 审查）。密集请求合并：已有一条待执行时不再排队。
     *
     * 单线程 daemon、线程名 carplay-control-actions 由生产侧的 [ScreenOwnershipScheduling.executeControl] 提供。
     */
    private data class ModesAction<C : Any>(
        val client: C,
        val reason: String,
        var requestKeyFrame: Boolean,
        var retakeScreen: Boolean,
        var rebuildStream: Boolean,
    )

    private val modesActionLock = Any()
    private var pendingModesAction: ModesAction<C>? = null

    /**
     * modesChanged 资源重申调度（MIB3 控制会话完成事件驱动）。
     *
     * AADisplay r17 在奥迪上长播验证的时序：auth+burst 之后 **+1.5s 首发、+4s/+12s/
     * +30s 各重申一次**（绝对时刻）。声明若落在 0xAA00/0xAA05 进行中，会与 iAP POST
     * 抢控制通道、奥迪 ~12s 后死（AADisplay 注释原话）——所以只能由 burst 完成事件
     * 触发，不能在会话建立时一次性发完。082446 定谳：第二控制会话会重置车机的资源
     * 仲裁，早先那份 200 OK 的声明到订阅簇完成时已经失效，车机在等一份新的。
     */
    @Volatile
    private var modesReclaimEpoch = 0L

    /** 连续「拿回主屏车机没反应」次数；车机有反应或新一轮连接时清零。 */
    @Volatile
    private var takeBackFailures = 0

    /** 上次「推流中车机邀请 → 重建会话」的 uptime 时刻（0 = 本次连接尚无；新连接时清零）。 */
    @Volatile
    private var lastInviteReconnectAtMillis = 0L

    private val inviteReconnectLock = Any()

    fun resetInviteReconnectAt() {
        lastInviteReconnectAtMillis = 0L
    }

    fun resetTakeBackFailures() {
        takeBackFailures = 0
    }

    fun scheduleModesReclaim(client: C) {
        val epoch = ++modesReclaimEpoch
        val connectionGeneration = clients.generation(client)
        scheduling.runBackground("modes-reclaim") reclaim@{
            val start = scheduling.uptimeMillis()
            for (atMs in longArrayOf(1_500, 4_000, 12_000, 30_000)) {
                while (true) {
                    if (epoch != modesReclaimEpoch ||
                        clients.isCancelled(client) ||
                        runtime.connectCancelled ||
                        !clients.isConnectionCurrent(client, connectionGeneration)
                    ) {
                        return@reclaim
                    }
                    val remain = atMs - (scheduling.uptimeMillis() - start)
                    if (remain <= 0) break
                    scheduling.sleepFor(client, minOf(remain, 200L))
                }
                if (epoch != modesReclaimEpoch ||
                    clients.isCancelled(client) ||
                    runtime.connectCancelled ||
                    !clients.isConnectionCurrent(client, connectionGeneration)
                ) {
                    return@reclaim
                }
                if (!clients.assertModes(client, releaseScreen = false, expectedEpoch = connectionGeneration)) return@reclaim
                log("AIRPLAY: modesChanged 资源重申（burst 后 +${atMs / 1000}s，AADisplay r17 时序）")
            }
        }
    }

    fun enqueueModesAssert(
        client: C,
        reason: String,
        requestKeyFrame: Boolean,
        retakeScreen: Boolean = false,
        rebuildStream: Boolean = false,
        takeBackRetried: Boolean = false,
    ) {
        val connectionGeneration = clients.generation(client)
        val action =
            synchronized(modesActionLock) {
                val pending = pendingModesAction
                if (pending?.client === client) {
                    pending.requestKeyFrame = pending.requestKeyFrame || requestKeyFrame
                    pending.retakeScreen = pending.retakeScreen || retakeScreen
                    pending.rebuildStream = pending.rebuildStream || rebuildStream
                    log("EVENT: $reason → 与待执行的 modesChanged 合并")
                    return
                }
                ModesAction(client, reason, requestKeyFrame, retakeScreen, rebuildStream).also { pendingModesAction = it }
            }
        scheduling.executeControl control@{
            val request =
                synchronized(modesActionLock) {
                    if (pendingModesAction === action) pendingModesAction = null
                    action.copy()
                }
            // 执行时会话可能已取消或换了 client：迟到任务不得对新会话发 modesChanged
            if (runtime.connectCancelled ||
                runtime.currentClient !== client ||
                !clients.isConnectionCurrent(client, connectionGeneration)
            ) {
                log("EVENT: $reason → 会话已结束或更替，丢弃迟到的 modesChanged")
                return@control
            }
            // 用户点图标时主屏本就归手机：车机停在原车界面却不认这个归属，先报让出、再报拿回
            val rebuildOnResourceReturn = runtime.receiverPolicy(client).rebuildScreenOnResourceReturn
            if (request.retakeScreen &&
                clients.phoneOwnsScreen(client) &&
                !(rebuildOnResourceReturn && clients.screenNeedsRebuild(client))
            ) {
                if (clients.assertModes(client, releaseScreen = true, expectedEpoch = connectionGeneration)) {
                    try {
                        scheduling.sleepFor(client, SCREEN_RETAKE_GAP_MS)
                    } catch (_: InterruptedException) {
                        return@control
                    }
                }
            }
            if (runtime.connectCancelled ||
                runtime.currentClient !== client ||
                !clients.isConnectionCurrent(client, connectionGeneration)
            ) {
                return@control
            }
            val asserted = clients.assertModes(client, releaseScreen = false, expectedEpoch = connectionGeneration)
            if (runtime.connectCancelled ||
                runtime.currentClient !== client ||
                !clients.isConnectionCurrent(client, connectionGeneration)
            ) {
                return@control
            }
            val rebuildScreen = if (rebuildOnResourceReturn) clients.screenNeedsRebuild(client) else request.rebuildStream
            val restoreScreen =
                (request.requestKeyFrame || (rebuildOnResourceReturn && rebuildScreen)) && asserted && clients.phoneOwnsScreen(client)
            val recovery = runtime.readRecovery()
            if (restoreScreen && rebuildScreen && recovery != null) {
                when (recovery()) {
                    RecoveryOutcome.ABORTED -> return@control
                    RecoveryOutcome.FAILED -> {
                        runtime.onRecoveryFailed(client)
                        return@control
                    }
                    RecoveryOutcome.RESTORED -> Unit
                }
            }
            if (runtime.connectCancelled ||
                clients.isCancelled(client) ||
                runtime.currentClient !== client ||
                !clients.isConnectionCurrent(client, connectionGeneration)
            ) {
                return@control
            }
            if (restoreScreen && clients.phoneOwnsScreen(client)) {
                runtime.reprime()
                scheduleTakeBackCheck(client, reason, takeBackRetried)
            }
            // 自己补的 I 帧不计入车机要帧风暴（用户连点图标不能被当成解码失败）
            val keyFrame = restoreScreen && clients.phoneOwnsScreen(client) && runtime.requestLocalKeyFrame()
            log(
                "EVENT: $reason → modesChanged " + (if (asserted) "已应答" else "失败") +
                    if (request.requestKeyFrame) "，关键帧" + (if (keyFrame) "已请求" else "未就绪") else "",
            )
        }
    }

    /**
     * 拿回主屏 3 s 后记一笔车机反应：有触控 = 已切到 CarPlay；无触控也无要帧 = 车机没理这次归属变化。
     * 帕萨特回原车主界面后再点图标属于后者（2026-11-01 23:22），需要据此区分是仲裁还是解码问题。
     */
    private fun scheduleTakeBackCheck(
        client: C,
        reason: String,
        alreadyRetried: Boolean,
    ) {
        val touchesBefore = runtime.touchCount
        val keyFramesBefore = runtime.forceKeyFrameCount
        scheduling.runBackground("takeback-check") check@{
            try {
                scheduling.sleepFor(client, TAKE_BACK_CHECK_MS)
            } catch (_: InterruptedException) {
                return@check
            }
            if (runtime.currentClient !== client) return@check
            log(
                "SESSION: 拿回主屏 ${TAKE_BACK_CHECK_MS}ms 后：车机触控 +${runtime.touchCount - touchesBefore} 条、" +
                    "要关键帧 +${runtime.forceKeyFrameCount - keyFramesBefore} 次（${clients.modesDescription(client)}；起因 $reason）",
            )
            applyScreenReturnStrategy(
                client,
                ScreenReturnPolicy.takeBackFailed(runtime.touchCount - touchesBefore, runtime.forceKeyFrameCount - keyFramesBefore),
                alreadyRetried,
            )
        }
    }

    /** 回屏策略的失败后续动作；用户未选择策略（OFF）时什么都不做。 */
    private fun applyScreenReturnStrategy(
        client: C,
        failed: Boolean,
        alreadyRetried: Boolean,
    ) {
        val strategy = runtime.screenReturnPolicy(client).screenReturn
        if (strategy == ScreenReturnStrategy.OFF) return
        if (!failed) {
            takeBackFailures = 0
            return
        }
        val failures = ++takeBackFailures
        if (ScreenReturnPolicy.retryRebuild(strategy, failed, alreadyRetried)) {
            log("PASSAT: 策略「失败后重建一次」——拿回主屏车机没反应，重建主屏流重试")
            enqueueModesAssert(
                client,
                "帕萨特策略：拿回主屏无反应，重建重试",
                requestKeyFrame = true,
                retakeScreen = true,
                rebuildStream = true,
                takeBackRetried = true,
            )
        } else if (ScreenReturnPolicy.disconnect(strategy, failures)) {
            log("PASSAT: 策略「连续失败后断开」——连续 $failures 次拿回主屏车机没反应，结束本轮，请重新连接")
            runtime.onTakeBackDisconnect(client)
        } else {
            log("PASSAT: 策略 ${strategy.key}：拿回主屏车机没反应（连续 $failures 次）")
        }
    }

    /**
     * 车机 ctrl 邀请的推流分支：已登记的车机（[ReceiverCompatibility.Policy.reconnectOnStreamingInvite]）
     * 退役当前会话并按邀请重连，带 [StreamingInviteReconnect.DEBOUNCE_MS] 防抖。
     *
     * 非推流（或新一轮正在建立）不读取策略与时钟，直接交给连接流程；连接期的 Wi-Fi 邀请消费不在此处。
     */
    fun onStreamingInvite(client: C) {
        val streaming = !runtime.connectCancelled && !runtime.sessionStarting && runtime.videoRunning
        if (!streaming) return
        val now = scheduling.uptimeMillis()
        val decision =
            synchronized(inviteReconnectLock) {
                StreamingInviteReconnect
                    .decide(
                        streaming = true,
                        enabled = runtime.receiverPolicy(client).reconnectOnStreamingInvite,
                        nowMillis = now,
                        lastReconnectAtMillis = lastInviteReconnectAtMillis,
                    ).also { if (it == StreamingInviteReconnect.Decision.RECONNECT) lastInviteReconnectAtMillis = now }
            }
        when (decision) {
            StreamingInviteReconnect.Decision.NOT_STREAMING -> Unit
            StreamingInviteReconnect.Decision.NOT_ENABLED ->
                log("CTRL: 推流中收到车机邀请——该车机未登记邀请重连，保持当前会话")
            StreamingInviteReconnect.Decision.DEBOUNCED ->
                log(
                    "CTRL: 推流中收到车机邀请——距上次邀请重连 ${now - lastInviteReconnectAtMillis}ms，" +
                        "不足 ${StreamingInviteReconnect.DEBOUNCE_MS / 1000}s，忽略",
                )
            StreamingInviteReconnect.Decision.RECONNECT ->
                scheduling.runBackground("streaming-invite-reconnect") { runtime.reconnectStreaming(client) }
        }
    }

    private companion object {
        /** 让出与拿回两条 modesChanged 的间隔：给车机 HMI 处理一次归属切换的时间。 */
        private const val SCREEN_RETAKE_GAP_MS = 200L

        /** 拿回主屏后多久检查车机是否有触控/要帧。 */
        private const val TAKE_BACK_CHECK_MS = 3_000L
    }
}

/** 屏幕归属组件执行恢复闭包后的中性结果；原 ScreenRecoveryResult 与闭包仍留会话编排层（2g 边界）。 */
internal enum class RecoveryOutcome {
    RESTORED,
    ABORTED,
    FAILED,
}

/** 对同一个 CarPlay 客户端的身份与模式操作；客户端对象本体即身份，比较仍用 `===`。 */
internal interface ScreenOwnershipClient<C : Any> {
    fun generation(client: C): Long

    fun isConnectionCurrent(
        client: C,
        generation: Long,
    ): Boolean

    fun isCancelled(client: C): Boolean

    fun phoneOwnsScreen(client: C): Boolean

    fun screenNeedsRebuild(client: C): Boolean = false

    fun modesDescription(client: C): String

    fun assertModes(
        client: C,
        releaseScreen: Boolean,
        expectedEpoch: Long,
    ): Boolean
}

/**
 * 编排层的活读端口：每个 getter 都在原读取点活读，构造时不触碰会话、sender、context 或计数。
 *
 * [receiverPolicy] 原样返回客户端兼容策略，供邀请防抖使用，**不读取用户偏好**；
 * [screenReturnPolicy] 才叠加动态回屏偏好，只供原回屏策略读取点使用，两者不可合并。
 */
internal interface ScreenOwnershipRuntime<C : Any> {
    val currentClient: C?
    val connectCancelled: Boolean
    val sessionStarting: Boolean
    val videoRunning: Boolean
    val touchCount: Int
    val forceKeyFrameCount: Int

    fun readRecovery(): (() -> RecoveryOutcome)?

    fun reprime()

    fun requestLocalKeyFrame(): Boolean

    fun onRecoveryFailed(client: C)

    fun onTakeBackDisconnect(client: C)

    fun reconnectStreaming(client: C)

    fun receiverPolicy(client: C): ReceiverCompatibility.Policy

    fun screenReturnPolicy(client: C): ReceiverCompatibility.Policy
}

/** 线程与时钟端口：生产侧绑定单线程控制队列、Bg.run、单调 uptime 与 Thread.sleep。 */
internal interface ScreenOwnershipScheduling {
    fun executeControl(task: () -> Unit)

    fun runBackground(
        name: String,
        task: () -> Unit,
    )

    fun uptimeMillis(): Long

    fun sleep(millis: Long)

    /** 按客户端所属轮次可取消休眠；默认回退 [sleep] 以兼容既有纯 JVM 测试替身。 */
    fun sleepFor(
        client: Any,
        millis: Long,
    ) = sleep(millis)
}
