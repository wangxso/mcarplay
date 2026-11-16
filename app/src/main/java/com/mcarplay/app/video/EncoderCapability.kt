package com.mcarplay.app.video

/**
 * 编码器声明能力的只读摘要（纯数据，无 Android 依赖，单测直接构造）。
 *
 * 全部字段可空：`null` = 厂商未声明或读取失败，一律按「未知」处理，**不得**当成「不支持」。
 *
 * 为什么只读而不是提前判定：车载链路的流尺寸是**协商结果**（车机 `/info` 的分辨率 ×
 * `DisplayPrefs` 百分比，同时写进 VideoConfig 头部让车机建解码器画布）。按对齐向上取整、
 * 或换一个 `MediaCodecList` 选出的编码器，都会让实际码流与车机画布或探测器判断对不上。
 * 厂商能力表还常见低估（声明不支持但仍能 configure 成功），据此提前失败会让原本可用的设备
 * 直接黑屏。因此这里只描述「实际创建的那个编码器声明了什么」，不选 codec、不改尺寸、不失败。
 */
internal data class EncoderCapability(
    val name: String,
    val surfaceInput: Boolean? = null,
    val widthAlignment: Int? = null,
    val heightAlignment: Int? = null,
    val minWidth: Int? = null,
    val maxWidth: Int? = null,
    val minHeight: Int? = null,
    val maxHeight: Int? = null,
    /** 声明支持的最高帧率；非有限值或 ≤ 0 的异常声明按未知处理。 */
    val maxFrameRate: Double? = null,
    /**
     * `(profile, level)` 声明对原文（MediaCodecInfo.CodecProfileLevel 的取值）。
     * 保留对而不是提前折成两个布尔：判断「Baseline@≥4.0」必须看同一对，
     * 只声明 High@4.2 的编码器不能算支持我们请求的 Baseline。
     */
    val profileLevels: List<Pair<Int, Int>>? = null,
)

/** 单个维度的结论。[UNSUPPORTED] 只是「编码器声明不支持」，不是失败依据。 */
internal enum class CapabilityState(
    val label: String,
) {
    SUPPORTED("支持"),
    UNSUPPORTED("声明不支持"),
    UNKNOWN("未知"),
    NOT_REQUESTED("未要求"),
}

/**
 * 一次只读核对的结论：各维度状态、可读的不足原因，以及一行可直接写日志的摘要。
 *
 * [hardProblems] 只放**与本机 H.264 约束冲突、确定不可用**的项（当前仅有奇数尺寸）；
 * 能力表声明与实际请求不一致的项在 [problems] 里，只记录，不阻断。
 */
internal data class EncoderCapabilityCheck(
    val size: CapabilityState,
    val frameRate: CapabilityState,
    val surfaceInput: CapabilityState,
    val baseline: CapabilityState,
    val level4: CapabilityState,
    val problems: List<String>,
    val hardProblems: List<String>,
    val summary: String,
) {
    /** 只读核对从不用于阻断 configure；保留给调用方记录，避免将来误当失败依据。 */
    val declaredMismatch: Boolean get() = problems.isNotEmpty()
}

/**
 * 把编码器声明能力与本次要 configure 的尺寸 / 帧率 / profile / level 做只读核对。
 *
 * 输入为空或异常时一律给出 `UNKNOWN`，绝不抛异常——调用方在建立编码器的关键路径上。
 */
internal object EncoderCapabilityRules {
    /** `MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline`（AOSP 常量值 1）。 */
    private const val AVC_PROFILE_BASELINE = 1

    /** `MediaCodecInfo.CodecProfileLevel.AVCLevel4`（AOSP 常量值 2048，level_idc 40）。 */
    private const val AVC_LEVEL4 = 2048

    fun assess(
        capability: EncoderCapability?,
        width: Int,
        height: Int,
        fps: Int,
        baselineProfile: Boolean,
        level4: Boolean,
    ): EncoderCapabilityCheck {
        val problems = ArrayList<String>()
        val hardProblems = ArrayList<String>()
        val size = assessSize(capability, width, height, problems, hardProblems)
        val frameRate = assessFrameRate(capability, fps, problems)
        val surface = assessSurface(capability, problems)
        val baseline = assessBaseline(capability, baselineProfile, problems)
        val level = assessLevel(capability, level4, baselineProfile, problems)
        return EncoderCapabilityCheck(
            size = size,
            frameRate = frameRate,
            surfaceInput = surface,
            baseline = baseline,
            level4 = level,
            problems = problems,
            hardProblems = hardProblems,
            summary = buildSummary(capability, width, height, fps, size, frameRate, surface, baseline),
        )
    }

