package com.mcarplay.app.compat

import android.content.Context

/**
 * 兼容兜底开关（均默认关）。打开后恢复统一兼容策略之前的宽松行为，供个别车机
 * 尝试；它们没有实车证据，不是推荐设置，用户指南见 README「兼容兜底开关」。
 */
object CompatibilityPrefs {
    private const val PREFS = "settings"
    private const val KEY_PLAIN_FALLBACK = "compat_plain_fallback"
    private const val KEY_VEHICLE_PRESET = "compat_vehicle_preset"
    private const val KEY_AUDIO_BASELINE = "compat_audio_baseline"

    // 历史持久化键：中性化只改 Kotlin API，不改字符串，老安装的选择继续生效。
    private const val KEY_SCREEN_RETURN = "compat_passat_return_strategy"
    private const val KEY_KNOWN_ORA03 = "compat_known_ora03_"

    const val PLAIN_FALLBACK_DEFAULT = false
    const val AUDIO_BASELINE_DEFAULT = false

    /** Recognize a generic identity only for a receiver previously confirmed by its normal identity. */
    internal fun genericReceiverIdentityWarning(
        context: Context,
        binding: String,
        identity: ReceiverCompatibility.ReceiverIdentity,
    ): Boolean {
        if (identity == ReceiverCompatibility.ReceiverIdentity.OTHER) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_KNOWN_ORA03 + binding
        val decision = ReceiverCompatibility.receiverIdentityDecision(prefs.getBoolean(key, false), identity)
        if (decision.rememberExpected) prefs.edit().putBoolean(key, true).apply()
        return decision.showWarning
    }

    /** 配对失败（非 403）但 RTSP 连上过时，再试一次 et=NONE 明文会话。 */
    fun plainFallback(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PLAIN_FALLBACK, PLAIN_FALLBACK_DEFAULT)

    fun setPlainFallback(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PLAIN_FALLBACK, enabled)
            .apply()
    }

    /** 主页手动选择的车型适配；默认 [VehiclePreset.AUTO]。 */
    fun vehiclePreset(context: Context): VehiclePreset =
        VehiclePreset.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VEHICLE_PRESET, null))

    fun setVehiclePreset(
        context: Context,
        preset: VehiclePreset,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VEHICLE_PRESET, preset.name)
            .apply()
    }

    /** 接收端没有可用的媒体音频声明时，仍按协议基线（PCM 48k）尝试出声。 */
    fun audioBaseline(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUDIO_BASELINE, AUDIO_BASELINE_DEFAULT)

    fun setAudioBaseline(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_BASELINE, enabled)
            .apply()
    }

    /** 回屏策略（原帕萨特「回主界面再进」实验）；默认关，只有手动选择大众时由兼容层读取。 */
    fun screenReturnStrategy(context: Context): ScreenReturnStrategy =
        ScreenReturnStrategy.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SCREEN_RETURN, null))

    fun setScreenReturnStrategy(
        context: Context,
        strategy: ScreenReturnStrategy,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCREEN_RETURN, strategy.key)
            .apply()
    }
}
