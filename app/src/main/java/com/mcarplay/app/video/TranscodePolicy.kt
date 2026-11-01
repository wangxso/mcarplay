package com.mcarplay.app.video

/**
 * 何时把 CarWith 透传流改为手机端转码（纯函数，单测钉死）。
 *
 * 只在「尺寸与车机流不符」且「车机连续要关键帧」同时成立时切换：DiPlay、奇瑞等
 * 接收端对尺寸不符的透传流照常出画，不该为它们引入转码的延迟与画质损失；
 * 帕萨特 2025（VW4211）则每 ~300ms 要一次关键帧，这是解码持续失败的信号。
 */
internal object TranscodePolicy {
    fun needsTranscode(
        sourceWidth: Int,
        sourceHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
    ): Boolean = sourceWidth > 0 && sourceHeight > 0 && (sourceWidth != streamWidth || sourceHeight != streamHeight)

    /** 转码编码帧率：不超过车机 /info 的 maxFPS（未声明时为 0，沿用目标帧率）。 */
    fun cappedFps(
        targetFps: Int,
        receiverMaxFps: Int,
    ): Int = if (receiverMaxFps in 1 until targetFps) receiverMaxFps else targetFps
}

/**
 * 「车机连续要关键帧」检测：[windowMillis] 内达到 [threshold] 次即判为解码失败风暴。
 * 宝马 iD9 会话起步时只要 1 次（正常现象）；帕萨特 2025 约 3 次/秒、持续不停。
 */
internal class KeyFrameStorm(
    private val threshold: Int = 4,
    private val windowMillis: Long = 2_000L,
) {
    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun record(nowMillis: Long): Boolean {
        recent.addLast(nowMillis)
        while (recent.isNotEmpty() && nowMillis - recent.first() > windowMillis) recent.removeFirst()
        return recent.size >= threshold
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
