package com.mcarplay.app.video

/**
 * 视频输出停滞的健康判定（纯函数状态机，单测钉死）。
 *
 * 只回答一个问题：「距上一次成功写出视频帧已经很久了，此刻该做什么？」输入全是调用方采样的
 * 事实——距上次成功写出的毫秒数、当前写已在 `write`/`flush` 里停留多久、自上次成功写以来
 * 源是否又有输入、编码器是否又有输出——不读时钟、不碰网络，因此可以在单测里把时间轴推快。
 *
 * **必须区分几类停滞**，因为补救手段不同（issue #72 任务 C 的核心结论）：
 *  - 写阻塞（`write`/`flush` 长时间不返回，或编码器/源已有新数据却没写出）→ 补关键帧无意义：
 *    新 IDR 只会排在被卡的写后面，让积压更长。只记诊断，不请求。
 *  - 编码器无输出（源有新输入、编码器却一直 `INFO_TRY_AGAIN_LATER`）→ 补关键帧有意义。
 *  - 无源输入（CarWith 本身没有新帧）→ 不是编码器故障；只记为「无输入」，不补帧、不判死。
 *  - 主屏暂停/会话关闭 → 完全不判定，重置基线；暂停期间不得补帧。
 *
 * 阈值刻意不照抄参考实现的单一 20 s：那款发送端的日志里出现过 14 s 的写阻塞而看门狗全程未触发，
 * 说明单一「距上次成功写」阈值覆盖不到写阻塞。这里报警 5 s（与
 * [com.mcarplay.app.diagnostics.ConnectionTimeline] 的 5 s 文案对齐）、判「自动补救用尽」20 s
 * （与 [com.mcarplay.app.net.TcpLiveness] 的约 19 s 对齐），中间每 5 s 补一次关键帧、最多 3 轮。
 *
 * 本类**不做**任何丢弃、拆链或关闭动作：[Action.GIVE_UP] 只是停止自动补救并留一行日志，
 * 传输保持，交给 `TcpLiveness` 判对端死亡。避免在这里削弱 keepAlive 或抢在 keepalive 之前断链。
 *
 * 非线程安全：由 [VideoSender] 在锁内串行调用。
 */
