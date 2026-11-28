package com.mcarplay.app.session

import android.app.Application
import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.audio.CarLifeAudioBridge
import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeChannel
import com.mcarplay.app.carlife.CarLifeJoinGate
import com.mcarplay.app.carlife.CarLifeLaunchStrategy
import com.mcarplay.app.carlife.CarLifeSession
import com.mcarplay.app.carlife.CarLifeSessionRegistry
import com.mcarplay.app.carlife.CarLifeVideoAccessUnit
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.video.ExternalVideoSource
import com.mcarplay.app.video.VideoSender

/**
 * Phase 2g：正式 CarLife 源、会话租约、输出去向、主屏发送端与主屏流重建的编排组件。
 *
 * 2g-1 承接正式 CarLife 会话、租约与 [CarLifeOutput]；2g-2 再承接发送端槽
 * （[sender]）、[newScreenSender] 工厂与 [installRecovery] 重建闭包。
 *
 * 边界（见 docs/ARCHITECTURE-REFACTOR.md）：
 *  - 组件不引用 `SessionController` / Activity / `service` / `ui` / `settings` / `SessionUiPort`；
 *    一切日志、时间线、当前 client、音频桥与模式重申都经 [CarLifeStreamPorts] 注入。
 *  - 租约互斥仍用 `synchronized(registry)`（与基线同一监视器），不引入新锁。
 *  - 主屏流重建闭包原在控制器 4 处 `synchronized(this)`；迁入后一律经 [CarLifeStreamPorts.withControllerLock]
 *    回到控制器监视器，组件内不出现裸 `synchronized(this)`。
 *  - B-18：旧 CarLife 源的迟到 `onEnded` 只记「未接入推流的旧会话结束」并释放它自己的租约，
 *    绝不停止新一轮的发送端。
 *
 * 事件处理拆成 [handleAccessUnit] / [handleEnded] / [handleAudioEvent] / [handleReturnToCar]
 * 仅供 JVM 单测直接驱动；生产路径由 [newFormalSession] 的构造回调调用同一实现。
 */
