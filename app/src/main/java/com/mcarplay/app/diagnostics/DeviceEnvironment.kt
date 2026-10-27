package com.mcarplay.app.diagnostics

import android.content.Context
import android.os.Build
import com.mcarplay.app.BuildConfig
import com.mcarplay.app.carlife.CarLifeLauncher

/**
 * 实机验证要求记录的手机环境（机型、Android/HyperOS、CarWith 与小米 CarLife 版本），
 * 一行文本，供远程调试日志自动上报，免得每次复测都要人工查版本号。
 * 版本号前缀 `v`：日志 API 的脱敏会把独立的 `a.b.c.d` 当 IP 改写。
 */
object DeviceEnvironment {
    fun summary(context: Context): String =
        format(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            hyperOs = systemProperty("ro.mi.os.version.name"),
            romBuild = Build.VERSION.INCREMENTAL,
            carWith = packageVersion(context, CarLifeLauncher.CARWITH_PACKAGE),
            carLife = packageVersion(context, CarLifeLauncher.CARLIFE_PACKAGE),
            app = BuildConfig.VERSION_NAME,
        )

    internal fun format(
        model: String,
        android: String,
        hyperOs: String?,
        romBuild: String?,
        carWith: String?,
        carLife: String?,
        app: String,
    ): String =
        "ENV: 手机=$model Android=$android HyperOS=${hyperOs ?: "未知"} ROM=${romBuild ?: "未知"} " +
            "CarWith=${carWith ?: "未安装"} CarLife=${carLife ?: "未安装"} mcarplay=$app"

    @Suppress("DEPRECATION")
    private fun packageVersion(
        context: Context,
        name: String,
    ): String? =
        runCatching {
            context.packageManager.getPackageInfo(name, 0).let { "v${it.versionName} (${it.longVersionCode})" }
        }.getOrNull()

    /** 非小米 ROM 或读取受限时返回 null；只读一个公开的系统属性。 */
    private fun systemProperty(key: String): String? =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as String
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
