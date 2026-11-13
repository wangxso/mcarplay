package com.mcarplay.app.settings

import com.mcarplay.app.carlife.CarLifeProtocol
import kotlin.math.sqrt

/**
 * 输出分辨率模式（issue #58）。
 *
 * - [FOLLOW_CAR]：沿用旧行为，车机通告分辨率 × 百分比缩放；旧用户只存了百分比时落到这里。
 * - [HD_720] / [FULL_HD_1080]：绝对档位，忽略百分比。
 * - [CUSTOM]：绝对宽高，受 [DisplayResolution] 的上下限与偶数对齐约束。
 */
enum class ResolutionMode(
    val storageValue: String,
) {
    FOLLOW_CAR("follow_car"),
    HD_720("720p"),
    FULL_HD_1080("1080p"),
    CUSTOM("custom"),
    ;

    companion object {
        /** 未知/缺失值按「跟随车机」处理，兼容只有 `display_scale_percent` 的旧安装。 */
        fun fromStorage(value: String?): ResolutionMode = entries.firstOrNull { it.storageValue == value } ?: FOLLOW_CAR
    }
}

/**
 * 输出尺寸的纯计算与校验（无 Android 依赖，JVM 单测钉死）。
 *
 * 编码器固定 H.264 level 4.0（见 `VideoSender.createEncoder`）。level 4.0 的约束是
 * 单帧最多 [LEVEL4_MAX_FRAME_MACROBLOCKS] 个宏块、每秒最多
 * [LEVEL4_MAX_MACROBLOCKS_PER_SECOND] 个宏块；超出时本对象按比例缩小画布并把
 * 60fps 降为 30fps，而不是生成超出 level 声明的码流。CarWith 是否采纳请求无法保证，
 * 实际源尺寸始终以 `VIDEO_INIT_DONE` / SPS 为准。
 */
object DisplayResolution {
    const val MIN_WIDTH = 320
    const val MIN_HEIGHT = 180
    const val MAX_CUSTOM_WIDTH = 1920
    const val MAX_CUSTOM_HEIGHT = 1080

    const val PRESET_720_WIDTH = 1280
    const val PRESET_720_HEIGHT = 720
    const val PRESET_1080_WIDTH = 1920
    const val PRESET_1080_HEIGHT = 1080

    const val DEFAULT_CUSTOM_WIDTH = PRESET_720_WIDTH
    const val DEFAULT_CUSTOM_HEIGHT = PRESET_720_HEIGHT

    /** H.264 level 4.0：MaxFS = 8192 宏块/帧。 */
    const val LEVEL4_MAX_FRAME_MACROBLOCKS = 8192

    /** H.264 level 4.0：MaxMBPS = 245760 宏块/秒。 */
    const val LEVEL4_MAX_MACROBLOCKS_PER_SECOND = 245_760

    data class StoredSettings(
        val mode: ResolutionMode,
        val scalePercent: Int,
        val customWidth: Int,
        val customHeight: Int,
    )

    /**
     * 一次连接实际使用的尺寸计划。
     *
     * [streamWidth]/[streamHeight] 是 CarPlay 流画布（编码器/转码输出尺寸）；
     * [carWithRequestWidth]/[carWithRequestHeight] 是 CarLife `VIDEO_INIT` 请求；
     * [targetFps] 是经过 level 4.0 约束后的编码帧率，[requestedFps] 保留用户偏好。
     */
    data class Plan(
        val mode: ResolutionMode,
        val scalePercent: Int,
        val streamWidth: Int,
        val streamHeight: Int,
        val carWithRequestWidth: Int,
        val carWithRequestHeight: Int,
        val requestedFps: Int,
        val targetFps: Int,
        val level4FrameClamped: Boolean,
        val level4FpsLimited: Boolean,
    )

    enum class CustomSizeError { BLANK, NOT_A_NUMBER, TOO_SMALL, TOO_LARGE, LEVEL4_UNSUPPORTED }