internal class StreamPipeline(
    private val ports: CarLifeStreamPorts,
) {
    private val registry = CarLifeSessionRegistry()

    /** Formal CarPlay session currently bridging Xiaomi CarWith. */
    @Volatile
    private var formal: CarLifeSession? = null

    /** Standalone CarWith self-test session (never submitted to CarPlay). */
    @Volatile
    private var diagnostic: CarLifeSession? = null

    /** 正式 CarLife 源本轮的输出去向；null = 发送端尚未启动，帧直接丢弃。 */
    @Volatile
    private var output: CarLifeOutput? = null

    /** 当前主屏发送端（含恢复重建后的 replacement）。null = 本轮尚未建立屏幕流。 */
    @Volatile
    var sender: VideoSender? = null

    /** 主屏流重建闭包；由 [installRecovery] 安装、`RoundRetirement` 清除。 */
    @Volatile
    private var recovery: (() -> ScreenRecoveryResult)? = null

    fun isFormalActive(): Boolean = formal?.isRunning == true

    fun currentFormalSession(): CarLifeSession? = formal

    fun runningDiagnosticSession(): CarLifeSession? = diagnostic?.takeIf { it.isRunning }

    fun claim(
        owner: CarLifeSessionRegistry.Owner,
        session: CarLifeSession,
    ): Boolean =
        synchronized(registry) {
            if (!registry.acquire(owner, session.lease)) return@synchronized false
            when (owner) {
                CarLifeSessionRegistry.Owner.FORMAL -> formal = session
                CarLifeSessionRegistry.Owner.DIAGNOSTIC -> diagnostic = session
            }
            true
        }

    fun release(session: CarLifeSession): Boolean =
        synchronized(registry) {
            if (!registry.release(session.lease)) return@synchronized false
            if (formal === session) formal = null
            if (diagnostic === session) diagnostic = null
            true
        }

    fun stopFormal() {
        output = null
        currentFormalSession()?.let {
            it.stop()
            release(it)
        }
        synchronized(registry) {
            formal = null
        }
    }

    fun currentOutput(): CarLifeOutput? = output

    fun outputSender(): VideoSender? = output?.sender

    fun publishOutput(
        attempt: String,
        sender: VideoSender,
    ) {
        output = CarLifeOutput(attempt, sender)
    }

    fun clearOutput() {
        output = null
    }

    fun clearOutputIf(sender: VideoSender) {
        if (output?.sender === sender) output = null
    }

    fun recoveryOrNull(): (() -> ScreenRecoveryResult)? = recovery

    fun clearRecovery() {
        recovery = null
    }

    /**
     * 构造主屏发送端。尺寸/几何/fps、诊断、实验与环境采样均已由控制器算好并经 [ScreenSenderPlan] 传入；
     * 组件只按此计划创建 [VideoSender]，恢复重建时用同一计划配合新的 [AirPlayClient.ScreenStreamInfo] 再建一次。
     */
    fun newScreenSender(
        plan: ScreenSenderPlan,
        screenInfo: AirPlayClient.ScreenStreamInfo,
    ): VideoSender {
        lateinit var created: VideoSender
        created =
            VideoSender(
                host = plan.host,
                dataPort = screenInfo.dataPort,
                frameCipher = screenInfo.cipher,
                // 用收敛后的尺寸：实验面板不得把编码器画布推出资源上限。
                width = plan.width,
                height = plan.height,
                targetFps = plan.targetFps,
                log = { line -> ports.log(line) },
                keepAliveWithStats = plan.keepAliveWithStats,
                externalVideoSource = plan.externalVideoSource,
                onVideoFrameSent = plan.hooks.newVideoFrameObserver(),
                receiverMaxFps = plan.receiverMaxFps,
                transcodeFromStart = plan.transcodeFromStart,
                // 车机拿走主屏（原车界面、倒车影像等）时暂停屏幕视频，归还后从新的 IDR 恢复
                phoneOwnsScreen = plan.phoneOwnsScreen,
                onScreenOutputSuspended = { suspended -> plan.hooks.onScreenOutputSuspended(suspended) },
                onEnded = { plan.hooks.onEnded(created) },
            )
        // 实验（issue #62，PR #78 评审 P2）：只在 VideoConfig 真正写出后判定，且尺寸变化时重新判定。
        // 观察器按 sender 各取一份，保留「最近上报尺寸」的逐 sender 语义。
        plan.hooks.newVideoConfigObserver()?.let { observer -> created.onVideoConfigSent = observer }
        plan.hooks.onSenderCreated(created)
        return created
    }

    /**
     * 安装主屏流重建闭包。在同一正式 CarLife 会话内，允许资源归还策略接续被车机借屏打断的重建。
     *
     * 原实现 4 处 `synchronized(this)`（`this` = SessionController 单例）改经 [CarLifeStreamPorts.withControllerLock]，
     * 锁身份保持为控制器监视器。
     */
    fun installRecovery(
        plan: ScreenSenderPlan,
        client: AirPlayClient,
        carLife: () -> CarLifeSession?,
        sessionKey: ByteArray?,
        displayUuid: String?,
        diagnosticAttempt: String,
        port: ScreenRecoveryPort,
        rebuildOnResourceReturn: Boolean = false,
    ) {
        recovery =
            recover@{
                fun canRestore(): Boolean =
                    port.isScopeActive() &&
                        !port.isClientCancelled(client) &&
                        port.isCurrentClient(client) &&
                        port.phoneOwnsScreen(client)

                fun failure(): ScreenRecoveryResult =
                    if (!canRestore()) {
                        ScreenRecoveryResult.ABORTED
                    } else {
                        ScreenRecoveryResult.FAILED
                    }
                var previousSender: VideoSender? = null
                val detached =
                    ports.withControllerLock {
                        val session = carLife()
                        val previous = sender
                        if (!canRestore() ||
                            session == null ||
                            currentFormalSession() !== session ||
                            output?.sender !== previous ||
                            (previous == null && !rebuildOnResourceReturn)
                        ) {
                            return@withControllerLock false
                        }
                        output = null
                        sender = null
                        previousSender = previous
                        true
                    }
                if (!detached) return@recover ScreenRecoveryResult.ABORTED
                ports.log(
                    if (rebuildOnResourceReturn) {
                        "SCREEN: 回原车界面后拿回主屏——仅重建主屏流，保留 CarWith 与音频"
                    } else {
                        "SCREEN: 回原车界面后的 requestUI——仅重建主屏流，保留 CarWith 与音频"
                    },
                )
                previousSender?.stop()
                if (!canRestore() || !port.teardownScreenStream(client)) return@recover failure()
                if (!canRestore()) return@recover ScreenRecoveryResult.ABORTED
                val resumed = port.runScreenStream(client, sessionKey, displayUuid) ?: return@recover failure()
                if (!canRestore()) return@recover ScreenRecoveryResult.ABORTED
                val replacement = newScreenSender(plan, resumed)
                var restored = false
                try {
                    val published =
                        ports.withControllerLock {
                            if (!canRestore()) {
                                false
                            } else {
                                sender = replacement
                                true
                            }
                        }
                    if (!published) return@recover ScreenRecoveryResult.ABORTED
                    if (!replacement.openDataPort() ||
                        !canRestore() ||
                        !port.record(client) ||
                        !canRestore() ||
                        !replacement.start {} ||
                        !canRestore()
                    ) {
                        return@recover failure()
                    }
                    val confirmed =
                        ports.withControllerLock {
                            if (!canRestore() || currentFormalSession() !== carLife() || sender !== replacement) {
                                false
                            } else {
                                publishOutput(diagnosticAttempt, replacement)
                                true
                            }
                        }
                    if (!confirmed) return@recover ScreenRecoveryResult.ABORTED
                    port.onScreenStreamRestored(client)
                    replacement.requestKeyFrame(fromReceiver = false)
                    restored = true
                    ports.log("SCREEN: 主屏流已重新 SETUP/RECORD，等待新的 IDR 与触控")
                    ScreenRecoveryResult.RESTORED
                } finally {
                    if (!restored) {
                        ports.withControllerLock {
                            if (sender === replacement) sender = null
                            clearOutputIf(replacement)
                        }
                        replacement.stop()
                    }
                }
            }
    }

    /** 正式会话的 CarLife 源：输出按当前 [output] 路由，旧轮迟到回调被隔离。 */
    fun newFormalSession(
        application: Application,
        requestedVideoSize: Pair<Int, Int>? = null,
        preferVehicleResolution: Boolean = false,
    ): CarLifeSession {
        lateinit var session: CarLifeSession
        session =
            CarLifeSession(
                application = application,
                onAccessUnit = { unit -> handleAccessUnit(session, unit) },
                onEnded = { reason -> handleEnded(session, reason) },
                onAudioEvent = { channel, event -> handleAudioEvent(session, channel, event) },
                log = { line -> ports.log(line) },
                requestedVideoSize = requestedVideoSize,
                preferVehicleResolution = preferVehicleResolution,
                launchStrategy = CarLifeLaunchStrategy.COMPATIBLE,
                onReturnToCar = { handleReturnToCar(session) },
            )
        return session
    }

    internal fun handleAccessUnit(
        session: CarLifeSession,
        unit: CarLifeVideoAccessUnit,
    ) {
        if (formal !== session) return
        val out = output ?: return
        val joinsBefore = out.gate.joins
        val frame = out.gate.admit(unit) ?: return
        if (out.gate.joins != joinsBefore) {
            ports.log(
                "CARLIFE: 入流门控第 ${out.gate.joins} 次接入（此前等 IDR 丢弃 ${out.gate.droppedBeforeLastJoin} 帧，" +
                    "补参数集=${out.gate.filledAtLastJoin.ifEmpty { "无（帧内已带齐）" }}）",
            )
        }
        ports.timelineSourceFrame(out.attempt)
        val sender = out.sender
        val dimensions = frame.dimensions()
        if (dimensions != null && sender.isNewSourceSize(dimensions.first, dimensions.second)) {
            ports.timelineNote(
                out.attempt,
                "CARWITH_VIDEO_FORMAT ${dimensions.first}x${dimensions.second}",
            )
            sender.setVideoDimensions(dimensions.first, dimensions.second)
            ports.log("CARLIFE: 实际视频尺寸 ${dimensions.first}x${dimensions.second}")
        }
        if (!sender.submitExternalFrame(frame.toExternalVideoFrame())) out.gate.reset()
    }

    internal fun handleEnded(
        session: CarLifeSession,
        reason: String,
    ) {
        // 旧源的迟到回调不能停掉新一轮的发送端
        val out = output.takeIf { formal === session }
        if (out != null) {
            ports.timelineFinish(out.attempt, session.endDiagnosticCode, true, reason)
            ports.log("CARLIFE: 会话结束 - $reason")
            ports.nativeBridge()?.stop()
            out.sender.stop()
        } else {
            ports.log("CARLIFE: 未接入推流的旧会话结束 - $reason")
        }
        release(session)
    }

    internal fun handleAudioEvent(
        session: CarLifeSession,
        channel: CarLifeChannel,
        event: CarLifeAudioEvent,
    ) {
        if (formal !== session) return
        val attempt = output?.attempt
        if (attempt != null && event is CarLifeAudioEvent.Init) {
            ports.timelineNote(
                attempt,
                "CARWITH_AUDIO_FORMAT channel=$channel rate=${event.sampleRate} " +
                    "channels=${event.channels} bits=${event.bitsPerSample}",
            )
        }
        if (attempt != null && event is CarLifeAudioEvent.Pcm) {
            ports.timelineMedia(attempt, false, event.littleEndian.size)
        }
        ports.nativeBridge()?.submit(channel, event)
    }

    internal fun handleReturnToCar(session: CarLifeSession) {
        if (formal !== session) return
        val client = ports.currentClient() ?: return
        val note = client.modes.onUserReturnedToCar()
        ports.enqueueModesAssert(client, "CarWith 返回车机（→ $note）", requestKeyFrame = false)
    }
}

