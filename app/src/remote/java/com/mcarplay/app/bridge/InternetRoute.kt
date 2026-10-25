package com.mcarplay.app.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 调试桥的互联网通道选择器：跟踪可用的蜂窝网络作为**兜底**。
 *
 * 为什么需要它（2026-10-21 DiPlay 实车会话定谳）：加入车机热点用的是
 * `WifiNetworkSpecifier` + 进程绑定网络——会话期间**本进程所有默认 socket 都路由到
 * 车机热点**，而热点没有互联网，调试桥的 WebSocket 在「加入热点」那一毫秒即断。
 * 多网络 socket 是 Android 的标准解法：从蜂窝 `Network.socketFactory` 建 socket、
 * DNS 也走该网络，与进程默认路由解耦，车机会话期间照常收日志。
 *
 * 选择策略见 [current]：默认网络已验证（正常 WiFi/蜂窝日常态）→ 走默认；
 * 未验证（车机热点）→ 蜂窝。跟踪蜂窝但不跟踪 WiFi 的原因：车机热点这类
 * specifier 网络同样可能上报 NET_CAPABILITY_INTERNET（未经过验证），
 * 按「WiFi 且有 INTERNET」挑会正好选中车机热点。
 */
class InternetRoute(
    context: Context,
) {
    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var cellular: Network? = null

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellular = network
            }

            override fun onLost(network: Network) {
                if (cellular == network) cellular = null
            }
        }

    fun start() {
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        cellular = null
    }

    /** 当前最优先的互联网通道；null = 进程默认网络可用，直接走系统默认路由。
     *
     *  默认网络带 VALIDATED（真通互联网，如家里/公司 WiFi）就优先它——无条件走
     *  蜂窝反而更差：室内 WiFi 环境蜂窝信号弱，15MB APK 实测中途断流
     * （unexpected end of stream，2026-10-21）。车机会话期间进程默认网络是
     *  车机热点（无互联网、不 VALIDATED），此时才退蜂窝。
     *  **但 VALIDATED 是缓存判定 ≠ 当前可达**（2026-10-22 实测：测试台热点无上行
     *  互联网却仍标 VALIDATED，重连每轮 ENETUNREACH 且永不换路）——默认网络近期
     *  连接失败过（[noteFailure]）即改走蜂窝；成功（[noteSuccess]）或默认网络
     *  更换（netId 变化，如会话结束解绑）时复位判定。 */
    fun current(): Network? {
        val active = cm.activeNetwork ?: return cellular
        // toString() 即 netId 的十进制串（公开途径拿网络身份）：默认网络更换
        // （会话结束解绑/切网）即视为新网络，失败判定复位
        val netKey = active.toString()
        if (netKey != lastActiveNetKey) {
            lastActiveNetKey = netKey
            defaultFailStreak = 0
        }
        val caps = cm.getNetworkCapabilities(active) ?: return cellular
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return cellular
        if (defaultFailStreak > 0) return cellular
        return null
    }

    /** 一次走默认网络的连接失败：连续失败即换蜂窝，直到网络变化或成功复位。 */
    fun noteFailure(usedDefaultNetwork: Boolean) {
        if (usedDefaultNetwork) defaultFailStreak++
    }

    /** 走默认网络的连接成功：清除失败判定，恢复优先默认的快速路径。 */
    fun noteSuccess(usedDefaultNetwork: Boolean) {
        if (usedDefaultNetwork) defaultFailStreak = 0
    }

    @Volatile
    private var defaultFailStreak = 0

    private var lastActiveNetKey: String? = null
}
