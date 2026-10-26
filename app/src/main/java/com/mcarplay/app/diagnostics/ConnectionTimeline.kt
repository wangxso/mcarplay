package com.mcarplay.app.diagnostics

import org.json.JSONObject
import java.util.UUID

enum class ConnectionStage(
    val label: String,
) {
    PRECHECK("连接准备"),
    BLUETOOTH("等待车机蓝牙"),
    WIFI("连接车机网络"),
    DISCOVERY("解析接收端"),
    PAIRING("AirPlay 配对"),
    SETUP("建立 CarPlay 会话"),
    CARWITH("等待 CarWith 首帧"),
    STREAMING("传输画面"),
    SELF_TEST("CarWith 独立自检（非 CarPlay）"),
}

enum class DiagnosticCode(
    val advice: String,
) {
    BT_PERMISSION("请允许附近设备/蓝牙权限后重试"),
    BT_DISABLED("请开启蓝牙，并确认已与接收端配对"),
    BT_LINK_CLOSED("建立会话前蓝牙控制链路已结束，请检查接收端状态后重连"),
    ENDPOINT_PENDING("蓝牙已连接，仍在等待接收端提供热点和地址信息"),
    LOCATION_REQUIRED("Android 12 需允许位置权限并开启位置服务以加入热点"),
    WIFI_LOST("车机网络已丢失，请检查接收端热点及手机 WLAN 后重连"),
    WIFI_JOIN_FAILED("未能绑定车机网络，请检查 WLAN、附近设备权限及系统加入确认框"),
    PAIRING_FAILED("未完成 AirPlay 配对，请检查接收端提示，必要时重启接收端后重试"),
    SETUP_FAILED("CarPlay 会话未建立，请提供诊断报告核对接收端响应"),
    CARWITH_FAILED("CarWith 未完成出流，请检查组件版本、原厂连接开关和授权"),
    CARWITH_BUSY("CarWith 正被独立自检占用，请先停止自检，再启动 CarPlay 连接"),
    VIDEO_CLOSED("视频连接已结束；仅凭此不能确认接收端崩溃，请检查网络和接收端"),
    AUDIO_UNAVAILABLE("音频未能启用，画面可以继续；请提供报告核对音频协商"),
    CONNECTION_INCOMPLETE("连接尚未完成，请结合最后阶段和接收端提示排查"),
    UNCAUGHT_EXCEPTION("应用线程发生异常，请分享诊断报告，不要只提供界面截图"),
    USER_STOP("用户主动停止，非连接故障"),
    SUPERSEDED("新的连接尝试替代了上一轮"),
}

