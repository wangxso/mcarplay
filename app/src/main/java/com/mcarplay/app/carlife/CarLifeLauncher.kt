package com.mcarplay.app.carlife

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import com.mcarplay.app.R
import com.mcarplay.app.diagnostics.DiagnosticExportPolicy
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.shizuku.ShizukuGate
import java.util.concurrent.TimeUnit

/** Opens CarWith entries, with a verified-root fallback for legacy services. */
object CarLifeLauncher {
    const val CARWITH_PACKAGE = "com.miui.carlink"
    const val CARWITH_SETTINGS =
        "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity"
    const val CARWITH_ENTRY =
        "com.miui.carlink.castfwk.usb.CarlifeAdbActivity"
    const val CARWITH_SERVICE =
        "com.miui.carlink.castfwk.CarlinkService"
    const val CARLIFE_PACKAGE = "com.baidu.carlife.xiaomi"

    enum class EntryLaunchResult {
        NOT_REQUESTED,
        REQUESTED,
        ATTACHED,
        MISSING,
        DISABLED,
        NOT_EXPORTED,
        PERMISSION_DENIED,
        FAILED,
    }

    fun isAvailable(context: Context): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(CARWITH_PACKAGE, 0)
            context.packageManager.getApplicationInfo(CARLIFE_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun openSettings(context: Context): Boolean = startActivity(context, CARWITH_SETTINGS)

    /** The component's name contains Adb, but this ordinary app intent needs no ADB client. */
    fun launchPublicEntry(context: Context): Boolean = requestPublicEntry(context) == EntryLaunchResult.REQUESTED

    /** REQUESTED 只说明 startActivity 返回，不能证明 CarWith 真正收到入口请求。 */
    fun requestPublicEntry(context: Context): EntryLaunchResult =
        try {
            val component = ComponentName(CARWITH_PACKAGE, CARWITH_ENTRY)
            val info = context.packageManager.getActivityInfo(component, 0)
            when {
                !info.enabled -> EntryLaunchResult.DISABLED
                !info.exported -> EntryLaunchResult.NOT_EXPORTED
                else -> {
                    context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    EntryLaunchResult.REQUESTED
                }
            }
        } catch (error: Exception) {
            DiagnosticExportPolicy.exception("CARWITH_PUBLIC_ENTRY", error)
            when (error) {
                is PackageManager.NameNotFoundException, is ActivityNotFoundException -> EntryLaunchResult.MISSING
                is SecurityException -> EntryLaunchResult.PERMISSION_DENIED
                else -> EntryLaunchResult.FAILED
            }
        }

    /**
     * CarWith 3.1.2 之前没有 `CarlifeAdbActivity`，正常无线连接页直接启动受签名权限
     * 保护的 [CARWITH_SERVICE]。root/ADB 身份可以执行同一 `startType=4`，但普通
     * 应用和 uid 2000 的 Shizuku shell 都不行；因此这里只在确实拿到 uid 0 时请求。
     */
    fun requestLegacyCarWithService(context: Context): EntryLaunchResult =
        try {
            val component = ComponentName(CARWITH_PACKAGE, CARWITH_SERVICE)
            val info = context.packageManager.getServiceInfo(component, 0)
            when {
                !info.enabled -> EntryLaunchResult.DISABLED
                else -> {
                    val result = runLegacyServiceCommand(context, legacyCarWithServiceCommand())
                    if (result.ok) EntryLaunchResult.REQUESTED else EntryLaunchResult.PERMISSION_DENIED
                }
            }
        } catch (error: Exception) {
            DiagnosticExportPolicy.exception("CARWITH_LEGACY_SERVICE", error)
            requestFailure(error)
        }

    /**
     * 旧版没有自动入口且当前进程拿不到 uid 0 时，打开 CarWith 的已导出设置页。
     *
     * 用户完成原厂路径后就绪的七口会被会话直接附着。百度 CarLife 的首页只会启动
     * 七口，不保证创建 CarWith 虚拟显示或编码器，因此不能作为自动出流回退。
     */
    fun requestCarWithSettings(context: Context): EntryLaunchResult {
        val result = requestActivity(context, CARWITH_PACKAGE, CARWITH_SETTINGS)
        if (result == EntryLaunchResult.REQUESTED) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, R.string.ma_legacy_carwith_setup, Toast.LENGTH_LONG).show()
            }
        }
        return result
    }

    private fun requestActivity(
        context: Context,
        packageName: String,
        className: String,
    ): EntryLaunchResult =
        try {
            val component = ComponentName(packageName, className)
            val info = context.packageManager.getActivityInfo(component, 0)
            when {
                !info.enabled -> EntryLaunchResult.DISABLED
                !info.exported -> EntryLaunchResult.NOT_EXPORTED
                else -> {
                    context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    EntryLaunchResult.REQUESTED
                }
            }
        } catch (error: Exception) {
            DiagnosticExportPolicy.exception("CARWITH_PUBLIC_ENTRY", error)
            requestFailure(error)
        }

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

    private fun requestFailure(error: Exception): EntryLaunchResult =
        when (error) {
            is PackageManager.NameNotFoundException, is ActivityNotFoundException -> EntryLaunchResult.MISSING
            is SecurityException -> EntryLaunchResult.PERMISSION_DENIED
            else -> EntryLaunchResult.FAILED
        }

    internal fun legacyCarWithServiceCommand(): String =
        "am start-foreground-service -n $CARWITH_PACKAGE/$CARWITH_SERVICE " +
            "--ei startType 4 --es carlife_wireless_type Baidu_ap " +
            "--es carlife_wireless_type_name mcarplay"

    private fun runLegacyServiceCommand(
        context: Context,
        command: String,
    ): CommandResult =
        runLegacyServiceCommand(
            command = command,
            runSu = ::runCommand,
            runShell = { cmd, timeout ->
                if (ShizukuGate.state(context) == ShizukuGate.State.READY) {
                    val result = Shell.exec(cmd, timeout)
                    CommandResult(result.exitCode, result.output)
                } else {
                    CommandResult(-1, "Shizuku unavailable")
                }
            },
        )

    internal fun runLegacyServiceCommand(
        command: String,
        runSu: (List<String>) -> CommandResult,
        runShell: (String, Long) -> CommandResult,
    ): CommandResult {
        val su = runSu(listOf("su", "-c", "id -u"))
        if (Thread.currentThread().isInterrupted) return CommandResult(-1, "cancelled")
        if (su.ok &&
            su.output
                .lineSequence()
                .lastOrNull { it.isNotBlank() }
                ?.trim() == "0"
        ) {
            return runSu(listOf("su", "-c", command))
        }
        val uid = runShell("id -u", 2_000L)
        if (Thread.currentThread().isInterrupted) return CommandResult(-1, "cancelled")
        if (uid.ok && uid.output.trim() == "0") return runShell(command, 6_000L)
        return CommandResult(-1, "uid 0 required")
    }

    private fun runCommand(command: List<String>): CommandResult {
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(3_000L, TimeUnit.MILLISECONDS)) {
                CommandResult(-1, "command timed out")
            } else {
                val output =
                    process.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                        .trim()
                CommandResult(process.exitValue(), output)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            CommandResult(-1, "cancelled")
        } catch (error: Exception) {
            CommandResult(-1, error.javaClass.simpleName)
        } finally {
            process?.destroyForcibly()
        }
    }

    internal data class CommandResult(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean
            get() = exitCode == 0 && !output.contains("Error", ignoreCase = true)
    }

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

    /**
     * 先附着已有七口；连接失败后再按 CarWith 的公开入口、root 旧版服务、
     * CarWith 设置页依次回退。
     */
    COMPATIBLE,
}
