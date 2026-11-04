package com.mcarplay.app.video

import com.mcarplay.app.carlife.parsePps
import com.mcarplay.app.carlife.parseSps
import com.mcarplay.app.carlife.sliceType

/**
 * 透传码流与车机要帧的诊断统计（只记数字，不含码流内容）。
 *
 * 帕萨特 2025 在 CarWith 尺寸与车机流一致时仍整轮要关键帧（2026-11-01 21:45），报告里却看不出
 * CarWith 码流的 profile、B 帧、多 slice、帧内参数集等特征，也看不出要帧发生在 IDR 之后多久。
 * 这里汇总这些信息写进运行日志，供下一次报告对照。所有方法 @Synchronized：
 * 帧来自透传线程，要帧来自车机事件线程。
 */
internal class PassthroughStreamStats {
    private var frames = 0L
    private var keyFrames = 0L
    private var sliceI = 0L
    private var sliceP = 0L
    private var sliceB = 0L
    private var sliceOther = 0L
    private var maxSlicesPerFrame = 0
    private var multiSliceFrames = 0L
    private var inbandSpsFrames = 0L
    private var inbandPpsFrames = 0L
    private var audFrames = 0L
    private var seiFrames = 0L
    private val otherNalTypes = sortedSetOf<Int>()
    private var lastIdrSentAt = 0L
    private val requestGaps = ArrayList<Long>()
    private var requests = 0L
    private var requestsBeforeFirstIdr = 0L
    private var requestsWhileTranscoding = 0L
    private var firstIdrNals: String? = null
    private var totalBytes = 0L
    private var maxFrameBytes = 0
    private var maxIdrBytes = 0
    private var framesSinceIdr = 0L
    private var minIdrIntervalFrames = Long.MAX_VALUE
    private var maxIdrIntervalFrames = 0L
    private var lastFrameAt = 0L
    private var maxSendGapMs = 0L
    private var sendStalls = 0L
    private var maxWriteMs = 0L
    private var slowWrites = 0L
    private var maxQueueDepth = 0
    private var queueDrops = 0L
    private var pendingRequestAt = 0L
    private val idrResponseMs = ArrayList<Long>()
    private var requestsWithoutIdr = 0L
    private var transcodedFrames = 0L
    private var transcodedKeyFrames = 0L
    private val transcodeRequestGaps = ArrayList<Long>()

    /** 记一帧已发出的透传访问单元（NAL 不含起始码）。 */
    @Synchronized
    fun onFrameSent(
        nalUnits: List<ByteArray>,
        keyFrame: Boolean,
        nowMillis: Long,
        bytes: Int = nalUnits.sumOf { it.size + 4 },
        writeMillis: Long = 0L,
        queueDepth: Int = 0,
    ) {
        frames++
        totalBytes += bytes
        if (bytes > maxFrameBytes) maxFrameBytes = bytes
        if (lastFrameAt != 0L) {
            val gap = nowMillis - lastFrameAt
            if (gap > maxSendGapMs) maxSendGapMs = gap
            if (gap >= STALL_MILLIS) sendStalls++
        }
        lastFrameAt = nowMillis
        if (writeMillis > maxWriteMs) maxWriteMs = writeMillis
        if (writeMillis >= SLOW_WRITE_MILLIS) slowWrites++
        if (queueDepth > maxQueueDepth) maxQueueDepth = queueDepth
        if (keyFrame) {
            if (keyFrames > 0) {
                minIdrIntervalFrames = minOf(minIdrIntervalFrames, framesSinceIdr)
                maxIdrIntervalFrames = maxOf(maxIdrIntervalFrames, framesSinceIdr)
            }
            keyFrames++
            framesSinceIdr = 0
            lastIdrSentAt = nowMillis
            if (bytes > maxIdrBytes) maxIdrBytes = bytes
            if (pendingRequestAt != 0L) {
                if (idrResponseMs.size < MAX_GAPS) idrResponseMs += nowMillis - pendingRequestAt
                pendingRequestAt = 0L
            }
        }
        framesSinceIdr++
        var slices = 0
        var hasSps = false
        var hasPps = false
        var hasAud = false
        var hasSei = false
        for (nal in nalUnits) {
            if (nal.isEmpty()) continue
            when (val type = nal[0].toInt() and 0x1F) {
                1, 5 -> {
                    slices++
                    when (sliceType(nal)?.rem(5)) {
                        0 -> sliceP++
                        1 -> sliceB++
                        2 -> sliceI++
                        else -> sliceOther++
                    }
                }
                6 -> hasSei = true
                7 -> hasSps = true
                8 -> hasPps = true
                9 -> hasAud = true
                else -> otherNalTypes += type
            }
        }
        if (slices > maxSlicesPerFrame) maxSlicesPerFrame = slices
        if (slices > 1) multiSliceFrames++
        if (hasSps) inbandSpsFrames++
        if (hasPps) inbandPpsFrames++
        if (hasAud) audFrames++
        if (hasSei) seiFrames++
        if (keyFrame && firstIdrNals == null) firstIdrNals = describeNals(nalUnits)
    }

