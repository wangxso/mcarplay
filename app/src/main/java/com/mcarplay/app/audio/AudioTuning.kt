package com.mcarplay.app.audio

/**
 * 音频负载的封装/时间戳参数（**已定案，唯一一组**）。
 *
 * 背景：2026-10-20 在 Honda HFT / Alpine 8A250-30A（Telechips tcc805x Android 10）上
 * 走 `type=102` + AAC-LC 44100 时，车机 SETUP 应答完整、`/feedback` 里 `sampleTime`
 * 按 44.1k 实时推进、会话稳定不断，但**喇叭没有声音**。当时把三组可能的封装差异
 * （裸帧 / ADTS、声明 spf / 不声明、到达时间戳 / 高精度时间戳）做成可切换方案，
 * 在车上逐一实测。
 *
 * 定案结论：真正的病根不在封装，而是发送前对 AAC 比特流做了 PCM 的 s16 换序
 * （见 [AudioSender] 的 `sealPacket`）。换序修掉后**方案 1 即可出声**，
 * 另外两组的差异对这台车机没有影响，因此收敛为唯一一组参数，不再提供切换。
 */
enum class AudioTuning(
    /** 是否给每帧补 ADTS 头。 */
    val adts: Boolean,
    /** SETUP 里声明的 spf（每包样本帧数）；null = 不声明。 */
    val spf: Int?,
    /** SETUP 里的 supportsHighAccuracyTimestamps。 */
    val highAccuracyTimestamps: Boolean,
) {
    /** 裸 AAC 帧 + `spf=1024` + 到达时间戳——真车实测唯一可用组合。 */
    RAW_SPF(
        adts = false,
        spf = 1024,
        highAccuracyTimestamps = false,
    ),
    ;

    /** 日志用的一行说明。 */
    val detail: String
        get() = "裸 AAC 帧 + spf=$spf + 到达时间戳"
}
