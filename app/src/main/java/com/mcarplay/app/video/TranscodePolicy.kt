package com.mcarplay.app.video

/**
 * 何时把 CarWith 透传流改为手机端转码（纯函数，单测钉死）。
 *
 * 三条触发路径：
 *  1. 尺寸与车机流不符且车机连续要关键帧（[KeyFrameStorm.Level.BURST] 或 SUSTAINED）：
 *     帕萨特 2025（VW4211）在 CarWith 输出 1920×1140 时每 ~300ms 要一次。
 *  2. 尺寸不符且源画面明显更宽：车机固定画布无法容纳更宽的透传画面，右侧会被裁掉。
 *     2026-11-28 奇瑞报告：车机声明 1280×720，CarWith 1920×720，右侧约三分之一显示不全。
 *  3. 用户显式打开「尺寸不符即转码」实验开关：覆盖宽高比相同但仍需精确画布的严格接收端。
 *
 * DiPlay、WinPlay 等软件接收端能自行缩放 1920×1140 → 1280×720（比例仅差约 5%），不应只因
 * 尺寸不符就转码；因此自动路径只在源宽高比超过车机流 [ASPECT_MISMATCH_PERCENT] 以上时启用。
 *
 * 尺寸一致时持续要帧**不**触发转码（2026-11-01 修正）：同一台帕萨特 22:45 一轮同尺寸透传持续要帧、
 * 转码后仍要帧并在 13 s 后关闭数据口；22:46 一轮同样的透传流在主屏归手机时零要帧、正常出画触控。
 * 要帧风暴在这里反映的是主屏不归手机（资源仲裁），不是码流问题。
 */
internal object TranscodePolicy {
    /** 宽高比相对变化超过此百分比才自动转码；滤掉 1920×1140 → 1280×720 这类轻微差异。 */
    const val ASPECT_MISMATCH_PERCENT = 10

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

    /**
     * 用户显式开启「尺寸不符即转码」实验开关时，一旦收到首个 CarWith 源帧且尺寸与车机流不符，
     * 无需等待 `forceKeyFrame` 风暴即启动手机端转码；尺寸一致或尚未收到源帧（0×0）时不触发。
     */
    fun shouldTranscodeOnGeometryMismatch(
        sizeMismatch: Boolean,
        enabled: Boolean,
    ): Boolean = enabled && sizeMismatch

    /**
     * 首个源帧是否需要立即转码：显式开关覆盖任意尺寸不符；否则只有源画面明显更宽才自动转码。
     * 尺寸一致或尚未收到源帧（0×0）时都不会触发。
     */
    fun shouldTranscodeOnFirstFrame(
        sizeMismatch: Boolean,
        sourceWiderThanStream: Boolean,
        forceOnAnyMismatch: Boolean,
        autoOnAspectMismatch: Boolean,
    ): Boolean =
        sizeMismatch &&
            (forceOnAnyMismatch || (autoOnAspectMismatch && sourceWiderThanStream))

    /**
     * 源画面是否明显比车机流更宽（超过 [thresholdPercent]）。只在横向溢出时自动转码；
     * 更窄/更高的源通常由接收端留边，不会像 1920×720 → 1280×720 这样被裁掉右侧。
     * 用交叉相乘避免浮点误差；source 或 stream 尚未就绪时返回 false。
     */
    fun sourceWiderThanStream(
        sourceWidth: Int,
        sourceHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
        thresholdPercent: Int = ASPECT_MISMATCH_PERCENT,
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) return false
        val sourceRatio = sourceWidth.toLong() * streamHeight
        val streamRatio = streamWidth.toLong() * sourceHeight
        return sourceRatio * 100 > streamRatio * (100 + thresholdPercent)
    }

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
