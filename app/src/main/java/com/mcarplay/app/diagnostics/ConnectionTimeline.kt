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
    WIFI_PERMISSION("请允许「附近 WLAN 设备」权限后重试"),
    WIFI_JOIN_FAILED("未能绑定车机网络，请检查 WLAN、附近设备权限及系统加入确认框"),
    PAIRING_FAILED("未完成 AirPlay 配对，请检查接收端提示，必要时重启接收端后重试"),
    SETUP_FAILED("CarPlay 会话未建立，请提供诊断报告核对接收端响应"),
    CARWITH_FAILED("CarWith 未完成出流，请检查组件版本、原厂连接开关和授权"),
    CARWITH_ENTRY_BLOCKED("应用在后台时未能打开 CarWith 入口；小米请在应用权限中允许「后台弹出界面」，或保持 mcarplay 在前台后重连"),
    CARWITH_ENTRY_NOT_OBSERVED(
        "等待七路连接期间未观察到 CarWith 通知，端口也未就绪；请保持 mcarplay 在前台，" +
            "打开 CarWith 设置手动选择百度 CarLife 无线连接后重试",
    ),
    CARWITH_ENTRY_STATUS_UNKNOWN(
        "七路端口未就绪，且未绑定通知监听器，无法判断 CarWith 入口是否执行；" +
            "请从 CarWith 设置手动发起百度 CarLife 无线连接；可允许 mcarplay 的通知使用权供下次诊断",
    ),
    CARWITH_LISTENERS_PENDING(
        "已观察到 CarWith 通知，但七路端口未就绪；请打开 CarWith 设置手动选择百度 CarLife 无线连接，" +
            "并核对 CarWith/CarLife 组件版本",
    ),
    CARWITH_PROTOCOL_SILENT(
        "七路端口已连接，但未收到 CarLife 协议响应；端口监听不等于有效投屏会话。" +
            "请停止现有 CarWith 连接，再从 CarWith 设置重新发起百度 CarLife 无线连接",
    ),
    CARWITH_BUSY("CarWith 正被独立自检占用，请先停止自检，再启动 CarPlay 连接"),
    VIDEO_CLOSED("视频连接已结束；仅凭此不能确认接收端崩溃，请检查网络和接收端"),
    AUDIO_UNAVAILABLE("音频未能启用，画面可以继续；请提供报告核对音频协商"),
    CONNECTION_INCOMPLETE("连接尚未完成，请结合最后阶段和接收端提示排查"),
    DETECTION_TIMEOUT("适配检测等待达到上限；未确定的项目仍为未验证，可检查车机后重新检测"),
    UNCAUGHT_EXCEPTION("应用线程发生异常，请分享诊断报告，不要只提供界面截图"),
    USER_STOP("用户主动停止，非连接故障"),
    SUPERSEDED("新的连接尝试替代了上一轮"),
}

/**
 * Pure, generation-scoped state. Old callbacks cannot overwrite a replacement attempt.
 *
 * Records are built under the state lock but handed to [sink] outside it, in order, so a slow
 * sink (disk) never stalls media threads counting frames or the UI thread reading [snapshot].
 * Each [track] is an independent timeline: a CarWith self-test cannot supersede a CarPlay attempt.
 */