/** 正式 CarLife 源本轮的输出去向；[sender] 是接收 CarWith 帧的发送端。 */
internal class CarLifeOutput(
    val attempt: String,
    val sender: VideoSender,
) {
    val gate = CarLifeJoinGate()
}

/** 主屏流重建结果：成功 / 因轮次或绑定失效而放弃 / 真正失败。 */
internal enum class ScreenRecoveryResult { RESTORED, ABORTED, FAILED }

/**
 * 主屏发送端的构造计划：只含控制器已算好的值（尺寸/几何/fps/心跳/帧率上限/视频源/主屏归属），
 * 具体数据端口与帧加密随每次 [AirPlayClient.ScreenStreamInfo] 传入。
 */
internal class ScreenSenderPlan(
    val host: String,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val keepAliveWithStats: Boolean,
    val receiverMaxFps: Int,
    val transcodeFromStart: Boolean = false,
    val externalVideoSource: ExternalVideoSource,
    val phoneOwnsScreen: () -> Boolean,
    val hooks: ScreenSenderHooks,
)

/**
 * 发送端回调控件：诊断、S3 收尾、实验判定与环境采样都留在控制器，经此注入。
 * 三个 `new*Observer` 按发送端实例各取一份，保留逐 sender 的 `firstFrameSent` / `lastReported` 语义。
 */
