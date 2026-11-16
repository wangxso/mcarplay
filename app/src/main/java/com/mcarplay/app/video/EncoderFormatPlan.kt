package com.mcarplay.app.video

/**
 * 本次要 configure 的编码参数（纯数据，单测可直接断言）。
 *
 * 抽出它的目的不是改行为，而是让「既有路径的键与取值」可以被测试钉住：
 * [VideoSender.createEncoder] 用同一份计划构造主 format 与 level 回退 format，
 * 避免将来重构时把 `KEY_MAX_FPS_TO_ENCODER`、码率钳制或回退丢失 profile/level 的现状悄悄改掉。
 */
internal data class EncoderFormatPlan(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    /** 是否写 `KEY_MAX_FPS_TO_ENCODER`（转码路径：源帧率不受控，按车机 maxFPS 实际限帧）。 */
    val capEncoderFps: Boolean,
    /** 是否请求 Baseline profile（转码路径，与 CarWith 透传流形态一致）。 */
    val baselineProfile: Boolean,
    /** 是否写 `KEY_LEVEL = AVCLevel4`（两条路径都尝试，个别编码器会拒绝）。 */
    val level4: Boolean,
)

/**
 * 编码参数计划的纯函数（无 Android 依赖）。
 *
 * 码率公式与关掉 level 后的回退形态沿用既有实现，逐值不变：回退 format 只去掉
 * profile 与 level，尺寸 / 码率 / 帧率 / max-fps 键都保留——否则「回退」会同时降低限帧能力。
 */
internal object EncoderFormatRules {
    fun plan(
        width: Int,
        height: Int,
        fps: Int,
        capEncoderFps: Boolean,
        baselineProfile: Boolean,
    ): EncoderFormatPlan =
        EncoderFormatPlan(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrateFor(width, height, fps),
            capEncoderFps = capEncoderFps,
            baselineProfile = baselineProfile,
            level4 = true,
        )

    /**
     * 码率：像素量 × 6，钳在 2–10 Mbps；60 fps 再乘 1.5，最终不超过 15 Mbps。
     *
     * 与既有实现同式同序（含先转 Int 再钳制），不在此「顺手修正」溢出或量纲问题：
     * 码率变化会影响真机观感与车机解码器负载，没有台架证据前不动。
     */
    fun bitrateFor(
        width: Int,
        height: Int,
        fps: Int,
    ): Int =
        (
            ((width * height * 6L).toInt()).coerceIn(MIN_BITRATE, MAX_BASE_BITRATE) *
                if (fps >= 60) 1.5f else 1f
        ).toInt().coerceAtMost(MAX_BITRATE)

    /** level 被拒后的回退计划：保留其余键，丢掉 profile 与 level（既有行为）。 */
    fun withoutProfileAndLevel(plan: EncoderFormatPlan): EncoderFormatPlan = plan.copy(baselineProfile = false, level4 = false)

    private const val MIN_BITRATE = 2_000_000
    private const val MAX_BASE_BITRATE = 10_000_000
    private const val MAX_BITRATE = 15_000_000
}

/**
 * 执行 [block]；它抛出任何异常时先 [release] 再原样抛出。
 *
 * 用在「codec 已创建、但 configure / createInputSurface / start 仍可能失败」的窗口：
 * 原实现只把 `codec.release()` 留给 start 之后的调用方，提前失败会漏掉一个编码器实例。
 * 拆成独立函数是为了能用假回调单测「失败必释放、成功不释放」。
 */
internal inline fun <T> releaseOnFailure(
    release: () -> Unit,
    block: () -> T,
): T =
    try {
        block()
    } catch (error: Throwable) {
        runCatching { release() }
        throw error
    }
