package com.mcarplay.app.carlife

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Uses ordinary intents to open CarWith's exported settings or compatibility entry. */
object CarLifeLauncher {
    const val CARWITH_PACKAGE = "com.miui.carlink"
    const val CARWITH_SETTINGS =
        "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity"
    const val CARWITH_ENTRY =
        "com.miui.carlink.castfwk.usb.CarlifeAdbActivity"
    const val CARLIFE_PACKAGE = "com.baidu.carlife.xiaomi"

    fun isAvailable(context: Context): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(CARWITH_PACKAGE, 0)
            context.packageManager.getApplicationInfo(CARLIFE_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun openSettings(context: Context): Boolean = startActivity(context, CARWITH_SETTINGS)

    /** The component's name contains Adb, but this ordinary app intent needs no ADB client. */
    fun launchPublicEntry(context: Context): Boolean = startActivity(context, CARWITH_ENTRY)

    private fun startActivity(
        context: Context,
        activityClass: String,
    ): Boolean =
        runCatching {
            val component = ComponentName(CARWITH_PACKAGE, activityClass)
            val info = context.packageManager.getActivityInfo(component, 0)
            if (!info.exported || !info.enabled) return@runCatching false
            context.startActivity(
                Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)
}

enum class CarLifeLaunchStrategy {
    ATTACH_EXISTING,
    EXPORTED_ENTRY,
}
