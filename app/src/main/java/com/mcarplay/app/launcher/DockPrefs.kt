package com.mcarplay.app.launcher

import android.content.Context

/**
 * dock 栏外观偏好（SharedPreferences 持久化 + 监听器通知）。
 *
 *  - scrimOpacity：dock 暗幕强度 0..100（%黑；0=壁纸清晰可见，100=全黑）
 *  - blur：背景高斯模糊强度 0..100（0=不模糊；映射到 RenderEffect 模糊半径）
 *
 * 监听器在主线程回调（设置页拖动滑条时触发），DockActivity 据此即时重渲染。
 */
object DockPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_SCRIM = "dockScrimOpacity"
    private const val KEY_BLUR = "dockBlur"
    private const val KEY_WIDTH = "dockWidthPercent"

    /** 暗幕强度默认值（%）。 */
    const val DEFAULT_SCRIM = 20

    /**
     * 栏宽度缩放（50–100，步进 5；默认 100）。厚度像素与 dock 屏密度**同乘**——
     * 内容 dp 布局不变、渲染进更少像素，整条 dock（图标/时钟/文字）等比变窄。
     * **仅下次连接生效**（dock 虚拟屏按会话建立时的尺寸/密度创建，与 scrim/blur
     * 的实时生效不同）。
     */
    const val WIDTH_MIN = 50
    const val WIDTH_MAX = 100
    const val WIDTH_STEP = 5
    const val WIDTH_DEFAULT = 100

    fun widthPercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WIDTH, WIDTH_DEFAULT)
            .coerceIn(WIDTH_MIN, WIDTH_MAX)

    fun setWidthPercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WIDTH, percent.coerceIn(WIDTH_MIN, WIDTH_MAX))
            .apply()
    }

    /** 模糊强度默认值（%）。 */
    const val DEFAULT_BLUR = 40

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scrimOpacity(context: Context): Int = prefs(context).getInt(KEY_SCRIM, DEFAULT_SCRIM).coerceIn(0, 100)

    fun blur(context: Context): Int = prefs(context).getInt(KEY_BLUR, DEFAULT_BLUR).coerceIn(0, 100)

    fun setScrimOpacity(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_SCRIM, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    fun setBlur(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_BLUR, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }
}