internal interface ScreenSenderHooks {
    fun onScreenOutputSuspended(suspended: Boolean)

    fun newVideoFrameObserver(): (bytes: Int) -> Unit

    /** 实验面板的 VideoConfig 上报观察器；未启用实验面板时返回 null（不挂回调）。 */
    fun newVideoConfigObserver(): ((width: Int, height: Int) -> Unit)?

    /** 记录本次发送端实例建立时的网络与邀请基线，供 [onEnded] 判断断流重连。 */
    fun onSenderCreated(sender: VideoSender) {}

    /** 推流自然结束；[sender] 是真正结束的发送端实例（陈旧判定依赖它）。 */
    fun onEnded(sender: VideoSender)
}

/**
 * 主屏流重建所需的客户端叶子与轮次判据。
 *
 * 全部由控制器按本轮 attempt 装配：`scope.isActive`、`currentClient === client`、`client.cancelled`、
 * `client.modes.phoneOwnsScreen`，以及 teardown/run/record/restored 四个屏幕流叶子。
 */
internal interface ScreenRecoveryPort {
    fun isScopeActive(): Boolean

    fun isCurrentClient(client: AirPlayClient): Boolean

    fun isClientCancelled(client: AirPlayClient): Boolean

    fun phoneOwnsScreen(client: AirPlayClient): Boolean

    fun teardownScreenStream(client: AirPlayClient): Boolean

    fun runScreenStream(
        client: AirPlayClient,
        sessionKey: ByteArray?,
        displayUuid: String?,
    ): AirPlayClient.ScreenStreamInfo?

    fun record(client: AirPlayClient): Boolean

    fun onScreenStreamRestored(client: AirPlayClient)
}

/**
 * [StreamPipeline] 依赖的窄端口：控制器提供日志、诊断时间线、当前 client、音频桥与模式重申。
 *
 * 全部使用中性命名，组件因此不反向依赖编排层或界面层。
 */
internal interface CarLifeStreamPorts {
    fun log(line: String)

    fun timelineSourceFrame(attempt: String)

    fun timelineNote(
        attempt: String,
        note: String,
    )

    fun timelineMedia(
        attempt: String,
        video: Boolean,
        bytes: Int,
    )

    fun timelineFinish(
        attempt: String,
        code: DiagnosticCode,
        failed: Boolean,
        detail: String,
    )

    fun currentClient(): AirPlayClient?

    fun enqueueModesAssert(
        client: AirPlayClient,
        reason: String,
        requestKeyFrame: Boolean,
    )

    fun nativeBridge(): CarLifeAudioBridge?

    /**
     * 回到控制器监视器（生产绑定 `synchronized(this@SessionController)`）。
     * 主屏流重建闭包原 4 处 `synchronized(this)` 依赖这把锁；迁入组件后必须显式注入，不得改用组件自身监视器。
     */
    fun <T> withControllerLock(block: () -> T): T
}
