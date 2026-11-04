package com.mcarplay.app.carlife

/**
 * 推流中途接入 CarLife 视频时的门控。
 *
 * 预热的 CarLife 会话在 CarPlay RECORD 之前就已出帧；接入时必须从 IDR 开始，
 * 且 VideoSender 只在同一帧内同时找到 SPS 和 PPS 时才生成 avcC——CarWith 的 IDR
 * 不保证内联参数集（也可能只带 SPS），因此首个 IDR 缺哪一项就从解析器缓存补哪一项。
 */
class CarLifeJoinGate {
    @Volatile
    private var joined = false

    /** 接入次数（首次接入 + 每次 [reset] 后的重新接入），诊断用。 */
    @Volatile
    var joins = 0
        private set

    /** 最近一次接入前因等 IDR / 参数集而丢弃的帧数。 */
    @Volatile
    var droppedBeforeLastJoin = 0L
        private set

    /** 最近一次接入时从解析器缓存补进 IDR 的参数集（如 "SPS+PPS"；空 = 帧内已带齐）。 */
    @Volatile
    var filledAtLastJoin = ""
        private set

    private var droppedWhileWaiting = 0L

    /** 返回应提交的帧；null 表示丢弃（尚未等到可解码的起点）。 */
    fun admit(unit: CarLifeVideoAccessUnit): CarLifeVideoAccessUnit? {
        if (joined) return unit
        if (!unit.keyFrame) return waiting()
        val inline = inlineNalTypes(unit.data)
        val missingSps = SPS !in inline
        val missingPps = PPS !in inline
        if (missingSps && unit.sps == null) return waiting()
        if (missingPps && unit.pps == null) return waiting()
        joined = true
        droppedBeforeLastJoin = droppedWhileWaiting
        droppedWhileWaiting = 0
        filledAtLastJoin = listOfNotNull("SPS".takeIf { missingSps }, "PPS".takeIf { missingPps }).joinToString("+")
        joins++
        if (!missingSps && !missingPps) return unit
        var prefix = ByteArray(0)
        if (missingSps) prefix += START_CODE + unit.sps!!
        if (missingPps) prefix += START_CODE + unit.pps!!
        return unit.copy(data = prefix + unit.data)
    }

    private fun waiting(): CarLifeVideoAccessUnit? {
        droppedWhileWaiting++
        return null
    }

    /** 提交失败（发送端未运行或队列满）后重新等待 IDR，避免参考帧缺失导致花屏。 */
    fun reset() {
        joined = false
    }

    private fun inlineNalTypes(data: ByteArray): Set<Int> =
        annexBStartCodes(data)
            .mapNotNull { (start, prefix) ->
                val header = start + prefix
                if (header < data.size) data[header].toInt() and 0x1F else null
            }.toSet()

    private companion object {
        const val SPS = 7
        const val PPS = 8
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
