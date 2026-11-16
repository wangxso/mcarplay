package com.mcarplay.app.video

import android.media.MediaFormat

/**
 * 转码编码器（`VideoSender.createEncoder(baselineProfile = true)`）的 MediaFormat 取值（纯函数，单测钉死）。
 *
 * 拆出来的原因：这几个键**写错不会抛异常**，只会静默地改变出流节奏或让编码器忽略设置，
 * 真车上的表现是「车机反复要 IDR / 画面卡顿」，从日志里几乎看不出来。单测只能覆盖算术与
 * 键的归属，真机行为仍需台架与实车验证。
 *
 * 参考对象（闭源第三方发送端）的静态分析给出的是 [microsPerFrame] 这个量级，
 * 但数值本身不是协议常量——真正的依据是 Android 官方文档对每个键的定义。
 */
internal object EncoderFormatPolicy {
    /**
     * `MediaFormat.KEY_MAX_B_FRAMES` 的取值：0 = 不使用 B 帧。
     *
     * 官方文档（API 29+）说明该键**默认就是 0**，因此这里显式设置的作用是回归钉子：
     * 即使将来有人去掉 Baseline profile，也不会悄悄引入 B 帧。B 帧需要解码端重排序，
     * 会给车载显示链路增加固有延迟，而本项目的目标是最低延迟而不是最高压缩率。
     */
    const val MAX_B_FRAMES = 0

    /**
     * `MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER` 的单位：**微秒**。
     *
     * 官方原文：「gives the time in microseconds after which the frame previously submitted to
     * the encoder will be repeated (once) if no new frame became available since.」
     * 只在 surface 输入的编码器上生效。
     *
     * **不要写成纳秒**：用 `1_000_000_000 / fps` 会让重复周期变成 33.3 秒（30 fps），
     * 比预期慢 1000 倍——编码器在该周期内不会补帧，车机侧看到的是长间隔断流。
     * [microsPerFrame] 与 [transcodeKeys] 的单测同时钉住正确值与错误值。
     */
    const val MICROS_PER_SECOND = 1_000_000L

    /**
     * 可选的「实时」提示键。编码器不支持时官方定义是忽略该键，但部分厂商编码器会直接
     * 在 `configure` 抛异常，因此调用方要按「先加后撤」的方式尝试（[optionalRealtimeKeys]）。
     *
     * 注意：这里**刻意不包含** `KEY_BITRATE_MODE`。`bitrate-mode = 2` 就是 `BITRATE_MODE_CBR`，
     * 属于 issue #72 的非目标（没有接收端因此受益的证据），不得作为默认值引入。
     */
    const val PRIORITY_REALTIME = 0

    /** `KEY_LATENCY` 的单位是**帧**：要求编码器在排入该帧数后至少产出一帧。 */
    const val LATENCY_FRAMES = 1

    /** 帧间隔（微秒）。帧率非法时按 1 处理，避免除零。 */
    fun microsPerFrame(fps: Int): Long = MICROS_PER_SECOND / fps.coerceAtLeast(1)

    /** 转码路径必定写入的两个键。 */
    data class TranscodeKeys(
        val maxBFrames: Int,
        val repeatPreviousFrameAfterMicros: Long,
    ) {
        fun applyTo(format: MediaFormat) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, maxBFrames)
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatPreviousFrameAfterMicros)
        }
    }

    fun transcodeKeys(fps: Int): TranscodeKeys =
        TranscodeKeys(
            maxBFrames = MAX_B_FRAMES,
            repeatPreviousFrameAfterMicros = microsPerFrame(fps),
        )

    /** 可选提示键（键 → 整数取值）；顺序固定，便于日志与测试比对。 */
    fun optionalRealtimeKeys(): List<Pair<String, Int>> =
        listOf(
            MediaFormat.KEY_PRIORITY to PRIORITY_REALTIME,
            MediaFormat.KEY_LATENCY to LATENCY_FRAMES,
        )

    fun applyOptionalRealtime(format: MediaFormat) {
        for ((key, value) in optionalRealtimeKeys()) format.setInteger(key, value)
    }

    /**
     * `configure` 的一级尝试：哪一层参数可以撤销。
     *
     * 转码路径的两个关键键（max-bframes / repeat-previous-frame-after）与 level/Profile 是**独立**的：
     * 编码器拒绝 level 4.0 不代表它拒绝这两个键，因此 [LEVEL_AND_PROFILE] 这一级也必须保留它们
     * （2026-11 审查：把三者绑在同一层会让「只拒 level」的编码器静默丢掉任务 A 的两个键）。
     */
    enum class Attempt(
        /** 是否写 level 4.0 / Baseline profile。 */
        val withLevelAndProfile: Boolean,
        /** 是否写 priority/latency 提示键。 */
        val withOptionalRealtime: Boolean,
        /** 这一级相对上一级撤销了什么（null = 第一级）；成功时据此记日志。 */
        val dropped: String?,
    ) {
        FULL(withLevelAndProfile = true, withOptionalRealtime = true, dropped = null),
        WITHOUT_OPTIONAL_HINTS(withLevelAndProfile = true, withOptionalRealtime = false, dropped = "priority/latency 提示"),

        /** 只撤销 level/Profile；转码专用键仍然写入。 */
        WITHOUT_LEVEL_AND_PROFILE(
            withLevelAndProfile = false,
            withOptionalRealtime = false,
            dropped = "level 4.0 / Baseline",
        ),

        /** 本地屏幕路径的第一级（非转码：从不写 priority/latency）。 */
        LOCAL_FULL(withLevelAndProfile = true, withOptionalRealtime = false, dropped = null),
    }

    /** 某条路径要尝试的级数：转码三级，本地屏幕路径两级（与旧行为一致）。 */
    fun attempts(baselineProfile: Boolean): List<Attempt> =
        if (baselineProfile) {
            listOf(Attempt.FULL, Attempt.WITHOUT_OPTIONAL_HINTS, Attempt.WITHOUT_LEVEL_AND_PROFILE)
        } else {
            listOf(Attempt.LOCAL_FULL, Attempt.WITHOUT_LEVEL_AND_PROFILE)
        }

    /** 日志用：把实际写入编码器的取值列出来，便于从报告里判断键是否生效。 */
    fun describe(fps: Int): String =
        "max-bframes=$MAX_B_FRAMES repeat-previous-frame-after=${microsPerFrame(fps)}us（重复周期，" +
            "官方单位为微秒）可选 priority=$PRIORITY_REALTIME latency=$LATENCY_FRAMES（单位：帧）"
}
