package com.mcarplay.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * mDNS 服务发现：找到接收端（车机/xcertplay）广播的 _airplay._tcp，
 * 解析出 AirPlay 端点与 TXT 记录（deviceid/features/pi/pk/model…）。
 *
 * 真实时序中这一步发生在加入车机热点之后、发起 AirPlay 主会话之前。
 */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    /** 结果验收：只缓存/上报真正的车机端点（手机双归属时 mDNS 会混入其它 AirPlay 设备）。 */
    private val accept: (NsdServiceInfo) -> Boolean = { true },
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discovering = false

    /**
     * 已解析到的 `_airplay._tcp`（本网段）。在加入车机热点的瞬间就开始后台发现，
     * 到真正需要端点地址时通常已命中——对照 catplay：它用常驻 Bonjour cache +
     * `watch`，收到 ctrl 邀请时地址现成；我们此前才在配对前现查，白等好几秒。
     */
    @Volatile
    private var resolved: NsdServiceInfo? = null

    /** 新会话开始时清掉上一轮缓存（车机可能换了地址/端口）。 */
    fun resetCache() {
        resolved = null
    }

    /**
     * 等已解析结果（轮询缓存）。返回 null = 超时仍未发现。
     * 调用方通常在等待期间继续做别的事（绑定网络、握手），不是纯粹的阻塞 sleep。
     */
    fun awaitEndpoint(timeoutMillis: Long): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resolved?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return resolved
            }
        }
        return resolved
    }

    @Synchronized
    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovering) {
            log("$TAG: 已在扫描中")
            return
        }
        log("$TAG: 开始发现 $serviceType")
        val listener = newDiscoveryListener()
        discoveryListener = listener
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        discovering = true
    }

    /**
     * 清缓存后重新发现。NSD 只在首次看到服务时回调 onServiceFound：扫描一直开着时清掉缓存，
     * 车机同一条 `_airplay._tcp` 不会再报上来，awaitEndpoint 只能干等
     * （帕萨特 2026-11-01 23:02：「已在扫描中」后 3 s「mDNS 未发现」）。停掉再开一轮才会重报。
     */
    @Synchronized
    fun restart(serviceType: String = AIRPLAY_TYPE) {
        resetCache()
        if (discovering) {
            stop()
            log("$TAG: 已清缓存，重新发现以重报现有服务")
        }
        start(serviceType)
    }

    @Synchronized
    fun stop() {
        if (!discovering) return
        discoveryListener?.let { listener -> runCatching { nsdManager.stopServiceDiscovery(listener) } }
        discoveryListener = null
        discovering = false
        log("$TAG: 停止发现")
    }

    // 每轮发现一个新监听器：stopServiceDiscovery 是异步的，旧监听器未注销前复用会抛「listener already in use」
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private fun newDiscoveryListener() =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                if (discoveryListener === this) {
                    discoveryListener = null
                    discovering = false
                }
                log("$TAG: 发现启动失败 code=$errorCode")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                log("$TAG: 停止失败 code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("$TAG: 发现服务 ${serviceInfo.serviceName} (${serviceInfo.serviceType})，解析中…")
                resolveWithRetry(serviceInfo, attempts = 3)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                log("$TAG: 服务消失 ${serviceInfo.serviceName}")
            }
        }

    private fun resolveWithRetry(
        serviceInfo: NsdServiceInfo,
        attempts: Int,
    ) {
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts > 1) {
                        Thread {
                            Thread.sleep(200)
                            resolveWithRetry(info, attempts - 1)
                        }.start()
                    } else {
                        log("$TAG: 解析失败 ${info.serviceName} code=$errorCode")
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: "no-host"
                    val txt =
                        info.attributes.entries.joinToString(", ") { (key, value) ->
                            val text =
                                value
                                    ?.toString(Charsets.UTF_8)
                                    ?.trimEnd { it.code == 0 }
                                    .orEmpty()
                            "$key=$text"
                        }
                    // 验收放在缓存之前：非车机端点（如同一 WiFi 上的 Mac）不得污染 awaitEndpoint
                    val accepted = accept(info)
                    log("$TAG: OK ${info.serviceName} -> $host:${info.port}${if (accepted) "" else "（非车机，忽略）"}")
                    if (txt.isNotEmpty()) log("$TAG: TXT $txt")
                    if (accepted) resolved = info
                    onEndpoint(info)
                }
            }
        nsdManager.resolveService(serviceInfo, listener)
    }
}
