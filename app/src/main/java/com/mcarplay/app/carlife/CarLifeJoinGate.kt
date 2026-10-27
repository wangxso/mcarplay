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

    /** 返回应提交的帧；null 表示丢弃（尚未等到可解码的起点）。 */
    fun admit(unit: CarLifeVideoAccessUnit): CarLifeVideoAccessUnit? {
        if (joined) return unit
        if (!unit.keyFrame) return null
        val inline = inlineNalTypes(unit.data)
        val missingSps = SPS !in inline
        val missingPps = PPS !in inline
        if (missingSps && unit.sps == null) return null
        if (missingPps && unit.pps == null) return null
        joined = true
        if (!missingSps && !missingPps) return unit
        var prefix = ByteArray(0)
        if (missingSps) prefix += START_CODE + unit.sps!!
        if (missingPps) prefix += START_CODE + unit.pps!!
        return unit.copy(data = prefix + unit.data)
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
