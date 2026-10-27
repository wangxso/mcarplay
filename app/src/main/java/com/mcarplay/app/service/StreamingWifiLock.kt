package com.mcarplay.app.service

import android.content.Context
import android.net.wifi.WifiManager

/**
 * 推流期间的 Wi‑Fi 低延迟锁：阻止无线进入激进省电（否则接收端 20s 内判死拆链）。
 *
 * 只在视频发送端启动后持有。低延迟模式在持锁应用位于前台时生效，会让系统 Wi‑Fi 扫描
 * 全部 `Scan aborted (-7)`；入网时系统选择框浮在本应用之上、本应用仍算前台，选择框因此
 * 无网络可选（2026-10-27 小米实测：mcarplay 前台扫描失败、按 Home 后立即恢复）。
 */
object StreamingWifiLock {
    private var lock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (lock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock =
            runCatching {
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CarPlaySender:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
    }

    @Synchronized
    fun release() {
        runCatching { lock?.release() }
        lock = null
    }
}
