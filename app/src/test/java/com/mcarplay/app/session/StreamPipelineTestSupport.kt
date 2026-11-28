package com.mcarplay.app.session

import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.airplay.StreamFrameCipher
import com.mcarplay.app.audio.CarLifeAudioBridge
import com.mcarplay.app.carlife.CarLifeSession
import com.mcarplay.app.carlife.CarLifeVideoAccessUnit
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.video.ExternalVideoSource
import com.mcarplay.app.video.VideoSender
import java.util.concurrent.atomic.AtomicBoolean

/** [StreamPipeline] 的测试替身端口：记录日志、时间线与模式重申，不在装配期触碰 Android。 */
internal class FakeStreamPorts : CarLifeStreamPorts {
    val logs = mutableListOf<String>()
    val notes = mutableListOf<Pair<String, String>>()
    val media = mutableListOf<Triple<String, Boolean, Int>>()
    val finishes = mutableListOf<Finish>()
    var sourceFrames = 0
    var client: AirPlayClient? = null
    var bridge: CarLifeAudioBridge? = null

    data class Finish(
        val attempt: String,
        val code: DiagnosticCode,
        val failed: Boolean,
        val detail: String,
    )

    override fun log(line: String) {
        logs += line
    }

    override fun timelineSourceFrame(attempt: String) {
        sourceFrames++
    }

    override fun timelineNote(
        attempt: String,
        note: String,
    ) {
        notes += attempt to note
    }

    override fun timelineMedia(
        attempt: String,
        video: Boolean,
        bytes: Int,
    ) {
        media += Triple(attempt, video, bytes)
    }

    override fun timelineFinish(
        attempt: String,
        code: DiagnosticCode,
        failed: Boolean,
        detail: String,
    ) {
        finishes += Finish(attempt, code, failed, detail)
    }

    override fun currentClient(): AirPlayClient? = client

    override fun enqueueModesAssert(
        client: AirPlayClient,
        reason: String,
        requestKeyFrame: Boolean,
    ) {
        // 2g-1 不驱动回原车路径：需要 AirPlayClient 真实实例，留待 2g-2 的恢复测试。
    }

    override fun nativeBridge(): CarLifeAudioBridge? = bridge

    /** 测试里复用同一把监视器，验证恢复闭包确实经端口加锁而不是裸 synchronized(this)。 */
    val controllerLock = Any()
    var lockCalls = 0

    override fun <T> withControllerLock(block: () -> T): T =
        synchronized(controllerLock) {
            lockCalls++
            block()
        }
}

/** 2g-1 单测共用的 H.264 样本与 sender/session 构造。 */
internal object StreamPipelineTestSupport {
    /** 1280×720 baseline SPS（与 CarLifeVideoParserTest 同源，`spsDimensions` 可解析）。 */
    val sps720: ByteArray =
        byteArrayOf(
            0x67,
            0x42,
            0x80.toByte(),
            0x1F,
            0xDA.toByte(),
            0x01,
            0x40,
            0x16,
            0xE9.toByte(),
            0xA8.toByte(),
            0x28,
            0x30,
        )
    val pps: ByteArray = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    val idr: ByteArray = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
    val slice: ByteArray = byteArrayOf(0x41, 0x9A.toByte())

    fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }

    fun accessUnit(
        data: ByteArray,
        keyFrame: Boolean,
        sps: ByteArray? = sps720,
        ppsBytes: ByteArray? = pps,
    ): CarLifeVideoAccessUnit = CarLifeVideoAccessUnit(timestamp = 1, data = data, keyFrame = keyFrame, sps = sps, pps = ppsBytes)

    fun idrUnit(): CarLifeVideoAccessUnit = accessUnit(annexB(sps720, pps, idr), keyFrame = true)

    /** 真实 [VideoSender]（JVM 可构造）；[running] 控制 `submitExternalFrame` 是否接受帧。 */
    fun sender(running: Boolean = false): VideoSender {
        val sender =
            VideoSender(
                host = "127.0.0.1",
                dataPort = 9,
                frameCipher = StreamFrameCipher.AesCtr(ByteArray(16), ByteArray(16)),
                width = 1280,
                height = 720,
                targetFps = 30,
                log = {},
                externalVideoSource =
                    object : ExternalVideoSource {
                        override fun requestKeyFrame(): Boolean = true
                    },
            )
        if (running) setSenderRunning(sender, true)
        return sender
    }

    fun setSenderRunning(
        sender: VideoSender,
        value: Boolean,
    ) {
        val field = VideoSender::class.java.getDeclaredField("running").apply { isAccessible = true }
        (field.get(sender) as AtomicBoolean).set(value)
    }

    fun setSessionRunning(
        session: CarLifeSession,
        value: Boolean,
    ) {
        val field = CarLifeSession::class.java.getDeclaredField("running").apply { isAccessible = true }
        (field.get(session) as AtomicBoolean).set(value)
    }

    fun cipher(): StreamFrameCipher = StreamFrameCipher.AesCtr(ByteArray(16), ByteArray(16))

    /** 最小 [AirPlayClient]（不连接网络）：2g-2 恢复闭包只把它当作本轮身份令牌。 */
    fun client(): AirPlayClient {
        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                "test",
                "",
                0,
                0,
                1,
                null,
                null,
                null,
                listOf("127.0.0.1"),
            )
        return AirPlayClient(endpoint, log = {})
    }

    fun plan(
        hooks: ScreenSenderHooks,
        width: Int = 1280,
        height: Int = 720,
    ): ScreenSenderPlan =
        ScreenSenderPlan(
            host = "127.0.0.1",
            width = width,
            height = height,
            targetFps = 30,
            keepAliveWithStats = false,
            receiverMaxFps = 30,
            externalVideoSource =
                object : ExternalVideoSource {
                    override fun requestKeyFrame(): Boolean = true
                },
            phoneOwnsScreen = { true },
            hooks = hooks,
        )
}

/** 记录发送端回调的测试钩子。 */
internal class RecordingScreenHooks : ScreenSenderHooks {
    val suspended = mutableListOf<Boolean>()
    val frames = mutableListOf<Int>()
    val ended = mutableListOf<VideoSender>()
    val configs = mutableListOf<Pair<Int, Int>>()

    override fun onScreenOutputSuspended(suspended: Boolean) {
        this.suspended += suspended
    }

    override fun newVideoFrameObserver(): (bytes: Int) -> Unit = { bytes -> frames += bytes }

    override fun newVideoConfigObserver(): ((width: Int, height: Int) -> Unit)? = { width, height -> configs += width to height }

    override fun onEnded(sender: VideoSender) {
        ended += sender
    }
}

/** 可编排的恢复端口：记录调用顺序，按字段返回各叶子的结果。 */
internal class FakeRecoveryPort : ScreenRecoveryPort {
    var scopeActive = true
    var currentClientMatches = true
    var clientCancelled = false
    var phoneOwns = true
    var teardownResult = true
    var recordResult = true
    var screenInfo: AirPlayClient.ScreenStreamInfo? = null

    val calls = mutableListOf<String>()
    var onTeardown: (() -> Unit)? = null
    var onRun: (() -> Unit)? = null

    override fun isScopeActive(): Boolean = scopeActive

    override fun isCurrentClient(client: AirPlayClient): Boolean = currentClientMatches

    override fun isClientCancelled(client: AirPlayClient): Boolean = clientCancelled

    override fun phoneOwnsScreen(client: AirPlayClient): Boolean = phoneOwns

    override fun teardownScreenStream(client: AirPlayClient): Boolean {
        calls += "teardown"
        onTeardown?.invoke()
        return teardownResult
    }

    override fun runScreenStream(
        client: AirPlayClient,
        sessionKey: ByteArray?,
        displayUuid: String?,
    ): AirPlayClient.ScreenStreamInfo? {
        calls += "run"
        onRun?.invoke()
        return screenInfo
    }

    override fun record(client: AirPlayClient): Boolean {
        calls += "record"
        return recordResult
    }

    override fun onScreenStreamRestored(client: AirPlayClient) {
        calls += "restored"
    }
}
