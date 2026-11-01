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

    /** 记一帧已发出的透传访问单元（NAL 不含起始码）。 */
    @Synchronized
    fun onFrameSent(
        nalUnits: List<ByteArray>,
        keyFrame: Boolean,
        nowMillis: Long,
    ) {
        frames++
        if (keyFrame) {
            keyFrames++
            lastIdrSentAt = nowMillis
        }
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
        if (lastIdrSentAt == 0L) {
            requestsBeforeFirstIdr++
            return null
        }
        val gap = nowMillis - lastIdrSentAt
        if (!transcoding && requestGaps.size < MAX_GAPS) requestGaps += gap
        return gap
    }

    @Synchronized
    fun summary(): String {
        val gaps = requestGaps.sorted()
        val gapText =
            if (gaps.isEmpty()) {
                "无"
            } else {
                "中位 ${gaps[gaps.size / 2]}ms 最小 ${gaps.first()}ms 最大 ${gaps.last()}ms（${gaps.size} 次）"
            }
        return "帧=$frames IDR=$keyFrames slice I/P/B/其它=$sliceI/$sliceP/$sliceB/$sliceOther " +
            "每帧最多 $maxSlicesPerFrame 片（多片帧 $multiSliceFrames） 帧内 SPS/PPS=$inbandSpsFrames/$inbandPpsFrames " +
            "AUD=$audFrames SEI=$seiFrames 其它 NAL=${otherNalTypes.ifEmpty { "无" }} " +
            "首个 IDR 组成=${firstIdrNals ?: "未收到"}；车机要帧 $requests 次（首个 IDR 前 $requestsBeforeFirstIdr、" +
            "转码后 $requestsWhileTranscoding），透传时距上个 IDR $gapText"
    }

    companion object {
        private const val MAX_GAPS = 512

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
