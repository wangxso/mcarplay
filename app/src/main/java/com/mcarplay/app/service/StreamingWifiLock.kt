package com.mcarplay.app.service

import android.content.Context
import android.net.wifi.WifiManager

/**
 * 推流期间的 Wi‑Fi 低延迟锁：阻止无线进入激进省电（否则接收端 20s 内判死拆链）。
 *
 * 只在视频发送端启动后持有。低延迟模式在持锁应用位于前台时生效，会让系统 Wi‑Fi 扫描
 * 全部 `Scan aborted (-7)`；入网时系统选择框浮在本应用之上、本应用仍算前台，选择框因此
 * 无网络可选（2026-10-27 小米实测：mcarplay 前台扫描失败、按 Home 后立即恢复）。
 *
 * 锁屏后本应用不再前台，`WIFI_MODE_FULL_LOW_LATENCY` 的低延迟随之失效，无线回到省电，
 * 音频 UDP 在驱动里排队 → 锁屏听歌卡顿。因此在同一时机再持一把
 * `WIFI_MODE_FULL_HIGH_PERF`：它不依赖前台，息屏后仍禁止省电。两把锁都只在入网
 * 完成、视频启动后获取，不会再影响扫描。
 */
object StreamingWifiLock {
    private var lowLatency: WifiManager.WifiLock? = null
    private var highPerf: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (lowLatency?.isHeld == true && highPerf?.isHeld == true) return
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
        if (highPerf?.isHeld != true) {
            // HIGH_PERF 在 API 29 起标记废弃，但仍是息屏后禁止 Wi‑Fi 省电的有效模式；
            // LOW_LATENCY 的前台限制在部分机型上会随锁屏解除。
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            highPerf =
                runCatching {
                    wifi.createWifiLock(mode, "CarPlaySender:wifi-highperf").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.getOrNull()
        }
    }

    @Synchronized
    fun release() {
        runCatching { lowLatency?.release() }
        lowLatency = null
        runCatching { highPerf?.release() }
        highPerf = null
    }
}
