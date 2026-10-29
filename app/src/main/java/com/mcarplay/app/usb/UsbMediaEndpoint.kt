package com.mcarplay.app.usb

import com.mcarplay.app.iap2.Iap2SenderSession
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** USB NCM 媒体台架的显式端点；不进行 Wi-Fi 发现、iPhone 枚举或车机角色切换。 */
class UsbMediaEndpoint(
    val interfaceName: String,
    address: String,
    port: Int,
    val scopeId: Int? = null,
) {
    val receiver: Iap2SenderSession.CarPlayEndpoint

    init {
        require(Regex("(?:usb|ncm)[0-9]+").matches(interfaceName)) { "只接受 USB/NCM 网卡" }
        require(port in 1..65535) { "端口不合法" }
        require(scopeId == null || scopeId > 0) { "USB 接口索引不合法" }
        require(':' in address && Regex("[a-fA-F0-9:]+").matches(address)) { "需要不带 scope 的 IPv6 地址" }
        val parsed = InetAddress.getByName(address)
        require(parsed is Inet6Address && parsed.isLinkLocalAddress) { "只接受 USB 链路本地地址" }
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
                ipAddresses = listOf("${parsed.hostAddress}%${scopeId ?: interfaceName}"),
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
        require(iface.inetAddresses.toList().any { it is Inet6Address && it.isLinkLocalAddress }) { "USB 网卡没有链路本地地址" }
    }
}