internal class VideoOutputHealth(
    private val warnMillis: Long = WARN_MILLIS,
    private val giveUpMillis: Long = GIVE_UP_MILLIS,
    private val keyFrameRetryMillis: Long = KEY_FRAME_RETRY_MILLIS,
    private val maxKeyFrameRequests: Int = MAX_KEY_FRAME_REQUESTS,
    /** 写进入 `write`/`flush` 超过这么久才算「写阻塞」；正常写（含 keepAlive）只有毫秒级。 */
    private val writeBlockMillis: Long = WRITE_BLOCK_MILLIS,
) {
    /** 停滞类别，只用于诊断；决定是否值得补关键帧。 */
    enum class Kind(
        /** 诊断日志里的类别描述（中文，与 video/ 其他日志一致）。 */
        val label: String,
    ) {
        /** 一切正常，或尚未到报警阈值。 */
        NORMAL("正常"),

        /** 有数据却没写出：socket 写阻塞/接收窗口满，或写线程被卡住。 */
        WRITE_BLOCKED("写阻塞"),

        /** 源有新输入，但编码器没有新输出。 */
        NO_ENCODER_OUTPUT("编码器无输出"),

        /** 源本身没有新输入（CarWith 静默）；不是编码器故障。 */
        NO_SOURCE_INPUT("无输入"),
    }

    enum class Action {
        NONE,
        WARN,
        REQUEST_KEY_FRAME,
        GIVE_UP,
    }

    data class Decision(
        val action: Action,
        val kind: Kind,
        /** 本轮停滞累计请求关键帧的次数（含本次）。 */
        val keyFrameRequests: Int,
        /** 当前距上次成功写出的毫秒数（未到阈值或未停滞时为 0）。 */
        val stalledMillis: Long,
        /** 本轮停滞持续过的最长毫秒数（恢复时用于留痕）。 */
        val maxStalledMillis: Long = 0L,
        /** 本次采样首次跨过报警阈值。 */
        val warning: Boolean = false,
        /** 本次采样首次判定自动补救用尽（只应记一次日志）。 */
        val giveUp: Boolean = false,
        /** 本次采样发现输出刚刚恢复（只应记一次日志）。 */
        val recovered: Boolean = false,
    )

    init {
        require(warnMillis > 0) { "warnMillis must be positive" }
        require(giveUpMillis > warnMillis) { "giveUpMillis must exceed warnMillis" }
        require(keyFrameRetryMillis > 0) { "keyFrameRetryMillis must be positive" }
        require(maxKeyFrameRequests > 0) { "maxKeyFrameRequests must be positive" }
        require(writeBlockMillis > 0) { "writeBlockMillis must be positive" }
    }

    private var warned = false
    private var gaveUp = false
    private var keyFrameRequests = 0
    private var lastRequestAtMs: Long? = null
    private var maxStalled = 0L
    private var lastKind = Kind.NORMAL

    val hasGivenUp: Boolean get() = gaveUp

    val requestedKeyFrames: Int get() = keyFrameRequests

    /**
     * 喂入一次采样。
     *
     * [sinceLastWriteMs] 是「距上一次成功写出视频帧的毫秒数」。健康线程每次采样都重新计算它，
     * 一旦它变大，就是**没有新视频帧写出**的时长。
     * [writeInFlightMillis] 是当前这次写已进入 `write`/`flush` 的时长（没有写在途时为 0）。
     * [sourceInputSinceLastWrite] 表示自上次成功写以来源是否又有新帧（CarWith/CarWith 解码输入）。
     * [encoderOutputSinceLastWrite] 表示自上次成功写以来编码器是否产出过新帧；null = 该路径没有独立编码器
     * （透传路径），此时以源输入判断。
     * [paused]/[stopped] 为 true 时完全不判定并重置基线（主屏暂停、有序关闭期间不得补帧、不得报警）。
     */
    fun sample(
        sinceLastWriteMs: Long,
        writeInFlightMillis: Long,
        sourceInputSinceLastWrite: Boolean,
        encoderOutputSinceLastWrite: Boolean?,
        paused: Boolean,
        stopped: Boolean,
    ): Decision {
        if (paused || stopped) {
            // 主屏暂停/关闭期间的「没有写」是预期行为：重置，不报警、不累积关键帧请求
            reset()
            return Decision(Action.NONE, Kind.NORMAL, 0, 0)
        }
        if (sinceLastWriteMs < warnMillis) {
            // 有新帧写出：任何停滞（含已用尽的补救）就此结束，可再次触发
            val recovered = warned || gaveUp
            val previousMax = maxStalled
            reset()
            return if (recovered) {
                Decision(Action.NONE, Kind.NORMAL, 0, 0, maxStalledMillis = previousMax, recovered = true)
            } else {
                Decision(Action.NONE, Kind.NORMAL, 0, 0)
            }
        }
        maxStalled = maxOf(maxStalled, sinceLastWriteMs)
        val kind = classify(writeInFlightMillis, sourceInputSinceLastWrite, encoderOutputSinceLastWrite)
        // 停滞分类在一轮停滞里可能变化（无输入 → 写阻塞）：每次变化都值得记一行
        val kindChanged = warned && kind != lastKind
        lastKind = kind
        if (sinceLastWriteMs >= giveUpMillis) {
            val first = !gaveUp
            gaveUp = true
            warned = true
            return Decision(Action.GIVE_UP, kind, keyFrameRequests, sinceLastWriteMs, maxStalled, giveUp = first)
        }
        val firstWarning = !warned || kindChanged
        warned = true
        // 写阻塞/无证据类别不补关键帧：新 IDR 只会排在卡住的写之后加剧积压；用尽后也不再请求
        if (!kind.worthKeyFrameRequest() || keyFrameRequests >= maxKeyFrameRequests) {
            return Decision(Action.WARN, kind, keyFrameRequests, sinceLastWriteMs, maxStalled, warning = firstWarning)
        }
        // 距上次补帧还不够一个重试间隔：只记 WARN，不重复请求
        val last = lastRequestAtMs
        if (last != null && sinceLastWriteMs - last < keyFrameRetryMillis) {
            return Decision(Action.WARN, kind, keyFrameRequests, sinceLastWriteMs, maxStalled, warning = firstWarning)
        }
        lastRequestAtMs = sinceLastWriteMs
        keyFrameRequests++
        return Decision(Action.REQUEST_KEY_FRAME, kind, keyFrameRequests, sinceLastWriteMs, maxStalled, warning = firstWarning)
    }

    /** 会话结束/重置时调用：清空所有计数，下一次停滞从头开始。 */
    fun reset() {
        warned = false
        gaveUp = false
        keyFrameRequests = 0
        lastRequestAtMs = null
        maxStalled = 0L
        lastKind = Kind.NORMAL
    }

    /**
     * 记录一次视频帧成功写出：清掉本轮停滞的计数，下一次采样会自然看到
     * `sinceLastWriteMs < warnMillis`。
     */
    fun onWriteSucceeded() {
        reset()
    }

    fun summary(): String =
        when {
            keyFrameRequests > 0 ->
                "停滞自动补救请求关键帧 $keyFrameRequests 次" +
                    if (gaveUp) "，已用尽（保留传输，交给 TCP keepalive 判死）" else ""
            gaveUp -> "停滞（${lastKind.label}）未补关键帧，已放弃自动补救"
            warned -> "停滞（${lastKind.label}），未补关键帧"
            else -> "无停滞"
        }

    private fun classify(
        writeInFlightMillis: Long,
        sourceInputSinceLastWrite: Boolean,
        encoderOutputSinceLastWrite: Boolean?,
    ): Kind =
        when {
            writeInFlightMillis >= writeBlockMillis -> Kind.WRITE_BLOCKED
            // 编码器/源已有新数据却没写出：写路径慢，不补帧
            encoderOutputSinceLastWrite == true -> Kind.WRITE_BLOCKED
            // 透传路径没有独立编码器：源有新帧却没写出，同样是在等写
            encoderOutputSinceLastWrite == null ->
                if (sourceInputSinceLastWrite) Kind.WRITE_BLOCKED else Kind.NO_SOURCE_INPUT
            // 转码路径：有输入、没输出才是编码器无输出；连输入都没有就只是无输入
            sourceInputSinceLastWrite -> Kind.NO_ENCODER_OUTPUT
            else -> Kind.NO_SOURCE_INPUT
        }

    private fun Kind.worthKeyFrameRequest(): Boolean = this == Kind.NO_ENCODER_OUTPUT

    companion object {
        /** 与连接 timeline 的 5 s「最近未发出新视频帧」同一量级，便于两条证据对照。 */
        const val WARN_MILLIS = 5_000L

        /** 与 `TcpLiveness` 的约 19 s 对端死亡判定对齐：先放弃自动补救，再交给 keepalive。 */
        const val GIVE_UP_MILLIS = 20_000L

        /** 两轮补帧之间的最小间隔。 */
        const val KEY_FRAME_RETRY_MILLIS = 5_000L

        /** 一轮停滞最多补几次关键帧。 */
        const val MAX_KEY_FRAME_REQUESTS = 3

        /** 写进入 `write`/`flush` 超过 1 s 才视为阻塞；正常写只有毫秒级。 */
        const val WRITE_BLOCK_MILLIS = 1_000L
    }
}
