package com.mcarplay.app.session

import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.airplay.StreamFrameCipher
import com.mcarplay.app.audio.CarLifeAudioBridge
import com.mcarplay.app.carlife.CarLifeSession
import com.mcarplay.app.carlife.CarLifeVideoAccessUnit
import com.mcarplay.app.diagnostics.DiagnosticCode
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
}