    /**
     * 英雄卡的尺寸描述。**实际输出**只取自发送端的已知出流尺寸：
     * 透传时发送端构造尺寸是计划值，首个带 SPS 的帧到达前不能当作实际输出；
     * 协商值（VIDEO_INIT_DONE）只能作为「源」列出，不能声称已经出流。
     */
    enum class ActiveSizeKind {
        /** 会话尚未建立流画布，也没有任何协商值。 */
        UNKNOWN,

        /** 已发出请求，但既未协商也未出流。 */
        REQUESTED,

        /** 已协商源尺寸，但发送端尚未出流（透传画布仍是计划值）。 */
        SOURCE_NEGOTIATED,

        /** 发送端已在推流，但还没拿到 CarWith 实际源尺寸。 */
        SOURCE_PENDING,

        /** 实际输出即源尺寸，且与请求一致。 */
        ACTUAL,

        /** 请求尺寸未被采纳：实际输出与请求不同。 */
        ACTUAL_WITH_REQUEST,

        /** 转码中：实际输出是计划流尺寸，CarWith 源尺寸另列。 */
        ACTUAL_WITH_SOURCE,
    }

    data class ActiveSize(
        val kind: ActiveSizeKind,
        val outputWidth: Int,
        val outputHeight: Int,
        val otherWidth: Int,
        val otherHeight: Int,
    )

    /**
     * 生成英雄卡的尺寸描述。
     *
     * [outputWidth]/[outputHeight] 必须是发送端**已知**的出流画布：转码已开始，或
     * 透传已收到 SPS。传 0 表示尚不可知，此时不得把计划尺寸或协商值当成实际输出。
     * [requestWidth]/[requestHeight] 是本次发给 CarWith 的请求，[source] 只在
     * `VIDEO_INIT_DONE`/SPS 真正到达后才非空。
     */
    fun describeOutput(
        requestWidth: Int,
        requestHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        source: Pair<Int, Int>?,
    ): ActiveSize {
        val hasOutput = outputWidth > 0 && outputHeight > 0
        if (!hasOutput) {
            return when {
                source != null ->
                    ActiveSize(ActiveSizeKind.SOURCE_NEGOTIATED, 0, 0, source.first, source.second)
                requestWidth > 0 && requestHeight > 0 ->
                    ActiveSize(ActiveSizeKind.REQUESTED, 0, 0, requestWidth, requestHeight)
                else -> ActiveSize(ActiveSizeKind.UNKNOWN, 0, 0, 0, 0)
            }
        }
        if (source == null) {
            return ActiveSize(ActiveSizeKind.SOURCE_PENDING, outputWidth, outputHeight, 0, 0)
        }
        if (source.first != outputWidth || source.second != outputHeight) {
            return ActiveSize(ActiveSizeKind.ACTUAL_WITH_SOURCE, outputWidth, outputHeight, source.first, source.second)
        }
        val requestedDiffers =
            requestWidth > 0 && requestHeight > 0 && (requestWidth != outputWidth || requestHeight != outputHeight)
        return if (requestedDiffers) {
            ActiveSize(ActiveSizeKind.ACTUAL_WITH_REQUEST, outputWidth, outputHeight, requestWidth, requestHeight)
        } else {
            ActiveSize(ActiveSizeKind.ACTUAL, outputWidth, outputHeight, 0, 0)
        }
    }

    sealed interface CustomSizeResult {
        /** [adjusted] = 输入是奇数，已向下对齐到偶数。 */
        data class Valid(
            val width: Int,
            val height: Int,
            val adjusted: Boolean,
        ) : CustomSizeResult

        data class Invalid(
            val error: CustomSizeError,
        ) : CustomSizeResult
    }

