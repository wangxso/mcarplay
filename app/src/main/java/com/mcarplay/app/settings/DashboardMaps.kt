package com.mcarplay.app.settings

import android.content.Context
import android.content.pm.PackageManager
import com.mcarplay.app.R

internal object DashboardMaps {
    val apps =
        listOf(
            R.string.car_google_maps to listOf("com.google.android.apps.maps"),
            R.string.car_waze to listOf("com.waze"),
            R.string.car_amap to listOf("com.autonavi.minimap", "com.autonavi.amapauto"),
            R.string.car_baidu_map to listOf("com.baidu.BaiduMap", "com.baidu.naviauto"),
        )

    fun installedUids(context: Context): IntArray =
        apps
            .flatMap { (_, packages) -> packages }
            .mapNotNull { name ->
                try {
                    context.packageManager.getApplicationInfo(name, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }.distinct()
            .toIntArray()
}
