package com.mcarplay.app.usb

import com.mcarplay.app.iap2.Iap2SenderSession
import java.net.NetworkInterface

/**
 * USB NCM 媒体台架的显式端点；不进行 Wi-Fi 发现、iPhone 枚举或车机角色切换。
 *
 * 地址按调用方优先级保留，但 [com.mcarplay.app.airplay.AirPlayClient] 会在同一 USB
 * 端点内逐个尝试。它可以同时包含链路本地 IPv6 与 IPv4，因此 mDNS 只发布 AAAA/A
 * 其中一种时仍能建立媒体会话。
 */
class UsbMediaEndpoint(
    val interfaceName: String,
    rawAddresses: List<String>,
    val port: Int,
    val scopeId: Int? = null,
) {
    val receiver: Iap2SenderSession.CarPlayEndpoint
    val addresses: List<String>

    constructor(
        interfaceName: String,
        address: String,
        port: Int,
        scopeId: Int? = null,
    ) : this(interfaceName, listOf(address), port, scopeId)

    init {
        require(Regex("(?:usb|ncm)[0-9]+").matches(interfaceName)) { "只接受 USB/NCM 网卡" }
        require(port in 1..65535) { "端口不合法" }
        require(scopeId == null || scopeId > 0) { "USB 接口索引不合法" }
        val parsedAddresses =
            rawAddresses
                .map { raw ->
                    val normalized = UsbNcmDiscovery.parsePeerAddress(raw) ?: error("USB 端点地址无效：$raw")
                    val allowedScopes = setOfNotNull(interfaceName, scopeId?.toString())
                    require(normalized.scope == null || normalized.scope in allowedScopes) {
                        "USB 端点地址属于其它网卡：$raw"
                    }
                    normalized.host
                }.distinct()
                .take(MAX_ADDRESSES)
        require(parsedAddresses.isNotEmpty()) { "USB 端点地址无效" }
        addresses = parsedAddresses
        receiver =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "USB NCM bench",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = port,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses =
                    addresses.map { address ->
                        if (address.startsWith("fe80:", ignoreCase = true)) {
                            "$address%${scopeId ?: interfaceName}"
                        } else {
                            address
                        }
                    },
            )
    }

    fun requireActiveInterface() {
        val iface = NetworkInterface.getByName(interfaceName)
        if (scopeId != null) {
            // 未托管的 USB 网卡可能不在应用可枚举的 getifaddrs 结果中。root 台架传入
            // 内核 ifindex；套接字仍须在该 scope 完成真实连接，绝不回退到 Wi-Fi。
            require(iface == null || iface.index == scopeId) { "USB 网卡已更换，请重新取得接口索引" }
            return
        }
        require(iface != null && iface.isUp) { "USB 网卡尚未启动" }
        require(iface.inetAddresses.toList().isNotEmpty()) { "USB 网卡没有可用地址" }
    }

    private companion object {
        const val MAX_ADDRESSES = 8
    }
}