    private fun assessSize(
        capability: EncoderCapability?,
        width: Int,
        height: Int,
        problems: MutableList<String>,
        hardProblems: MutableList<String>,
    ): CapabilityState {
        if (width <= 0 || height <= 0) {
            val reason = "尺寸 $width×$height 无效"
            problems += reason
            hardProblems += reason
            return CapabilityState.UNSUPPORTED
        }
        val odd = width % 2 != 0 || height % 2 != 0
        if (odd) {
            val reason = "尺寸 $width×$height 含奇数边（H.264 4:2:0 要求偶数）"
            problems += reason
            hardProblems += reason
        }
        if (capability == null) return if (odd) CapabilityState.UNSUPPORTED else CapabilityState.UNKNOWN

        val minWidth = capability.minWidth
        val maxWidth = capability.maxWidth
        val minHeight = capability.minHeight
        val maxHeight = capability.maxHeight
        var declaredMismatch = false
        if (minWidth != null && maxWidth != null && width !in minWidth..maxWidth) {
            declaredMismatch = true
            problems += "宽度 $width 超出编码器声明范围 $minWidth..$maxWidth"
        }
        if (minHeight != null && maxHeight != null && height !in minHeight..maxHeight) {
            declaredMismatch = true
            problems += "高度 $height 超出编码器声明范围 $minHeight..$maxHeight"
        }
        val alignWidth = capability.widthAlignment
        val alignHeight = capability.heightAlignment
        if (alignWidth != null && alignWidth > 1 && width % alignWidth != 0) {
            declaredMismatch = true
            problems += "宽度 $width 不是编码器声明对齐 $alignWidth 的倍数（不自动取整：会把协商尺寸改成车机未声明的大小）"
        }
        if (alignHeight != null && alignHeight > 1 && height % alignHeight != 0) {
            declaredMismatch = true
            problems += "高度 $height 不是编码器声明对齐 $alignHeight 的倍数（不自动取整：会把协商尺寸改成车机未声明的大小）"
        }
        if (odd || declaredMismatch) return CapabilityState.UNSUPPORTED
        val complete = minWidth != null && maxWidth != null && minHeight != null && maxHeight != null
        return if (complete) CapabilityState.SUPPORTED else CapabilityState.UNKNOWN
    }

    private fun assessFrameRate(
        capability: EncoderCapability?,
        fps: Int,
        problems: MutableList<String>,
    ): CapabilityState {
        val maxFps = capability?.maxFrameRate ?: return CapabilityState.UNKNOWN
        if (!maxFps.isFinite() || maxFps <= 0.0) return CapabilityState.UNKNOWN
        if (fps <= maxFps) return CapabilityState.SUPPORTED
        problems += "帧率 $fps 超过编码器声明上限 ${rateText(maxFps)}"
        return CapabilityState.UNSUPPORTED
    }

    private fun assessSurface(
        capability: EncoderCapability?,
        problems: MutableList<String>,
    ): CapabilityState =
        when (capability?.surfaceInput) {
            null -> CapabilityState.UNKNOWN
            true -> CapabilityState.SUPPORTED
            false -> {
                problems += "编码器未声明 COLOR_FormatSurface 输入（转码路径依赖 Surface 输入）"
                CapabilityState.UNSUPPORTED
            }
        }

    private fun assessBaseline(
        capability: EncoderCapability?,
        requested: Boolean,
        problems: MutableList<String>,
    ): CapabilityState {
        if (!requested) return CapabilityState.NOT_REQUESTED
        val pairs = capability?.profileLevels ?: return CapabilityState.UNKNOWN
        if (pairs.isEmpty()) return CapabilityState.UNKNOWN
        if (pairs.any { it.first == AVC_PROFILE_BASELINE }) return CapabilityState.SUPPORTED
        problems += "编码器未声明 Baseline profile（转码路径按 Baseline 请求；拒绝时 configure 会回退）"
        return CapabilityState.UNSUPPORTED
    }

