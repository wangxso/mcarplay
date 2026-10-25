package com.mcarplay.app.audio

/**
 * AirPlay 音频**同步包**（RTCP PT=84 / 字节 0xD4，发往该音频流的 `controlPort`）。
 *
 * 为什么必须有它（2026-10-20 Honda HFT / Alpine 8A250-30A 第三次实测定位）：
 * 音频 RTP 只是「样本流」，接收端还需要知道 **RTP 时间戳 ↔ 墙钟（NTP）** 的对应关系，
 * 才能算出「第 N 个样本应在什么时刻送到 DAC」。这条信息不在音频包里，而是由同步包
 * 单独下发（对照《Unofficial AirPlay Specification》audio/rtp_streams：
 * "Sync packets are sent once per second to the control port. They are used to
 * correlate the RTP timestamps currently used in the audio stream to the NTP time"）。
 *
 * 缺失时的典型症状正是我们遇到的那一版：车机 SETUP 给端口、照收 UDP、`/feedback` 里
 * `sampleTime` 按 44.1k 一路推进（时钟在跑），但喇叭一个样本都不出——排播锚点没建立。
 * 视频不受影响，因为每个视频帧自带呈现时间戳，不需要跨时钟锚定。
 *
 * 线格式（20 字节，**无 SSRC 字段**）：
 * ```
 * 0      1      2..3        4..7                8..15      16..19
 * 0x80   0xD4   seq(BE)     RTP ts(BE,当前)     NTP(BE)    RTP ts(BE,下一包)
 * ```
 *  - 字节 0：V=2（0x80）；**首个包（RECORD/FLUSH 之后）置 X 位 = 0x90**
 *  - 字节 1：M=1 + PT=84 → 0xD4
 *  - 字节 8..15：当前 NTP 时间（秒<<32 | 2^32 分数），与 [com.mcarplay.app.airplay.TimingServer]
 *    应答给车机的是同一时间基（单调时钟），否则车机算出的锚点会整体偏移。
 */
object AudioSyncPacket {
    /** 同步包定长（规范：8B 头 + 8B NTP + 4B 下一包时间戳）。 */
    const val SIZE = 20

    /** RTP 负载类型：84 = 时间同步（AirPlay 私有 RTCP 类型）。 */
    private const val PT_SYNC = 0xD4

    /** 版本 2 + Marker=1。 */
    private const val BASE_FIRST_BYTE = 0x80

    /** 扩展位（X）：RECORD/FLUSH 之后的首个同步包要置上。 */
    private const val EXTENSION_BIT = 0x10

    /**
     * 组包。
     *
     * @param sequence 同步包自己的序号（与音频 RTP 序号无关，独立递增）
     * @param currentRtpTimestamp 刚发出的音频包的时间戳（与 [ntp] 对应的那个时刻）
     * @param nextRtpTimestamp 下一个音频包的 RTP 时间戳（= current + 每包样本数）
     * @param ntp 8 字节大端 NTP 时间
     * @param first 是否为 RECORD 之后的首个同步包（置扩展位）
     */
    fun build(
        sequence: Int,
        currentRtpTimestamp: Long,
        nextRtpTimestamp: Long,
        ntp: ByteArray,
        first: Boolean,
    ): ByteArray {
        require(ntp.size == 8) { "NTP 时间必须是 8 字节，实际 ${ntp.size}" }
        val packet = ByteArray(SIZE)
        packet[0] = (BASE_FIRST_BYTE or (if (first) EXTENSION_BIT else 0)).toByte()
        packet[1] = PT_SYNC.toByte()
        packet[2] = ((sequence ushr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        writeBe32(packet, 4, currentRtpTimestamp)
        ntp.copyInto(packet, 8)
        writeBe32(packet, 16, nextRtpTimestamp)
        return packet
    }

    private fun writeBe32(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        val truncated = value.toInt()
        target[offset] = ((truncated ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((truncated ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((truncated ushr 8) and 0xFF).toByte()
        target[offset + 3] = (truncated and 0xFF).toByte()
    }
}
