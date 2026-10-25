package com.mcarplay.app.launcher

import android.content.Context
import android.content.pm.ActivityInfo

/**
 * 每应用超采样偏好（三态，SharedPreferences 持久化）。
 *
 *  自动（默认）：检测到「竖屏锁定」的启动 Activity 时启用超采样——
 *      这类应用在我们的横屏应用屏上被系统 letterbox 成窄窗，且按手机状态栏
 *      物理像素预留顶部空带；超采样（渲染 ×1.5 再缩回）能压小固定像素占比。
 *      横屏应用（地图类）不需要超采样——原生分辨率更清晰、更流畅。
 *  开启/关闭：用户显式指定，压过自动检测。
 *
 * 竖屏检测读启动 Activity 的 screenOrientation（PORTRAIT/REVERSE/SENSOR/USER）。
 */
object SupersamplePrefs {
    const val MODE_AUTO = 0
    const val MODE_ON = 1
    const val MODE_OFF = 2

    private const val PREFS_NAME = "settings"
    private const val KEY_PREFIX = "ss_"

    /** 读偏好（默认自动）。 */
    fun mode(
        context: Context,
        component: android.content.ComponentName,
    ): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREFIX + component.flattenToString(), MODE_AUTO)

    fun setMode(
        context: Context,
        component: android.content.ComponentName,
        mode: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREFIX + component.flattenToString(), mode)
            .apply()
    }

    /** 本次启动是否走超采样应用屏（三态解析后的最终结果）。 */
    fun shouldSupersample(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        when (mode(context, component)) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> isPortraitActivity(context, component)
        }

    /** 自动检测：启动 Activity 是否竖屏锁定（在横屏应用屏上会被 letterbox 的形态）。 */
    fun isPortraitActivity(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        try {
            val info = context.packageManager.getActivityInfo(component, 0)
            when (info.screenOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                -> true

                else -> false
            }
        } catch (_: Exception) {
            false
        }
}