class ConnectionTimeline(
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idFactory: () -> String = { UUID.randomUUID().toString().take(8) },
    private val sink: (String) -> Unit,
    private val track: String = "carplay",
    private val detailFilter: (String) -> String = DiagnosticRedactor::scrubAtSource,
) {
    data class Snapshot(
        val id: String,
        val startedAtMs: Long,
        val stage: ConnectionStage,
        val status: String,
        /** 主屏不归手机，屏幕视频已被门控暂停；此时 lastVideoMsAgo 增长不代表传输停滞。 */
        val screenSuspended: Boolean,
        val elapsedMs: Long,
        val code: DiagnosticCode?,
        val videoFrames: Long,
        val videoBytes: Long,
        val audioChunks: Long,
        val audioBytes: Long,
        val lastVideoMsAgo: Long?,
        val sourceFrames: Long,
        val audioPackets: Long,
        /** 首次到达各里程碑时距本轮开始的毫秒数，按到达先后排列。 */
        val milestones: Map<ConnectionMilestone, Long> = emptyMap(),
        /** 各阶段累计停留毫秒数（含当前阶段至今）；从未进入的阶段不在表中。 */
        val stageDurations: Map<ConnectionStage, Long> = emptyMap(),
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
                        screenSuspended ->
                            "主屏由车机持有，屏幕视频已暂停；等待主屏归还并收到新的关键帧"
                        status == "streaming" && (lastVideoMsAgo ?: 0) >= 5000 ->
                            "最近 ${(lastVideoMsAgo ?: 0) / 1000}s 未发出新视频帧；请确认接收端画面，并导出报告"
                        sourceFrames > 0 && videoFrames == 0L && stage != ConnectionStage.SELF_TEST -> "已收到 CarWith 视频，尚未向接收端发出视频帧"
                        stage == ConnectionStage.CARWITH && elapsedMs >= 15_000 -> "仍在等待 CarWith 首帧，请检查原厂授权并导出报告"
                        else -> "遇到问题请导出诊断报告，并补充接收端当时的提示"
                    }
                )
    }

    private class Pending(
        val record: JSONObject,
        val detail: String,
    )

    private val lock = Any()
    private val deliveryLock = Any()
    private val outbox = ArrayDeque<Pending>()

    private var id: String? = null
    private var startedAtMs = 0L
    private var stage = ConnectionStage.PRECHECK
    private var status = "waiting"
    private var screenSuspended = false
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
    private val milestones = LinkedHashMap<ConnectionMilestone, Long>()
    private val stageTime = LinkedHashMap<ConnectionStage, Long>()
    private var stageSince = 0L

    fun begin(source: String): String =
        mutate {
            id?.let { if (active()) end(it, DiagnosticCode.SUPERSEDED, false, "") }
            id = idFactory()
            startedAtMs = wallTime()
            stage = ConnectionStage.PRECHECK
            status = "waiting"
            screenSuspended = false
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
            milestones.clear()
            stageTime.clear()
            stageSince = started
            emit("BEGIN", source)
            requireNotNull(id)
        }

    fun stage(
        attempt: String,
        next: ConnectionStage,
    ) = mutate {
        if (id != attempt || !active() || stage == next) return@mutate
        enterStage(next)
        if (code == DiagnosticCode.ENDPOINT_PENDING) code = null
        emit("STAGE")
    }

    /**
     * 记录本轮首次到达 [milestone]；重复到达（周期重发、重连重跑）不改变首次时刻，也不重复写入。
     * [detail] 只写固定描述，不放地址等标识。
     */
    fun milestone(
        attempt: String,
        milestone: ConnectionMilestone,
        detail: String = "",
    ) = mutate {
        if (id != attempt || !active()) return@mutate
        if (reach(milestone)) emit("MILESTONE", detail, milestone)
    }

    fun issue(
        attempt: String,
        issue: DiagnosticCode,
        detail: String = "",
    ) = mutate {
        if (id != attempt || !active()) return@mutate
        code = issue
        emit("WARNING", detail)
    }

    fun note(
        attempt: String,
        detail: String,
    ) = mutate {
        if (id == attempt && active()) emit("NOTE", detail)
    }

    fun finish(
        attempt: String,
        reason: DiagnosticCode,
        failed: Boolean,
        detail: String = "",
    ) = mutate { end(attempt, reason, failed, detail) }

    /**
     * Ends an attempt that stopped before streaming, on every exit path (join failure, exception, cleanup).
     * A cancellation caused by the Bluetooth link closing stays [DiagnosticCode.BT_LINK_CLOSED]; only a real
     * user cancel is [DiagnosticCode.USER_STOP]. Otherwise [fallback], or the code for the stage reached.
     */
    fun abandon(
        attempt: String,
        cancelled: Boolean,
        fallback: DiagnosticCode? = null,
        detail: String = "",
    ) = mutate {
        if (id != attempt || !active()) return@mutate
        val reason =
            when {
                cancelled && code == DiagnosticCode.BT_LINK_CLOSED -> DiagnosticCode.BT_LINK_CLOSED
                cancelled -> DiagnosticCode.USER_STOP
                fallback != null -> fallback
                stage == ConnectionStage.PAIRING -> DiagnosticCode.PAIRING_FAILED
                stage == ConnectionStage.SETUP -> DiagnosticCode.SETUP_FAILED
                stage == ConnectionStage.CARWITH -> DiagnosticCode.CARWITH_FAILED
                else -> DiagnosticCode.CONNECTION_INCOMPLETE
            }
        end(attempt, reason, reason != DiagnosticCode.USER_STOP, detail)
    }

    /**
     * The video transport closed. After frames were sent this is the normal end of a session (receiver
     * switched off, stream torn down) and is not counted as a failure; with no frames sent it is one.
     */
    fun videoEnded(attempt: String) =
        mutate {
            if (id == attempt && active()) end(attempt, DiagnosticCode.VIDEO_CLOSED, frames == 0L, "")
        }

    fun media(
        attempt: String,
        video: Boolean,
        bytes: Int,
    ) = mutate {
        if (id != attempt || !active() || bytes < 0) return@mutate
        if (video) {
            frames++
            videoBytes += bytes
            lastVideo = elapsedTime()
            if (frames == 1L) {
                if (stage != ConnectionStage.STREAMING) enterStage(ConnectionStage.STREAMING)
                status = "streaming"
                if (code in listOf(DiagnosticCode.BT_LINK_CLOSED, DiagnosticCode.ENDPOINT_PENDING, DiagnosticCode.WIFI_LOST)) code = null
                reach(ConnectionMilestone.FIRST_VIDEO)
                emit("FIRST_VIDEO", milestone = ConnectionMilestone.FIRST_VIDEO)
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

    fun sourceFrame(attempt: String) =
        mutate {
            if (id != attempt || !active()) return@mutate
            sourceFrames++
            if (stage == ConnectionStage.SELF_TEST) status = "receiving_source"
            if (sourceFrames == 1L) {
                reach(ConnectionMilestone.CARWITH_FRAME)
                emit("FIRST_CARWITH_VIDEO", milestone = ConnectionMilestone.CARWITH_FRAME)
            }
        }

    /**
     * 主屏归属导致的屏幕视频暂停/恢复。暂停期间 [Snapshot.lastVideoMsAgo] 继续增长是预期行为，
     * 不能按传输停滞报警；恢复以新的 IDR 已发送为准。
     */
    fun screenOutput(
        attempt: String,
        suspended: Boolean,
    ) = mutate {
        if (id != attempt || !active() || screenSuspended == suspended) return@mutate
        screenSuspended = suspended
        emit(
            "SCREEN_OUTPUT",
            if (suspended) "主屏归车机，屏幕视频已暂停" else "主屏视频已由新的 IDR 恢复",
        )
    }

    fun audioPacket(attempt: String) =
        mutate {
            if (id != attempt || !active()) return@mutate
            audioPackets++
            if (audioPackets == 1L) {
                reach(ConnectionMilestone.FIRST_AUDIO)
                emit("FIRST_AUDIO_PACKET", milestone = ConnectionMilestone.FIRST_AUDIO)
            }
        }

    fun snapshot(): Snapshot? = synchronized(lock) { state() }

    private fun state(): Snapshot? =
        id?.let {
            val now = endedAt ?: elapsedTime()
            val durations = stageTime.toMutableMap()
            durations[stage] = (durations[stage] ?: 0L) + (now - stageSince).coerceAtLeast(0)
            Snapshot(
                it,
                startedAtMs,
                stage,
                status,
                screenSuspended,
                ((endedAt ?: elapsedTime()) - started).coerceAtLeast(0),
                code,
                frames,
                videoBytes,
                chunks,
                audioBytes,
                lastVideo?.let { time -> (elapsedTime() - time).coerceAtLeast(0) },
                sourceFrames,
                audioPackets,
                milestones.toMap(),
                durations,
            )
        }

    /** Called under [lock]: 记首次到达时刻；已到达过返回 false。 */
    private fun reach(milestone: ConnectionMilestone): Boolean {
        if (milestone in milestones) return false
        milestones[milestone] = (elapsedTime() - started).coerceAtLeast(0)
        return true
    }

    /** Called under [lock]: 把当前阶段的停留时间计入累计，再切到 [next]。 */
    private fun enterStage(next: ConnectionStage) {
        val now = elapsedTime()
        stageTime[stage] = (stageTime[stage] ?: 0L) + (now - stageSince).coerceAtLeast(0)
        stage = next
        stageSince = now
    }

    private fun active(): Boolean = status == "waiting" || status == "streaming" || status == "receiving_source"

    private inline fun <T> mutate(block: () -> T): T {
        val result = synchronized(lock) { block() }
        deliver()
        return result
    }

    private fun end(
        attempt: String,
        reason: DiagnosticCode,
        failed: Boolean,
        detail: String,
    ) {
        if (id != attempt || !active()) return
        code = reason
        status = if (failed) "failed" else "stopped"
        endedAt = elapsedTime()
        emit("END", detail)
    }

    /** Called under [lock]: queues the record; [deliver] writes it once the lock is released. */
    private fun emit(
        event: String,
        detail: String = "",
        milestone: ConnectionMilestone? = null,
    ) {
        val state = state() ?: return
        val record =
            JSONObject()
                .put("schema", 1)
                .put("timeMs", wallTime())
                .put("track", track)
                .put("attempt", state.id)
                .put("event", event)
                .put("stage", state.stage.name)
                .put("status", state.status)
                .put("screenSuspended", state.screenSuspended)
                .put("elapsedMs", state.elapsedMs)
                .put("code", state.code?.name ?: "NONE")
                .put("videoFrames", state.videoFrames)
                .put("videoBytes", state.videoBytes)
                .put("audioChunks", state.audioChunks)
                .put("audioBytes", state.audioBytes)
                .put("lastVideoMsAgo", state.lastVideoMsAgo ?: JSONObject.NULL)
                .put("sourceVideoFrames", state.sourceFrames)
                .put("audioPacketsSent", state.audioPackets)
        milestone?.let { record.put("milestone", it.name) }
        outbox.addLast(Pending(record, detail))
    }

    /** Drains queued records in order. Threads with nothing queued (most frames) never wait on the sink. */
    private fun deliver() {
        if (synchronized(lock) { outbox.isEmpty() }) return
        synchronized(deliveryLock) {
            while (true) {
                val next = synchronized(lock) { outbox.removeFirstOrNull() } ?: return
                runCatching {
                    next.record.put("detail", detailFilter(next.detail).take(2_000))
                    sink(next.record.toString())
                }
            }
        }
    }
}