    /** 从 SharedPreferences 原始值恢复；缺失/损坏的值回退到安全默认，旧百分比设置保留。 */
    fun fromStorage(
        modeValue: String?,
        scalePercent: Int,
        customWidth: Int,
        customHeight: Int,
    ): StoredSettings {
        val custom = sanitizeCustom(customWidth, customHeight) ?: defaultCustomSize()
        return StoredSettings(
            mode = ResolutionMode.fromStorage(modeValue),
            scalePercent = scalePercent.coerceIn(DisplayPrefs.SCALE_MIN, DisplayPrefs.SCALE_MAX),
            customWidth = custom.first,
            customHeight = custom.second,
        )
    }

    /**
     * 解析用户在自定义输入框里输入的宽高。
     *
     * 只接受十进制整数；边界为 320×180 到 1920×1080（含）。奇数向下对齐到偶数，
     * 并在返回的 [CustomSizeResult.Valid.adjusted] 里标明。
     */
    fun parseCustomSize(
        widthText: String,
        heightText: String,
    ): CustomSizeResult {
        val widthValue = widthText.trim()
        val heightValue = heightText.trim()
        if (widthValue.isEmpty() || heightValue.isEmpty()) return CustomSizeResult.Invalid(CustomSizeError.BLANK)
        val width = widthValue.toIntOrNull() ?: return CustomSizeResult.Invalid(CustomSizeError.NOT_A_NUMBER)
        val height = heightValue.toIntOrNull() ?: return CustomSizeResult.Invalid(CustomSizeError.NOT_A_NUMBER)
        if (width <= 0 || height <= 0) return CustomSizeResult.Invalid(CustomSizeError.NOT_A_NUMBER)
        if (width < MIN_WIDTH || height < MIN_HEIGHT) return CustomSizeResult.Invalid(CustomSizeError.TOO_SMALL)
        if (width > MAX_CUSTOM_WIDTH || height > MAX_CUSTOM_HEIGHT) return CustomSizeResult.Invalid(CustomSizeError.TOO_LARGE)
        val evenWidth = (width / 2) * 2
        val evenHeight = (height / 2) * 2
        if (macroblocks(evenWidth, evenHeight) > LEVEL4_MAX_FRAME_MACROBLOCKS) {
            return CustomSizeResult.Invalid(CustomSizeError.LEVEL4_UNSUPPORTED)
        }
        return CustomSizeResult.Valid(evenWidth, evenHeight, adjusted = evenWidth != width || evenHeight != height)
    }

