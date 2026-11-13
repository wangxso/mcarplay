package com.mcarplay.app.settings

import android.content.Context

/**
 * 「显示」面板偏好：输出分辨率模式/缩放、自定义宽高与目标帧率。
 *
 * - 分辨率模式：跟随车机（默认，按车机通告分辨率 × 百分比缩放）或固定档位
 *   （1280×720 / 1920×1080 / 自定义宽高）。固定模式忽略百分比，**仅下次连接生效**——
 *   编码器与全部虚拟屏按会话建立时的尺寸创建，运行中改无意义。
 * - 帧率（30/60）：目标帧率；H.264 level 4.0 下 1080p 的 60fps 会在连接时按 30fps 编码。
 *
 * 旧安装只有 `display_scale_percent`：模式缺失时回退「跟随车机」，百分比照旧生效。
 * 「实际生效值」由 SessionController 在会话建立时记录（activeStreamWidth/Height 等），
 * 英雄卡显示运行时值而非偏好值。
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100

    const val FPS_DEFAULT = 30

    /** 连接时真息屏（物理面板断电）默认值。 */
    // 默认**关**（2026-10-24 用户要求）：连接成功就断面板对多数人太激进，
    // 想要的人在 设置→显示 手动开启（开关保留，只改默认值）
    const val SCREEN_OFF_DEFAULT = false

    /** HID 原始报文日志默认值：默认关（校准键位时临时打开，避免刷屏）。 */
    const val HID_RAW_LOG_DEFAULT = false

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_RESOLUTION_MODE = "display_resolution_mode"
    private const val KEY_CUSTOM_WIDTH = "display_custom_width"
    private const val KEY_CUSTOM_HEIGHT = "display_custom_height"
    private const val KEY_FPS = "display_fps"
    private const val KEY_SCREEN_OFF = "screen_off_on_connect"
    private const val KEY_HID_RAW_LOG = "hid_raw_log"

    /**
     * HID 原始报文日志（键位校准用）：打开后每帧 `hidSendReport` 会写一行
     * `HID-RAW: uuid=… bytes=…` 到主日志，用来对照按下某个键时的字节。
     */
    fun hidRawLog(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HID_RAW_LOG, HID_RAW_LOG_DEFAULT)

    fun setHidRawLog(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HID_RAW_LOG, enabled)
            .apply()
        com.mcarplay.app.input.HidNavigation.rawLogEnabled = enabled
    }

    /**
     * 连接时真息屏（默认开）：会话建立后把物理面板硬件断电（同 scrcpy `-S`），
     * 系统保持唤醒、虚拟屏照常渲染；关闭则只用暗幕。
     * 切换在会话中立即生效（见 SessionController.applyScreenOffPreference）。
     */
    fun screenOffOnConnect(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_OFF, SCREEN_OFF_DEFAULT)

    fun setScreenOffOnConnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREEN_OFF, enabled)
            .apply()
    }

    /** 流分辨率缩放百分比（30–160，步进 5；默认 100）。 */
    fun scalePercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, SCALE_DEFAULT)
            .coerceIn(SCALE_MIN, SCALE_MAX)

    fun setScalePercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCALE, percent.coerceIn(SCALE_MIN, SCALE_MAX))
            .apply()
    }

    /** 输出分辨率模式；缺失/未知值回退「跟随车机」，兼容只有百分比键的旧安装。 */
    fun resolutionMode(context: Context): ResolutionMode =
        ResolutionMode.fromStorage(
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RESOLUTION_MODE, null),
        )

    fun setResolutionMode(
        context: Context,
        mode: ResolutionMode,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RESOLUTION_MODE, mode.storageValue)
            .apply()
    }

    /** 自定义宽高；损坏/越界值回退 1280×720，绝不把非法尺寸交给编码器。 */
    fun customSize(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val width = prefs.getInt(KEY_CUSTOM_WIDTH, 0)
        val height = prefs.getInt(KEY_CUSTOM_HEIGHT, 0)
        return DisplayResolution.sanitizeCustom(width, height) ?: DisplayResolution.defaultCustomSize()
    }

    /** 只写入通过校验的尺寸；非法输入返回 false，调用方保留旧值。 */
    fun setCustomSize(
        context: Context,
        width: Int,
        height: Int,
    ): Boolean {
        val sanitized = DisplayResolution.sanitizeCustom(width, height) ?: return false
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CUSTOM_WIDTH, sanitized.first)
            .putInt(KEY_CUSTOM_HEIGHT, sanitized.second)
            .apply()
        return true
    }

    /** 一次性读取并规范化显示偏好（模式缺失的旧安装会保留百分比）。 */
    fun storedSettings(context: Context): DisplayResolution.StoredSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DisplayResolution.fromStorage(
            modeValue = prefs.getString(KEY_RESOLUTION_MODE, null),
            scalePercent = prefs.getInt(KEY_SCALE, SCALE_DEFAULT),
            customWidth = prefs.getInt(KEY_CUSTOM_WIDTH, 0),
            customHeight = prefs.getInt(KEY_CUSTOM_HEIGHT, 0),
        )
    }

    /** 按当前偏好生成一次连接的尺寸计划（纯逻辑在 [DisplayResolution.plan]）。 */
    fun plan(
        context: Context,
        displayWidth: Int,
        displayHeight: Int,
        requestedFps: Int,
    ): DisplayResolution.Plan {
        val stored = storedSettings(context)
        return DisplayResolution.plan(
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            mode = stored.mode,
            scalePercent = stored.scalePercent,
            customWidth = stored.customWidth,
            customHeight = stored.customHeight,
            requestedFps = requestedFps,
        )
    }

    /** 目标帧率（30/60；驱动合成器出帧节奏，运行中切换实时生效）。 */
    fun fps(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, FPS_DEFAULT)

    fun setFps(
        context: Context,
        fps: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FPS, if (fps >= 60) 60 else 30)
            .apply()
    }
}
