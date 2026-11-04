package com.mcarplay.app.video

/**
 * 何时把 CarWith 透传流改为手机端转码（纯函数，单测钉死）。
 *
 * 只在「尺寸与车机流不符」且车机连续要关键帧（[KeyFrameStorm.Level.BURST] 或 SUSTAINED）时切换：
 * 帕萨特 2025（VW4211）在 CarWith 输出 1920×1140 时每 ~300ms 要一次。DiPlay、奇瑞等接收端对尺寸
 * 不符的透传流照常出画，不该只因尺寸不符就转码。
 *
 * 尺寸一致时持续要帧**不**触发转码（2026-11-01 修正）：同一台帕萨特 22:45 一轮同尺寸透传持续要帧、
 * 转码后仍要帧并在 13 s 后关闭数据口；22:46 一轮同样的透传流在主屏归手机时零要帧、正常出画触控。
 * 要帧风暴在这里反映的是主屏不归手机（资源仲裁），不是码流问题。
 */
internal object TranscodePolicy {
    fun needsTranscode(
        sourceWidth: Int,
        sourceHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
    ): Boolean = sourceWidth > 0 && sourceHeight > 0 && (sourceWidth != streamWidth || sourceHeight != streamHeight)

    fun shouldTranscode(
        storm: KeyFrameStorm.Level,
        sizeMismatch: Boolean,
    ): Boolean = sizeMismatch && storm != KeyFrameStorm.Level.NONE

    /** 转码编码帧率：不超过车机 /info 的 maxFPS（未声明时为 0，沿用目标帧率）。 */
    fun cappedFps(
        targetFps: Int,
        receiverMaxFps: Int,
    ): Int = if (receiverMaxFps in 1 until targetFps) receiverMaxFps else targetFps
}

/**
 * 「车机连续要关键帧」检测，只记车机的 `forceKeyFrame`（我们自己因 requestUI 补的 I 帧不算）。
 * - [burstThreshold] 次 / [burstWindowMillis]：短时密集（帕萨特 1920×1140 透传时约 3 次/秒）。
 * - [sustainedThreshold] 次 / [sustainedWindowMillis]：持续不停（帕萨特 1280×720 透传、主屏不归手机时约 0.9 次/秒）。
 * 两个阈值都来自单车现场观察，不是协议常量。
 */
internal class KeyFrameStorm(
    private val burstThreshold: Int = 4,
    private val burstWindowMillis: Long = 2_000L,
    private val sustainedThreshold: Int = 6,
    private val sustainedWindowMillis: Long = 10_000L,
) {
    enum class Level { NONE, BURST, SUSTAINED }

    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun record(nowMillis: Long): Level {
        recent.addLast(nowMillis)
        val horizon = maxOf(burstWindowMillis, sustainedWindowMillis)
        while (recent.isNotEmpty() && nowMillis - recent.first() > horizon) recent.removeFirst()
        if (recent.count { nowMillis - it <= sustainedWindowMillis } >= sustainedThreshold) return Level.SUSTAINED
        if (recent.count { nowMillis - it <= burstWindowMillis } >= burstThreshold) return Level.BURST
        return Level.NONE
    }
}

/**
 * 转码解码输入的断流恢复：丢掉任一访问单元后，后续 P 帧引用的参考帧已缺失，必须等下一个 IDR
 * 才能继续喂解码器，并向 CarWith 要关键帧（[requestIntervalMillis] 内最多一次）。转码后车机的
 * forceKeyFrame 只作用于手机端编码器，修不好源解码器（2026-11-01 审查）。只在单一投喂线程上调用。
 */
internal class IdrResync(
    private val requestIntervalMillis: Long = 1_000L,
) {
    var awaitingIdr = false
        private set
    private var lastRequestAt: Long? = null

    /** 该帧是否送解码器；等 IDR 期间只放行关键帧。 */
    fun admit(keyFrame: Boolean): Boolean {
        if (!awaitingIdr) return true
        if (!keyFrame) return false
        awaitingIdr = false
        return true
    }

    /** 记一次码流不连续（丢帧 / 入队失败），之后等 IDR。 */
    fun discontinuity() {
        awaitingIdr = true
    }

    /** 等 IDR 期间是否该（再）向上游要一次关键帧；限流。 */
    fun requestDue(nowMillis: Long): Boolean {
        if (!awaitingIdr) return false
        val last = lastRequestAt
        if (last != null && nowMillis - last < requestIntervalMillis) return false
        lastRequestAt = nowMillis
        return true
    }
}

/** Annex B 码流按 00 00 01 / 00 00 00 01 起始码切分为 NAL（不含起始码）。 */
internal object AnnexB {
    fun split(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
            return units
        }
        for (unit in 0 until starts.size) {
            val start = starts[unit]
            val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
            val bodyStart = start + prefix
            val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
        }
        return units
    }
}
