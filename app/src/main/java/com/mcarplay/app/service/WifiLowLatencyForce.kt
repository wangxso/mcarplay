package com.mcarplay.app.service

import android.content.Context
import android.os.Build
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.shizuku.ShizukuGate
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DebugFileLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14+ 的 Wi‑Fi 低延迟锁严格要求「应用前台 + 亮屏」；真锁屏后系统恢复省电，
 * 用户态 `WifiLock` 无法阻止。AOSP 提供的 `cmd wifi force-low-latency-mode` 可绕过
 * 该限制（`WifiLockManager` 仍会保留低延迟锁），但需要 shell 权限——本应用通过
 * 可选的 Shizuku 以 shell 身份执行，会话结束时恢复。
 *
 * 只在 [StreamingWifiLock] 持有低延迟锁期间启用；Shizuku 未运行/未授权时仅记录日志，
 * 不影响会话。应用被系统杀死时下次启动通过 [restoreAfterCrash] 兜底恢复。
 */
object WifiLowLatencyForce {
    private const val PREFS_NAME = "settings"
    private const val KEY_FORCED = "wifi_force_low_latency"

    private val enabledByUs = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun enable(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val app = context.applicationContext
        appContext = app
        if (enabledByUs.get()) return
        Bg.run("wifi-force-low-latency") {
            when (ShizukuGate.state(app)) {
                ShizukuGate.State.READY -> Unit
                ShizukuGate.State.UNAUTHORIZED -> {
                    DebugFileLog.append(app, "WIFI: Shizuku 未授权——无法强制 Wi‑Fi 低延迟；可改用「连接时真息屏」")
                    return@run
                }
                ShizukuGate.State.NOT_RUNNING -> {
                    DebugFileLog.append(app, "WIFI: Shizuku 未运行——启动 Shizuku 后可强制 Wi‑Fi 低延迟；当前可用「连接时真息屏」")
                    return@run
                }
                ShizukuGate.State.NOT_INSTALLED -> {
                    DebugFileLog.append(app, "WIFI: 未安装 Shizuku——真锁屏时请用「连接时真息屏」")
                    return@run
                }
            }
            val result = Shell.exec("cmd wifi force-low-latency-mode enabled")
            if (result.ok) {
                enabledByUs.set(true)
                app
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_FORCED, true)
                    .apply()
                DebugFileLog.append(app, "WIFI: 已通过 Shizuku 强制 Wi‑Fi 低延迟（真锁屏后仍保持）")
            } else {
                DebugFileLog.append(app, "WIFI: 强制 Wi‑Fi 低延迟失败 - ${result.output.take(120)}")
            }
        }
    }

    fun disable() {
        val app = appContext ?: return
        if (!enabledByUs.compareAndSet(true, false)) return
        Bg.run("wifi-force-low-latency-off") {
            val result = Shell.exec("cmd wifi force-low-latency-mode disabled")
            if (result.ok) {
                app
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_FORCED, false)
                    .apply()
                DebugFileLog.append(app, "WIFI: 已恢复 Wi‑Fi 低延迟自动管理")
            } else {
                // 保留 KEY_FORCED，下次启动 restoreAfterCrash 再试。
                DebugFileLog.append(app, "WIFI: 恢复 Wi‑Fi 低延迟失败 - ${result.output.take(120)}")
            }
        }
    }

    /** 上次进程被系统杀死时可能没走到 [disable]：启动时尝试恢复自动管理。 */
    fun restoreAfterCrash(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val app = context.applicationContext
        appContext = app
        val forced =
            app
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FORCED, false)
        if (!forced) return
        Bg.run("wifi-force-low-latency-restore") {
            if (ShizukuGate.state(app) != ShizukuGate.State.READY) return@run
            val result = Shell.exec("cmd wifi force-low-latency-mode disabled")
            if (result.ok) {
                app
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_FORCED, false)
                    .apply()
                DebugFileLog.append(app, "WIFI: 上次会话异常退出，已恢复 Wi‑Fi 低延迟自动管理")
            }
        }
    }
}
