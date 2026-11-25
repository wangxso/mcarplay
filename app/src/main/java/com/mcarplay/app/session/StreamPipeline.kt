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
import com.mcarplay.app.video.VideoSender

/**
 * Phase 2g-1：正式 CarLife 源、会话租约与输出去向的编排组件。
 *
 * 从 `SessionController` 抽出的第一半：构造正式 CarLife 会话、路由它的事件、持有
 * [CarLifeSessionRegistry] 租约与正式输出去向（[CarLifeOutput]）。发送端工厂
 * （`newScreenSender`）与主屏流重建闭包（`screenStreamRecovery`）仍留在控制器，属 Phase 2g-2。
 *
 * 边界（见 docs/ARCHITECTURE-REFACTOR.md）：
 *  - 组件不引用 `SessionController` / Activity / `service` / `ui` / `settings` / `SessionUiPort`；
 *    一切日志、时间线、当前 client、音频桥与模式重申都经 [CarLifeStreamPorts] 注入。
 *  - 租约互斥仍用 `synchronized(registry)`（与基线同一监视器），不引入新锁。
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
}