    /** Level 4.0 只对请求 Baseline 的转码路径有意义；本地画面源不设 profile，也不在这里判。 */
    private fun assessLevel(
        capability: EncoderCapability?,
        requested: Boolean,
        baselineRequested: Boolean,
        problems: MutableList<String>,
    ): CapabilityState {
        if (!requested || !baselineRequested) return CapabilityState.NOT_REQUESTED
        val pairs = capability?.profileLevels ?: return CapabilityState.UNKNOWN
        if (pairs.isEmpty()) return CapabilityState.UNKNOWN
        // 同一 profile 声明对必须同时满足：Baseline 且 level ≥ 4.0（level_idc 40）。
        if (pairs.any { it.first == AVC_PROFILE_BASELINE && it.second >= AVC_LEVEL4 }) return CapabilityState.SUPPORTED
        problems += "编码器未声明 Baseline@≥4.0（SPS level 仍会补到 4.0，见 patchSpsLevel）"
        return CapabilityState.UNSUPPORTED
    }

    private fun buildSummary(
        capability: EncoderCapability?,
        width: Int,
        height: Int,
        fps: Int,
        size: CapabilityState,
        frameRate: CapabilityState,
        surface: CapabilityState,
        baseline: CapabilityState,
    ): String {
        val name = capability?.name ?: "能力未知"
        val maxFps = capability?.maxFrameRate
        val rateDetail =
            if (maxFps != null && maxFps.isFinite() && maxFps > 0.0) "，声明上限 ${rateText(maxFps)}" else ""
        return "$name ← 候选 ${width}x$height@$fps：尺寸 ${size.label}${sizeDetail(capability)}；" +
            "帧率 ${frameRate.label}$rateDetail；Surface 输入 ${surface.label}；" +
            "Baseline ${baseline.label}（仅记录，不改变 configure）"
    }

    private fun sizeDetail(capability: EncoderCapability?): String {
        if (capability == null) return ""
        val parts = ArrayList<String>()
        val alignWidth = capability.widthAlignment
        val alignHeight = capability.heightAlignment
        if (alignWidth != null || alignHeight != null) {
            parts += "对齐 ${alignWidth ?: "?"}×${alignHeight ?: "?"}"
        }
        val minWidth = capability.minWidth
        val maxWidth = capability.maxWidth
        val minHeight = capability.minHeight
        val maxHeight = capability.maxHeight
        if (minWidth != null && maxWidth != null && minHeight != null && maxHeight != null) {
            parts += "范围 $minWidth..$maxWidth×$minHeight..$maxHeight"
        }
        return if (parts.isEmpty()) "" else "（${parts.joinToString("，")}）"
    }

    /** 整数值不写成 `60.0`；非整数值固定 `Locale.ROOT`，避免某些区域把小数点写成逗号。 */
    private fun rateText(value: Double): String =
        if (value.isFinite() && value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.ROOT, "%.1f", value)
        }
}

/**
 * 从**实际创建的那个** [android.media.MediaCodec] 读取声明能力。
 *
 * 刻意不用 `MediaCodecList.findEncoderForFormat`：它返回的可能是另一个实现，
 * 与实际 `createEncoderByType` 选出的 codec 不一致时，核对结论会与真正 configure 的
 * 那个编码器无关。这里读 `codec.codecInfo`，保证「核对对象 == 使用对象」。
 *
 * 任何读取失败都返回 `null`（= 未知），绝不抛异常——调用方在建立编码器的关键路径上。
 */
internal fun readEncoderCapability(codec: android.media.MediaCodec): EncoderCapability? =
    runCatching {
        val info = codec.codecInfo ?: return@runCatching null
        val caps = info.getCapabilitiesForType(android.media.MediaFormat.MIMETYPE_VIDEO_AVC)
        val video = caps.videoCapabilities
        EncoderCapability(
            name = info.name ?: codec.name ?: "?",
            surfaceInput = caps.colorFormats?.contains(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface),
            widthAlignment = video.widthAlignment,
            heightAlignment = video.heightAlignment,
            minWidth = video.supportedWidths?.lower,
            maxWidth = video.supportedWidths?.upper,
            minHeight = video.supportedHeights?.lower,
            maxHeight = video.supportedHeights?.upper,
            maxFrameRate = video.supportedFrameRates?.upper?.toDouble(),
            profileLevels = caps.profileLevels?.map { it.profile to it.level },
        )
    }.getOrNull()
