package com.mcarplay.app.carlife

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

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

    /**
     * 后台发起的 startActivity 可能被系统静默丢弃且不抛异常（2026-10-26 小米实测：
     * MIUI「后台弹出界面」拒绝时 CarlifeAdbActivity 从未创建，7240 一直 ECONNREFUSED）。
     */
    fun isAppInForeground(): Boolean =
        runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            isForegroundImportance(info.importance)
        }.getOrDefault(true)

    /**
     * 小米「后台弹出界面」（MIUI 私有 app-op 10021）是否允许；非小米或无法读取时返回 null。
     * CarWith 入口在 RECORD 之后才打开，此时应用多半在系统 WLAN 设置之后，缺这项授权
     * 入口会被静默拒绝（2026-10-26 实测）。
     */
    fun backgroundStartAllowed(context: Context): Boolean? =
        runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching null
            val method =
                AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            val mode = method.invoke(appOps, MIUI_OP_BACKGROUND_START_ACTIVITY, Process.myUid(), context.packageName) as Int
            isBackgroundStartModeAllowed(mode)
        }.getOrNull()

    /** checkOpNoThrow 对未知 op 返回 MODE_ERRORED（2）——非 MIUI，不提示。 */
    internal fun isBackgroundStartModeAllowed(mode: Int): Boolean? =
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED -> false
            else -> null
        }

    /** 打开小米权限编辑页；不可用时退回应用详情页。 */
    fun openBackgroundStartSettings(context: Context): Boolean {
        val editor =
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(MIUI_SECURITY_CENTER, MIUI_PERMISSION_EDITOR)
                .putExtra("extra_pkgname", context.packageName)
        val details =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        return listOf(editor, details).any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }

    private const val MIUI_OP_BACKGROUND_START_ACTIVITY = 10021
    private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
    private const val MIUI_PERMISSION_EDITOR = "com.miui.permcenter.permissions.PermissionsEditorActivity"

    /** 0 表示系统未填充，按前台处理，避免误报。 */
    internal fun isForegroundImportance(importance: Int): Boolean =
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

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
