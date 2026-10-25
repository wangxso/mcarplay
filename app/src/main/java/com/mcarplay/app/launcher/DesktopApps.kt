package com.mcarplay.app.launcher

import android.content.Context

/**
 * 桌面应用选择（SharedPreferences 持久化）。
 *
 * 默认（用户从未设置过）：只显示已安装的地图应用（高德/百度/腾讯；
 * 对照用户要求「默认显示高德、百度等地图」）；用户一旦在设置页勾选过，
 * 以显式选择为准（含「全部取消」这种状态）。
 */
object DesktopApps {
    private const val PREFS_NAME = "settings"
    private const val KEY_SELECTED = "desktopApps"

    /** 默认勾选的地图应用包名。 */
    private val MAP_PACKAGES =
        setOf(
            "com.autonavi.amapauto", // 高德地图车机版
            "com.baidu.naviauto", // 百度地图车机版
            "com.autonavi.minimap", // 高德地图
            "com.baidu.BaiduMap", // 百度地图
            "com.tencent.map", // 腾讯地图
        )

    /** 当前应显示在桌面上的应用（component flattenToString 集合）。 */
    fun selected(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SELECTED)) {
            return apps
                .filter { it.component.packageName in MAP_PACKAGES }
                .map { it.component.flattenToString() }
                .toSet()
        }
        return prefs.getStringSet(KEY_SELECTED, emptySet()).orEmpty().toSet()
    }

    fun save(
        context: Context,
        selection: Set<String>,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED, selection.toMutableSet())
            .apply()
    }
}