/** Pure, generation-scoped state. Old callbacks cannot overwrite a replacement attempt. */
class ConnectionTimeline(
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idFactory: () -> String = { UUID.randomUUID().toString().take(8) },
    private val sink: (String) -> Unit,
) {
    data class Snapshot(
        val id: String,
        val stage: ConnectionStage,
        val status: String,
        val elapsedMs: Long,
        val code: DiagnosticCode?,
        val videoFrames: Long,
        val videoBytes: Long,
        val audioChunks: Long,
        val audioBytes: Long,
        val lastVideoMsAgo: Long?,
        val sourceFrames: Long,
        val audioPackets: Long,
    ) {
        private val statusLabel: String
            get() =
                when (status) {
                    "waiting" -> "进行中"
                    "streaming" -> "已发送视频"
                    "receiving_source" -> "收到原生视频"
                    "failed" -> "失败"
                    "stopped" -> "已结束"
                    else -> status
                }

        fun summary(): String =
            "会话 $id · ${stage.label} · $statusLabel · ${elapsedMs / 1000}s\n" +
                (
                    code?.let { "${it.name}：${it.advice}" } ?: when {
                        status == "streaming" && (lastVideoMsAgo ?: 0) >= 5000 ->
                            "最近 ${(lastVideoMsAgo ?: 0) / 1000}s 未发出新视频帧；请确认接收端画面，并导出报告"
                        sourceFrames > 0 && videoFrames == 0L && stage != ConnectionStage.SELF_TEST -> "已收到 CarWith 视频，尚未向接收端发出视频帧"
                        stage == ConnectionStage.CARWITH && elapsedMs >= 15_000 -> "仍在等待 CarWith 首帧，请检查原厂授权并导出报告"
                        else -> "遇到问题请导出诊断报告，并补充接收端当时的提示"
                    }
                )
    }

    private var id: String? = null
    private var stage = ConnectionStage.PRECHECK
    private var status = "waiting"
    private var code: DiagnosticCode? = null
    private var started = 0L
    private var frames = 0L
    private var videoBytes = 0L
    private var chunks = 0L
    private var audioBytes = 0L
    private var lastVideo: Long? = null
    private var lastSample = 0L
    private var sourceFrames = 0L
    private var audioPackets = 0L
    private var endedAt: Long? = null

    @Synchronized
    fun begin(source: String): String {
        id?.let { if (active()) finish(it, DiagnosticCode.SUPERSEDED, false) }
        id = idFactory()
        stage = ConnectionStage.PRECHECK
        status = "waiting"
        code = null
        started = elapsedTime()
        endedAt = null
        frames = 0
        videoBytes = 0
        chunks = 0
        audioBytes = 0
        lastVideo = null
        sourceFrames = 0
        audioPackets = 0
        lastSample = started
        emit("BEGIN", source)
        return requireNotNull(id)
    }

    @Synchronized
    fun stage(
        attempt: String,
        next: ConnectionStage,
    ) {
        if (id != attempt || !active() || stage == next) return
        stage = next
        if (code == DiagnosticCode.ENDPOINT_PENDING) code = null
        emit("STAGE")
    }

    @Synchronized
    fun issue(
        attempt: String,
        issue: DiagnosticCode,
        detail: String = "",
    ) {
        if (id != attempt || !active()) return
        code = issue
        emit("WARNING", detail)
    }

    @Synchronized
    fun note(
        attempt: String,
        detail: String,
    ) {
        if (id == attempt && active()) emit("NOTE", detail)
    }

    @Synchronized
    fun finish(
        attempt: String,
        reason: DiagnosticCode,
        failed: Boolean,
        detail: String = "",
    ) {
        if (id != attempt || !active()) return
        code = reason
        status = if (failed) "failed" else "stopped"
        endedAt = elapsedTime()
        emit("END", detail)
    }

    @Synchronized
    fun media(
        attempt: String,
        video: Boolean,
        bytes: Int,
    ) {
        if (id != attempt || !active() || bytes < 0) return
        if (video) {
            frames++
            videoBytes += bytes
            lastVideo = elapsedTime()
            if (frames == 1L) {
                stage = ConnectionStage.STREAMING
                status = "streaming"
                if (code in listOf(DiagnosticCode.BT_LINK_CLOSED, DiagnosticCode.ENDPOINT_PENDING, DiagnosticCode.WIFI_LOST)) code = null
                emit("FIRST_VIDEO")
            }
        } else {
            chunks++
            audioBytes += bytes
            if (chunks == 1L) emit("FIRST_NATIVE_AUDIO")
        }
        if (elapsedTime() - lastSample >= 10_000) {
            lastSample = elapsedTime()
            emit("MEDIA_SAMPLE")
        }
    }

    @Synchronized
    fun sourceFrame(attempt: String) {
        if (id != attempt || !active()) return
        sourceFrames++
        if (stage == ConnectionStage.SELF_TEST) status = "receiving_source"
        if (sourceFrames == 1L) emit("FIRST_CARWITH_VIDEO")
    }

    @Synchronized
    fun audioPacket(attempt: String) {
        if (id != attempt || !active()) return
        audioPackets++
        if (audioPackets == 1L) emit("FIRST_AUDIO_PACKET")
    }

    @Synchronized
    fun snapshot(): Snapshot? =
        id?.let {
            Snapshot(
                it,
                stage,
                status,
                ((endedAt ?: elapsedTime()) - started).coerceAtLeast(0),
                code,
                frames,
                videoBytes,
                chunks,
                audioBytes,
                lastVideo?.let { time -> (elapsedTime() - time).coerceAtLeast(0) },
                sourceFrames,
                audioPackets,
            )
        }

    private fun active(): Boolean = status == "waiting" || status == "streaming" || status == "receiving_source"

    private fun emit(
        event: String,
        detail: String = "",
    ) {
        val state = snapshot() ?: return
        val record =
            JSONObject()
                .put("schema", 1)
                .put("timeMs", wallTime())
                .put("attempt", state.id)
                .put("event", event)
                .put("stage", state.stage.name)
                .put("status", state.status)
                .put("elapsedMs", state.elapsedMs)
                .put("code", state.code?.name ?: "NONE")
                .put("videoFrames", state.videoFrames)
                .put("videoBytes", state.videoBytes)
                .put("audioChunks", state.audioChunks)
                .put("audioBytes", state.audioBytes)
                .put("lastVideoMsAgo", state.lastVideoMsAgo ?: JSONObject.NULL)
                .put("sourceVideoFrames", state.sourceFrames)
                .put("audioPacketsSent", state.audioPackets)
                .put(
                    "detail",
                    DiagnosticRedactor().redact(detail).replace(Regex("<(?:IP|MAC|NAME)-[0-9]+>"), "<redacted-at-source>").take(2_000),
                )
        runCatching { sink(record.toString()) }
    }
}
