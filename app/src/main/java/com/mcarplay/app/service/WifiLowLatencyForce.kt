package com.mcarplay.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.shizuku.ShizukuGate
import com.mcarplay.app.util.DebugFileLog
import java.util.concurrent.Executors

/**
 * Android 14+ 的低延迟锁在真锁屏后失效；可选地通过 Shizuku 强制该模式。
 * 所有 shell 命令与异常退出恢复都在同一后台队列执行，避免跨会话的启停乱序。
 */
object WifiLowLatencyForce {
    private const val PREFS_NAME = "settings"
    private const val KEY_FORCED = "wifi_force_low_latency"

    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "wifi-force-low-latency").apply { isDaemon = true }
        }

    @Volatile
    private var reconciler: WifiLowLatencyReconciler? = null

    fun enable(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        initialize(context.applicationContext).setDesired(true)
    }

    fun disable() {
        reconciler?.setDesired(false)
    }

    /** 进程启动时登记监听；Shizuku 稍后启动或授权时也会重试恢复。 */
    fun restoreAfterCrash(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        initialize(context.applicationContext).retry()
    }

    @Synchronized
    private fun initialize(app: Context): WifiLowLatencyReconciler {
        reconciler?.let { return it }
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val created =
            WifiLowLatencyReconciler(
                executor = executor,
                isReady = { desired ->
                    when (ShizukuGate.state(app)) {
                        ShizukuGate.State.READY -> true
                        ShizukuGate.State.UNAUTHORIZED -> {
                            if (desired) DebugFileLog.append(app, "WIFI: Shizuku 未授权——无法强制 Wi‑Fi 低延迟")
                            false
                        }
                        ShizukuGate.State.NOT_RUNNING -> {
                            if (desired) DebugFileLog.append(app, "WIFI: Shizuku 未运行——无法强制 Wi‑Fi 低延迟")
                            false
                        }
                        ShizukuGate.State.NOT_INSTALLED -> {
                            if (desired) DebugFileLog.append(app, "WIFI: 未安装 Shizuku——无法强制 Wi‑Fi 低延迟")
                            false
                        }
                    }
                },
                isMarked = { prefs.getBoolean(KEY_FORCED, false) },
                setMarker = { forced ->
                    // 必须在启用命令前持久化，进程在命令期间被杀时也能恢复。
                    prefs.edit().putBoolean(KEY_FORCED, forced).commit().also { saved ->
                        if (!saved) DebugFileLog.append(app, "WIFI: 保存低延迟恢复标记失败")
                    }
                },
                setForced = { forced ->
                    val mode = if (forced) "enabled" else "disabled"
                    val result = Shell.exec("cmd wifi force-low-latency-mode $mode")
                    if (result.ok) {
                        val message = if (forced) "已通过 Shizuku 强制 Wi‑Fi 低延迟" else "已恢复 Wi‑Fi 低延迟自动管理"
                        DebugFileLog.append(app, "WIFI: $message")
                    } else {
                        DebugFileLog.append(app, "WIFI: 设置 Wi‑Fi 低延迟失败 - ${result.output.take(120)}")
                    }
                    result.ok
                },
            )
        reconciler = created
        runCatching {
            rikka.shizuku.Shizuku.addBinderReceivedListener { created.retry() }
        }
        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, result ->
                if (result == PackageManager.PERMISSION_GRANTED) created.retry()
            }
        }
        return created
    }
}
