package com.mcarplay.app.settings

import android.content.Context

/**
 * 「媒体」偏好：上车（CarWith 连接建立）时是否允许自动播放手机音乐。
 *
 * 默认**开**：保持既有行为，车机/CarWith 自己恢复的媒体照常播放。
 * 关闭后，mcarplay 在连接后的短窗口内拦下车机的自动播放键，并在音乐自行开始时
 * 暂停一次；连接前已经在播放的音乐保持不变，之后仍可在车机上手动播放。
 */
object MediaPrefs {
    const val AUTO_PLAY_ON_CONNECT_DEFAULT = true

    private const val PREFS_NAME = "settings"
    private const val KEY_AUTO_PLAY_ON_CONNECT = "auto_play_on_connect"

    fun autoPlayOnConnect(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PLAY_ON_CONNECT, AUTO_PLAY_ON_CONNECT_DEFAULT)

    fun setAutoPlayOnConnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_PLAY_ON_CONNECT, enabled)
            .apply()
    }
}
