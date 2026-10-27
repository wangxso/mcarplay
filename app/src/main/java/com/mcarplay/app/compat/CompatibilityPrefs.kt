package com.mcarplay.app.compat

import android.content.Context

/**
 * 兼容兜底开关（均默认关）。打开后恢复统一兼容策略之前的宽松行为，供个别车机
 * 尝试；它们没有实车证据，不是推荐设置，用户指南见 README「兼容兜底开关」。
 */
object CompatibilityPrefs {
    private const val PREFS = "settings"
    private const val KEY_PLAIN_FALLBACK = "compat_plain_fallback"
    private const val KEY_AUDIO_BASELINE = "compat_audio_baseline"

    const val PLAIN_FALLBACK_DEFAULT = false
    const val AUDIO_BASELINE_DEFAULT = false

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
}
