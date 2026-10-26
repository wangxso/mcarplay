package com.mcarplay.app.carlife

/**
 * 推流中途接入 CarLife 视频时的门控。
 *
 * 预热的 CarLife 会话在 CarPlay RECORD 之前就已出帧；接入时必须从 IDR 开始，
 * 且 VideoSender 只从帧内的 SPS/PPS 生成 avcC——CarWith 的 IDR 不保证内联参数集，
 * 因此首个 IDR 缺参数集时补上解析器缓存的最新一组。
 */
class CarLifeJoinGate {
    @Volatile
    private var joined = false

    /** 返回应提交的帧；null 表示丢弃（尚未等到可解码的起点）。 */
    fun admit(unit: CarLifeVideoAccessUnit): CarLifeVideoAccessUnit? {
        if (joined) return unit
        if (!unit.keyFrame) return null
        if (hasInlineSps(unit.data)) {
            joined = true
            return unit
        }
        val sps = unit.sps ?: return null
        val pps = unit.pps ?: return null
        joined = true
        return unit.copy(data = START_CODE + sps + START_CODE + pps + unit.data)
    }

    /** 提交失败（发送端未运行或队列满）后重新等待 IDR，避免参考帧缺失导致花屏。 */
    fun reset() {
        joined = false
    }

    private fun hasInlineSps(data: ByteArray): Boolean =
        annexBStartCodes(data).any { (start, prefix) ->
            val header = start + prefix
            header < data.size && (data[header].toInt() and 0x1F) == 7
        }

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
