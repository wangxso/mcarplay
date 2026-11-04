package com.mcarplay.app.carlife

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/** 只读、固定字段的源端环境；不读取其他应用私有偏好，不授予权限。缺少某项权限不直接判为根因。 */
object CarLifeSourceEnvironment {
    private const val CARLIFE_CONNECT_SERVICE = "com.baidu.carlife.service.CarlifeConnectService"
    private val permissions =
        listOf(
            "CAPTURE_VIDEO_OUTPUT",
            "CAPTURE_SECURE_VIDEO_OUTPUT",
            "INJECT_EVENTS",
            "ADD_TRUSTED_DISPLAY",
            "RECORD_AUDIO",
            "BLUETOOTH_CONNECT",
            "FOREGROUND_SERVICE",
        )

    fun snapshot(context: Context): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("source.schema", "1")
            packageState(context, CarLifeLauncher.CARWITH_PACKAGE, "carwith", this)
            packageState(context, CarLifeLauncher.CARLIFE_PACKAGE, "carlife", this)
            put(
                "carwith.featureSwitch",
                runCatching {
                    when (val value = Settings.Global.getString(context.contentResolver, "ex_func_carWith")) {
                        null -> "DEFAULT_ON"
                        "0" -> "OFF"
                        else -> value.toIntOrNull()?.let { if (it == 0) "OFF" else "ON" } ?: "UNKNOWN"
                    }
                }.getOrDefault("UNKNOWN"),
            )
            put("mcarplay.backgroundStartAllowed", CarLifeLauncher.backgroundStartAllowed(context)?.toString() ?: "unknown")
            put(
                "carlife.connectService",
                try {
                    val service =
                        context.packageManager.getServiceInfo(
                            ComponentName(CarLifeLauncher.CARLIFE_PACKAGE, CARLIFE_CONNECT_SERVICE),
                            0,
                        )
                    val permissionGranted =
                        service.permission?.let {
                            context.packageManager.checkPermission(it, CarLifeLauncher.CARWITH_PACKAGE) == PackageManager.PERMISSION_GRANTED
                        } ?: true
                    "found=true enabled=${service.enabled} exported=${service.exported} callerPermissionGranted=$permissionGranted"
                } catch (_: PackageManager.NameNotFoundException) {
                    "found=false"
                } catch (_: Exception) {
                    "unknown"
                },
            )
        }

    @Suppress("DEPRECATION")
    private fun packageState(
        context: Context,
        packageName: String,
        prefix: String,
        result: MutableMap<String, String>,
    ) {
        try {
            val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val app = info.applicationInfo
            result["$prefix.packageState"] = "AVAILABLE"
            result["$prefix.application"] =
                app?.let {
                    "enabled=${it.enabled} systemApp=${it.flags and ApplicationInfo.FLAG_SYSTEM != 0} " +
                        "updatedSystemApp=${it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0} targetSdk=${it.targetSdkVersion}"
                } ?: "unknown"
            permissions.forEach { shortName ->
                val permission = "android.permission.$shortName"
                val declared = permission in info.requestedPermissions.orEmpty()
                val granted = context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
                result["$prefix.permission.$shortName"] = "declared=$declared granted=$granted"
            }
        } catch (_: PackageManager.NameNotFoundException) {
            result["$prefix.packageState"] = "MISSING"
        } catch (_: Exception) {
            result["$prefix.packageState"] = "UNKNOWN"
        }
    }
}
