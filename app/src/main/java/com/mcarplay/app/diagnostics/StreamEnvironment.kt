package com.mcarplay.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.PowerManager

/**
 * 推流期间的链路与设备状态快照（诊断用）：Wi-Fi 信号/频段/速率、温控、电量、亮屏。
 * 不记 SSID/BSSID 等标识；读取都是轻量 binder 调用，调用方仍应放在后台线程。
 */
object StreamEnvironment {
    fun describe(context: Context): String {
        val parts = ArrayList<String>()
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.boundNetworkForProcess ?: connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            val wifi = capabilities?.transportInfo as? WifiInfo
            if (wifi == null) {
                parts += "Wi-Fi=无（${if (capabilities == null) "无网络" else "非 Wi-Fi 传输"}）"
            } else {
                parts +=
                    "Wi-Fi rssi=${wifi.rssi}dBm ${wifi.frequency}MHz 标准=${wifi.wifiStandard} " +
                    "速率=${wifi.linkSpeed}Mbps tx=${wifi.txLinkSpeedMbps} rx=${wifi.rxLinkSpeedMbps}"
            }
            capabilities?.let {
                parts += "带宽 up=${it.linkUpstreamBandwidthKbps}kbps down=${it.linkDownstreamBandwidthKbps}kbps"
                parts += "validated=${it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
            }
        }.onFailure { parts += "Wi-Fi 读取失败 ${it.javaClass.simpleName}" }
        runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            parts += "温控=${thermalName(power.currentThermalStatus)} 亮屏=${power.isInteractive} 省电=${power.isPowerSaveMode}"
        }
        runCatching {
            val battery = context.getSystemService(BatteryManager::class.java)
            parts += "电量=${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}% 充电=${battery.isCharging}"
        }
        return parts.joinToString("；")
    }

    private fun thermalName(status: Int): String =
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "正常"
            PowerManager.THERMAL_STATUS_LIGHT -> "轻度"
            PowerManager.THERMAL_STATUS_MODERATE -> "中度"
            PowerManager.THERMAL_STATUS_SEVERE -> "严重"
            PowerManager.THERMAL_STATUS_CRITICAL -> "临界"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "关机"
            else -> status.toString()
        }
}
