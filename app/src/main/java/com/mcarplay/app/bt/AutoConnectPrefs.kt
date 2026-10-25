package com.mcarplay.app.bt

import android.content.Context

/**
 * 「上车自动连接」开关（默认开）：车机蓝牙 ACL 连上时是否自动拉起会话。
 *
 * 与 `DisplayPrefs.screenOffOnConnect` 一起显示在「设置 → 显示」面板；
 * 由 [CarBluetoothReceiver] 在每次 ACL 广播时读取（进程可能刚被冷启动，没有缓存）。
 */
object AutoConnectPrefs {
    private const val PREFS = "settings"
    private const val KEY = "auto_connect_on_bt"

    /** 默认开：这是「拉开车门就自动连」的开关，用户主动关掉才停用。 */
    const val DEFAULT = true

    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, DEFAULT)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
