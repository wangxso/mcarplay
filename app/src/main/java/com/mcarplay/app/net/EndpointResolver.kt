package com.mcarplay.app.net

import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.iap2.WifiCredentialSource

/**
 * mDNS `_airplay._tcp` 已解析记录的只读窄视图。
 *
 * 生产实现由编排层对 `NsdServiceInfo` 做懒转发；单测使用普通 JVM 假对象，不依赖 Android NSD stub。
 */
internal interface DiscoveredEndpoint {
    val hostAddress: String?
    val port: Int

    fun txtBytes(key: String): ByteArray?
}

/**
 * [EndpointResolver.awaitCtrlInvite] 入口捕获的广播器只读视图。
 *
 * 三个属性各自实时读取被捕获实例的当前 volatile 值，不预先采样为原子快照。
 */
internal interface InviteView {
    val inviteCount: Int
    val lastInviteAtMillis: Long
    val advertisedAtMillis: Long
}

/**
 * 接收端 ctrl 邀请等待、mDNS `_airplay._tcp` 端点解析/刷新与晚到端点合并。
 *
 * 仅封装发现与端点补全算法，不持有连接/attempt/网络生命周期，也不直接读取车型预设或 UI 通道。
 */
internal class EndpointResolver(
    private val uptimeMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit,
    private val isConnectCancelled: () -> Boolean,
    private val captureInviteView: () -> InviteView?,
    private val awaitEndpoint: (Long) -> DiscoveredEndpoint?,
    private val forceAnnounce: (String) -> Unit,
    private val gatewayCandidates: () -> List<String>,
    private val directDiscoveryPolicy: (Iap2SenderSession.CarPlayEndpoint, DiscoveredEndpoint) -> Boolean,
    private val log: (String) -> Unit,
    private val resolveWaitMillis: Long,
) {
    /**
     * 等车机 ctrl 邀请（`GET /ctrl-int/1/connect`）——catplay 的连接触发点。
     *
     * 邀请意味着「车机已在 WiFi 上发现我们，且已把 AirPlay 端口打开」，此时连
     * AirPlay 才不会被 Connection refused。无邀请时可在发现 `_airplay._tcp` 后走直连；
     * 通用路径会先给公告及重公告留出宽限期。
     *
     * @return true = 收到邀请 / 已发现端点；false = 等待窗口耗尽或已取消/中断
     */
    fun awaitCtrlInvite(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        timeoutMillis: Long,
    ): Boolean {
        val advertiser = captureInviteView()
        val deadline = uptimeMillis() + timeoutMillis
        val startedAt = uptimeMillis()
        var lastLogAt = startedAt
        var reannouncedAt = 0L
        while (uptimeMillis() < deadline) {
            if (isConnectCancelled()) return false
            val invites = advertiser?.inviteCount ?: 0
            val now = uptimeMillis()
            val discovered = awaitEndpoint(0)
            val preferResolvedMdns =
                discovered != null &&
                    directDiscoveryPolicy(endpoint, discovered)
            when (
                CtrlInviteGate.decide(
                    nowMillis = now,
                    waitStartedAtMillis = startedAt,
                    lastInviteAtMillis = advertiser?.lastInviteAtMillis ?: 0L,
                    mdnsEndpointFound = discovered != null,
                    advertisedAtMillis = advertiser?.advertisedAtMillis ?: 0L,
                    reannouncedAtMillis = reannouncedAt,
                    preferResolvedMdns = preferResolvedMdns,
                )
            ) {
                CtrlInviteGate.Decision.INVITED -> {
                    log("CTRL: ✓ 收到车机邀请（${now - startedAt}ms，第 $invites 次）——立即连接 AirPlay")
                    return true
                }

                CtrlInviteGate.Decision.MDNS_FALLBACK -> {
                    log(
                        "CTRL: 已发现 _airplay._tcp，广播可见/重公告后 ${CtrlInviteGate.MDNS_GRACE_MS}ms 内无新鲜邀请" +
                            "（旧邀请 $invites 次不算，共等 ${now - startedAt}ms）——按旧机型直接连接 AirPlay",
                    )
                    return true
                }

                CtrlInviteGate.Decision.MDNS_DIRECT -> {
                    log("CTRL: ORA 03 N220346.2 已解析且等待邀请超时（${now - startedAt}ms）——直接连接 AirPlay")
                    return true
                }

                // catplay 实测：iPhone 的 Bonjour 公告常被车机丢弃——它在每次邀请前都
                // force_announce。这里补发一次；广播已可见时要等满宽限期，否则会把车机正要响应的那次公告注销掉。
                CtrlInviteGate.Decision.REANNOUNCE -> {
                    reannouncedAt = now
                    val advertisedAt = advertiser?.advertisedAtMillis ?: 0L
                    log(
                        "CTRL: " +
                            (if (advertisedAt > 0L) "广播可见 ${now - advertisedAt}ms 仍无邀请" else "${now - startedAt}ms 未确认广播可见") +
                            "——强制重新公告一次（catplay 同款 force_announce）",
                    )
                    forceAnnounce(endpoint.ssid)
                }

                CtrlInviteGate.Decision.WAIT -> Unit
            }
            if (now - lastLogAt > 1_200) {
                val advertisedAt = advertiser?.advertisedAtMillis ?: 0L
                log(
                    "CTRL: 等待车机 ctrl 邀请…（已等 ${now - startedAt}ms，" +
                        (if (advertisedAt > 0L) "广播已可见 ${now - advertisedAt}ms" else "广播尚未确认可见") + "）",
                )
                lastLogAt = now
            }
            try {
                sleepMillis(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        log("CTRL: ${timeoutMillis}ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）")
        return false
    }

    /**
     * 端点补全：优先吸收 mDNS 解析出的地址，再决定是否等待/兜底。
     *
     * 关键（2026-10-19 奥迪 4851 实车日志）：0x4301 只给了
     * `fe80::3ca8:64ff:fe7c:293e`（IPv6 链路本地），而 `_airplay._tcp` 解析出的是
     * `10.174.189.1:7000`——车机实际用 IPv4 通的。**不能因为 0x4301 带了 IPv6 就不看
     * mDNS**：那样会把唯一验证过的 IPv4 路径丢掉。这里先做一次「零等待」缓存合并，
     * 拿到 IPv4 就按 EndpointHosts 的优先级（IPv4 → IPv6）使用；缓存没命中才按需等。
     */
    fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
        onResolutionNeeded: () -> Unit = {},
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val cached = awaitEndpoint(0)
        if (cached != null) {
            effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)
        }
        val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        onResolutionNeeded()
        log(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），取 mDNS 车机 _airplay._tcp…")
        // 用常驻发现器的缓存（绑网时已开始后台发现，通常此时已命中）。
        // 此前现起一次发现并硬重试 3×6s：车机 mDNS 稳定时白等，不稳定时又拖满 18s。
        // 网段过滤（防误连同网 macOS 接收器）由 browser 的 accept 统一做。
        val info =
            awaitEndpoint(resolveWaitMillis) ?: run {
                // 兜底：热点场景车机 IP = 网关（Audi 实测 .1，但取网关更通用）。
                // mDNS 在部分网络/机型上不稳定（2026-10-11 实测车机 mDNS 时有时无）；
                // 网关地址 + 默认端口 7000 直接连，AirPlay 端口是固定约定。
                val gateways = gatewayCandidates()
                val gateway = gateways.firstOrNull()
                if (gateway != null) {
                    val others = if (gateways.size > 1) "；其余默认路由网关 ${gateways.drop(1).joinToString("、")}" else ""
                    log(">>> [$source] mDNS 未发现，兜底使用网关地址 $gateway:7000（车机热点网关=车机$others）")
                    return effective.copy(
                        ipAddresses = listOf(gateway) + effective.ipAddresses,
                        airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                        portIsFallback = needPort,
                    )
                }
                log(">>> [$source] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）")
                return effective
            }
        return mergeBrowserEndpoint(effective, info, source, needPort)
    }

    /**
     * 把一次 mDNS 解析结果并入端点：地址**追加到最前**（EndpointHosts 会按
     * IPv4 → 全局 IPv6 → 链路本地 IPv6 重排，顺序只影响同类型内部）。
     * 其余字段只在缺失时补（0x4301 的权威值不覆盖）。
     */
    fun mergeBrowserEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        info: DiscoveredEndpoint,
        source: String,
        needPort: Boolean,
    ): Iap2SenderSession.CarPlayEndpoint {
        var result = endpoint
        val address = info.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            log(">>> [$source] mDNS 解析到车机地址 $address:${info.port}")
        }
        if ((needPort || result.portIsFallback) && info.port > 0) {
            result = result.copy(airPlayPort = info.port, portIsFallback = false)
        }
        // TXT 公钥兜底（pair-verify 校验用；0x4301 缺失时只能从这里拿）
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        return result
    }

    /**
     * 配对循环的端点刷新器（非阻塞）：只读浏览器缓存的 `_airplay._tcp`，
     * 有则并入 IP/端口/公钥等字段。返回 null = 无新信息（配对循环继续用当前端点）。
     */
    fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if ((result.airPlayPort <= 0 || result.portIsFallback) && info.port > 0) {
            result = result.copy(airPlayPort = info.port, portIsFallback = false)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        log(
            ">>> [$source] 配对期间 mDNS 刷新端点 → " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    companion object {
        fun txtValue(
            info: DiscoveredEndpoint,
            key: String,
        ): String? =
            info
                .txtBytes(key)
                ?.toString(Charsets.UTF_8)
                ?.trimEnd { it.code == 0 }
                ?.takeIf { it.isNotEmpty() }

        /**
         * 晚到端点优先：车机明确告知的端口/公钥优先于 mDNS 合成值，
         * 但 IP 列表取并集（0x4301 可能不带 IPv4，Audi MMI 9310 实测即如此）。
         */
        fun preferEndpoint(
            late: Iap2SenderSession.CarPlayEndpoint,
            fallback: Iap2SenderSession.CarPlayEndpoint,
        ): Iap2SenderSession.CarPlayEndpoint {
            fun rank(source: WifiCredentialSource): Int =
                when (source) {
                    WifiCredentialSource.ACCESSORY -> 3
                    WifiCredentialSource.CURRENT_NETWORK -> 2
                    WifiCredentialSource.KNOWN_SSID -> 1
                    WifiCredentialSource.NONE -> 0
                }
            val wifiFromFallback =
                rank(fallback.wifiCredentialSource) > rank(late.wifiCredentialSource) &&
                    fallback.ssid.isNotBlank()
            return late.copy(
                ssid = if (wifiFromFallback) fallback.ssid else late.ssid,
                passphrase = if (wifiFromFallback) fallback.passphrase else late.passphrase,
                channel = if (wifiFromFallback) fallback.channel else late.channel,
                securityType = if (wifiFromFallback) fallback.securityType else late.securityType,
                airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
                portIsFallback = late.airPlayPort <= 0 && fallback.portIsFallback,
                ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
                publicKey = late.publicKey ?: fallback.publicKey,
                deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
                sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
                advertisedFeatures = late.advertisedFeatures ?: fallback.advertisedFeatures,
                wifiCredentialSource =
                    if (wifiFromFallback) fallback.wifiCredentialSource else late.wifiCredentialSource,
                receiverSignals =
                    if (late.receiverSignals == ReceiverProfile.Signals()) fallback.receiverSignals else late.receiverSignals,
            )
        }
    }
}
