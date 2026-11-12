package com.mcarplay.app.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.mcarplay.app.util.DebugFileLog

/**
 * 推流期间的 Wi‑Fi 低延迟锁：阻止无线进入激进省电（否则接收端 20s 内判死拆链）。
 *
 * 只在视频发送端启动后持有。低延迟模式在持锁应用位于前台时生效，会让系统 Wi‑Fi 扫描
 * 全部 `Scan aborted (-7)`；入网时系统选择框浮在本应用之上、本应用仍算前台，选择框因此
 * 无网络可选（2026-10-27 小米实测：mcarplay 前台扫描失败、按 Home 后立即恢复）。
 *
 * **Android 14+**：系统把 `WIFI_MODE_FULL_HIGH_PERF` 并入 `WIFI_MODE_FULL_LOW_LATENCY`，
 * 低延迟模式严格要求「前台 + 亮屏」；真锁屏后系统恢复省电，任何用户态 Wi‑Fi 锁都无法阻止。
 * 本应用支持的方式是「连接时真息屏 / 常亮+暗幕」（[com.mcarplay.app.ui.ScreenDimmer]：
 * 物理面板断电但系统保持交互），这样低延迟锁继续生效。API 33 及以下才额外持 HIGH_PERF 锁。
 */
object StreamingWifiLock {
    private var lowLatency: WifiManager.WifiLock? = null
    private var highPerf: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (lowLatency?.isHeld == true && (Build.VERSION.SDK_INT >= 34 || highPerf?.isHeld == true)) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (lowLatency?.isHeld != true) {
            lowLatency =
                runCatching {
                    wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CarPlaySender:wifi").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.getOrNull()
        }
        if (Build.VERSION.SDK_INT < 34 && highPerf?.isHeld != true) {
            // API 33 及以下 HIGH_PERF 仍能在息屏后禁止省电；34+ 已被系统并入 LOW_LATENCY，
            // 再持一把既无效又会让日志误报「高性能锁已持」，因此不再获取。
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            highPerf =
                runCatching {
                    wifi.createWifiLock(mode, "CarPlaySender:wifi-highperf").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.onFailure {
                    DebugFileLog.append(context, "WIFI: 高性能锁获取失败 - ${it.message}")
                }.getOrNull()
        }
        val note = if (Build.VERSION.SDK_INT >= 34) "；Android 14+ 低延迟需前台+亮屏）" else "）"
        DebugFileLog.append(
            context,
            "WIFI: 推流锁已持（低延迟=${lowLatency?.isHeld == true} 高性能=${highPerf?.isHeld == true}" + note,
        )
        if (Build.VERSION.SDK_INT >= 34 && WifiLowLatencyForce.supportedForBuild) {
            DebugFileLog.append(
                context,
                "WIFI: 真锁屏会关闭 Wi‑Fi 低延迟；正在尝试通过 Shizuku 强制（未运行/未授权时请用「连接时真息屏」）",
            )
            WifiLowLatencyForce.enable(context)
        }
    }

    @Synchronized
    fun release() {
        runCatching { lowLatency?.release() }
        lowLatency = null
        runCatching { highPerf?.release() }
        highPerf = null
        WifiLowLatencyForce.disable()
    }
}