    /** 校验并规范化已存储的宽高；越界/损坏返回 null。 */
    fun sanitizeCustom(
        width: Int,
        height: Int,
    ): Pair<Int, Int>? {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) return null
        if (width > MAX_CUSTOM_WIDTH || height > MAX_CUSTOM_HEIGHT) return null
        val evenWidth = (width / 2) * 2
        val evenHeight = (height / 2) * 2
        if (macroblocks(evenWidth, evenHeight) > LEVEL4_MAX_FRAME_MACROBLOCKS) return null
        return evenWidth to evenHeight
    }

    fun defaultCustomSize(): Pair<Int, Int> = DEFAULT_CUSTOM_WIDTH to DEFAULT_CUSTOM_HEIGHT

    /**
     * 生成一次连接的尺寸计划。
     *
     * 「跟随车机」保持旧的 CarWith 请求方式（按车机屏幕比例、长边 1920）；固定档位/自定义
     * 直接把流画布作为请求尺寸。无论哪种模式，流画布都会先压进 level 4.0 的单帧上限。
     */
    fun plan(
        displayWidth: Int,
        displayHeight: Int,
        mode: ResolutionMode,
        scalePercent: Int,
        customWidth: Int,
        customHeight: Int,
        requestedFps: Int,
    ): Plan {
        val requested = if (requestedFps >= 60) 60 else 30
        val raw =
            when (mode) {
                ResolutionMode.FOLLOW_CAR -> {
                    val scale = scalePercent.coerceIn(DisplayPrefs.SCALE_MIN, DisplayPrefs.SCALE_MAX)
                    val width = (displayWidth.coerceAtLeast(0).toLong() * scale / 100).toInt()
                    val height = (displayHeight.coerceAtLeast(0).toLong() * scale / 100).toInt()
                    evenFloor(width, MIN_WIDTH) to evenFloor(height, MIN_HEIGHT)
                }

                ResolutionMode.HD_720 -> PRESET_720_WIDTH to PRESET_720_HEIGHT
                ResolutionMode.FULL_HD_1080 -> PRESET_1080_WIDTH to PRESET_1080_HEIGHT
                ResolutionMode.CUSTOM ->
                    sanitizeCustom(customWidth, customHeight) ?: defaultCustomSize()
            }
        val stream = clampToLevel4(raw.first, raw.second)
        val request =
            if (mode == ResolutionMode.FOLLOW_CAR) {
                CarLifeProtocol.videoRequestFor(displayWidth, displayHeight)
            } else {
                stream
            }
        val maxFps = level4MaxFps(stream.first, stream.second)
        // 只提供 30/60 两档：60fps 在 level 4.0 下不成立时明确按 30fps 编码。
        val target = if (requested >= 60 && maxFps < 60) 30 else requested
        return Plan(
            mode = mode,
            scalePercent = scalePercent.coerceIn(DisplayPrefs.SCALE_MIN, DisplayPrefs.SCALE_MAX),
            streamWidth = stream.first,
            streamHeight = stream.second,
            carWithRequestWidth = request.first,
            carWithRequestHeight = request.second,
            requestedFps = requested,
            targetFps = target,
            level4FrameClamped = macroblocks(raw.first, raw.second) > LEVEL4_MAX_FRAME_MACROBLOCKS,
            level4FpsLimited = target < requested,
        )
    }

    /** 16×16 宏块数（向上取整）。 */
    fun macroblocks(
        width: Int,
        height: Int,
    ): Int {
        if (width <= 0 || height <= 0) return 0
        return ((width + 15) / 16) * ((height + 15) / 16)
    }

    /** 该尺寸在 level 4.0 宏块/秒上限下允许的最高帧率（至少 1）。 */
    fun level4MaxFps(
        width: Int,
        height: Int,
    ): Int {
        val blocks = macroblocks(width, height)
        if (blocks <= 0) return 60
        return (LEVEL4_MAX_MACROBLOCKS_PER_SECOND / blocks).coerceAtLeast(1)
    }

    private fun evenFloor(
        value: Int,
        minimum: Int,
    ): Int {
        val clamped = value.coerceAtLeast(minimum)
        return ((clamped / 2) * 2).coerceAtLeast(minimum)
    }

    /** 等比缩小到 level 4.0 单帧宏块上限，保持偶数对齐与最小尺寸。 */
    private fun clampToLevel4(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        var currentWidth = evenFloor(width, MIN_WIDTH)
        var currentHeight = evenFloor(height, MIN_HEIGHT)
        var guard = 0
        while (macroblocks(currentWidth, currentHeight) > LEVEL4_MAX_FRAME_MACROBLOCKS && guard < 32) {
            val factor =
                sqrt(LEVEL4_MAX_FRAME_MACROBLOCKS.toDouble() / macroblocks(currentWidth, currentHeight))
                    .coerceAtMost(0.999)
            val nextWidth = evenFloor((currentWidth * factor).toInt(), MIN_WIDTH)
            val nextHeight = evenFloor((currentHeight * factor).toInt(), MIN_HEIGHT)
            // 理论上下限内一定能收敛；尺寸已到最小仍不满足时停止循环，保留最小值。
            if (nextWidth == currentWidth && nextHeight == currentHeight) break
            currentWidth = nextWidth
            currentHeight = nextHeight
            guard++
        }
        return currentWidth to currentHeight
    }
}