    /** 记一次车机 `forceKeyFrame`；返回距上一个已发 IDR 的毫秒数（尚无 IDR 时为 null）。 */
    @Synchronized
    fun onReceiverKeyFrameRequest(
        nowMillis: Long,
        transcoding: Boolean,
    ): Long? {
        requests++
        if (transcoding) requestsWhileTranscoding++
        if (!transcoding) {
            // 上一次请求后 IDR_RESPONSE_LIMIT 内没等到 IDR：CarWith 没响应那次请求
            if (pendingRequestAt != 0L && nowMillis - pendingRequestAt > IDR_RESPONSE_LIMIT_MILLIS) requestsWithoutIdr++
            if (pendingRequestAt == 0L || nowMillis - pendingRequestAt > IDR_RESPONSE_LIMIT_MILLIS) pendingRequestAt = nowMillis
        }
        if (lastIdrSentAt == 0L) {
            requestsBeforeFirstIdr++
            return null
        }
        val gap = nowMillis - lastIdrSentAt
        val gaps = if (transcoding) transcodeRequestGaps else requestGaps
        if (gaps.size < MAX_GAPS) gaps += gap
        return gap
    }

    /** 记一帧已发出的转码输出（手机编码器）；之后的要帧间隔按转码 IDR 计。 */
    @Synchronized
    fun onTranscodedFrameSent(
        keyFrame: Boolean,
        nowMillis: Long,
    ) {
        transcodedFrames++
        if (keyFrame) {
            transcodedKeyFrames++
            lastIdrSentAt = nowMillis
        }
    }

    /** 透传队列已满、CarWith 帧被丢弃（随后入流门控等下一个 IDR）。返回累计丢弃数。 */
    @Synchronized
    fun onQueueDrop(): Long = ++queueDrops

    @Synchronized
    fun summary(): String {
        val idrInterval =
            if (keyFrames < 2) "不足 2 个" else "$minIdrIntervalFrames–$maxIdrIntervalFrames 帧"
        return "帧=$frames IDR=$keyFrames（间隔 $idrInterval） slice I/P/B/其它=$sliceI/$sliceP/$sliceB/$sliceOther " +
            "每帧最多 $maxSlicesPerFrame 片（多片帧 $multiSliceFrames） 帧内 SPS/PPS=$inbandSpsFrames/$inbandPpsFrames " +
            "AUD=$audFrames SEI=$seiFrames 其它 NAL=${otherNalTypes.ifEmpty { "无" }} " +
            "首个 IDR 组成=${firstIdrNals ?: "未收到"}" +
            "\n  ↳ 体积：共 ${totalBytes / 1024}KB 单帧最大 ${maxFrameBytes}B IDR 最大 ${maxIdrBytes}B；" +
            "发送：最大帧间隔 ${maxSendGapMs}ms（≥${STALL_MILLIS}ms 停顿 $sendStalls 次） 最慢写入 ${maxWriteMs}ms" +
            "（≥${SLOW_WRITE_MILLIS}ms $slowWrites 次） 队列最深 $maxQueueDepth 队满丢帧 $queueDrops" +
            "\n  ↳ 车机要帧 $requests 次（首个 IDR 前 $requestsBeforeFirstIdr、转码后 $requestsWhileTranscoding）；" +
            "透传时距上个 IDR ${distribution(requestGaps)}；要帧后 CarWith 出 IDR ${distribution(idrResponseMs)}，" +
            "${IDR_RESPONSE_LIMIT_MILLIS}ms 内未出 IDR $requestsWithoutIdr 次" +
            if (transcodedFrames > 0) {
                "\n  ↳ 转码输出 帧=$transcodedFrames IDR=$transcodedKeyFrames；转码后距上个 IDR ${distribution(transcodeRequestGaps)}"
            } else {
                ""
            }
    }

    private fun distribution(values: List<Long>): String {
        if (values.isEmpty()) return "无"
        val sorted = values.sorted()
        return "中位 ${sorted[sorted.size / 2]}ms 最小 ${sorted.first()}ms 最大 ${sorted.last()}ms（${sorted.size} 次）"
    }

    companion object {
        private const val MAX_GAPS = 512

        /** 相邻两帧发送间隔达到此值记一次停顿（约 19 fps 时正常间隔约 50ms）。 */
        const val STALL_MILLIS = 500L

        /** 单帧写入 socket（含 flush）达到此值记一次慢写：车机接收窗口满或链路拥塞。 */
        const val SLOW_WRITE_MILLIS = 50L

        /** 要帧后多久内出 IDR 算 CarWith 响应了这次请求。 */
        const val IDR_RESPONSE_LIMIT_MILLIS = 1_500L

        /** 「类型:字节」列表，如 `7:14 8:4 5:9450`。 */
        fun describeNals(nalUnits: List<ByteArray>): String =
            nalUnits.filter { it.isNotEmpty() }.joinToString(" ") { "${it[0].toInt() and 0x1F}:${it.size}" }

        /** SPS/PPS 兼容性字段一行描述；解析失败时只给字节数。 */
        fun describeParameterSets(
            sps: ByteArray,
            pps: ByteArray,
        ): String {
            val spsText = parseSps(sps)?.describe() ?: "SPS ${sps.size}B 解析失败"
            val ppsText = parsePps(pps)?.describe() ?: "PPS ${pps.size}B 解析失败"
            return "$spsText | $ppsText"
        }
    }
}
