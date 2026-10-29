package com.mcarplay.app.usb

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** USB NCM 台架在 root shell 输出里的纯解析逻辑。 */
object UsbNcmDiscovery {
    data class PeerAddress(
        val host: String,
        val scope: String?,
    )

    data class LocalAddresses(
        val ipv6LinkLocal: String?,
        val ipv4: String?,
    ) {
        val mdnsBindAddresses: List<String>
            get() = listOfNotNull(ipv6LinkLocal, ipv4).distinct()
    }

    fun isLinkLocalIpv6(value: String): Boolean {
        if ('%' in value || !value.contains(':')) return false
        return runCatching { InetAddress.getByName(value) }.getOrNull() is Inet6Address &&
            runCatching { InetAddress.getByName(value).isLinkLocalAddress }.getOrDefault(false)
    }

    fun parseIfindex(output: String): Int? = output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull { it > 0 }

    fun parseInterfaceName(output: String): String? =
        output
            .lineSequence()
            .map { it.trim() }
            .firstNotNullOfOrNull { line -> Regex("(?:usb|ncm)[0-9]+").find(line)?.value }

    /** 解析 `/proc/net/if_inet6`：32 位十六进制地址、ifindex、前缀、scope、flags、网卡名。 */
    fun parseLocalLinkLocal(
        output: String,
        interfaceName: String,
    ): String? =
        output
            .lineSequence()
            .mapNotNull { raw ->
                val tokens = raw.trim().split(Regex("\\s+"))
                if (tokens.size < 6 || tokens.last() != interfaceName) return@mapNotNull null
                val compact = tokens.firstOrNull()?.takeIf { Regex("[0-9a-fA-F]{32}").matches(it) } ?: return@mapNotNull null
                val flags = tokens[4].toIntOrNull(16) ?: return@mapNotNull null
                // 0x40=TENTATIVE、0x08=DADFAILED；0x80 是 PERMANENT，
                // 不能把非永久地址误判为尚未完成 DAD。
                if (flags and 0x48 != 0) return@mapNotNull null
                val address =
                    compact
                        .chunked(4)
                        .joinToString(":")
                        .lowercase()
                address.takeIf(::isLinkLocalIpv6)
            }.firstOrNull()

    fun parseLocalIpv4(output: String): String? =
        output
            .lineSequence()
            .map { it.trim() }
            .firstNotNullOfOrNull { line ->
                if (!line.startsWith("IPV4=")) return@firstNotNullOfOrNull null
                normalizePeerAddress(line.removePrefix("IPV4="))
            }

    fun parseLocalAddresses(
        output: String,
        interfaceName: String,
    ): LocalAddresses =
        LocalAddresses(
            ipv6LinkLocal = parseLocalLinkLocal(output, interfaceName),
            ipv4 = parseLocalIpv4(output),
        )

    /** `ip neigh` 中已经可用的 IPv4/IPv6 USB 邻居，失败态与其它网卡结果不计入。 */
    fun parseNeighborAddresses(
        output: String,
        interfaceName: String,
        limit: Int = 8,
    ): List<String> =
        output
            .lineSequence()
            .mapNotNull { raw ->
                val tokens = raw.trim().split(Regex("\\s+"))
                val devIndex = tokens.indexOf("dev")
                if (devIndex <= 0 || tokens.getOrNull(devIndex + 1) != interfaceName) return@mapNotNull null
                val state = tokens.lastOrNull()?.uppercase().orEmpty()
                if (state == "FAILED" || state == "INCOMPLETE") return@mapNotNull null
                normalizePeerAddress(tokens.first())
            }.distinct()
            .take(limit.coerceAtLeast(0))
            .toList()

    /** mDNS / `ip neigh` / 手动输入统一成不带 scope 的数字地址。 */
    fun normalizePeerAddress(value: String): String? = parsePeerAddress(value)?.host

    fun parsePeerAddress(value: String): PeerAddress? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val closingBracket = trimmed.indexOf(']')
        val unbracketed =
            if (trimmed.startsWith("[") && closingBracket > 1) {
                val suffix = trimmed.substring(closingBracket + 1)
                if (suffix.isNotEmpty() && !suffix.startsWith('%')) return null
                trimmed.substring(1, closingBracket) + suffix
            } else {
                trimmed
            }
        val host = unbracketed.substringBefore('%').trim()
        val scope = unbracketed.substringAfter('%', "").trim().takeIf { it.isNotEmpty() }
        if (host.isEmpty()) return null
        val looksNumeric =
            host.count { it == '.' } == 3 &&
                host.all { it.isDigit() || it == '.' } ||
                ':' in host &&
                host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
        if (!looksNumeric) return null
        val parsed = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (parsed.isAnyLocalAddress || parsed.isLoopbackAddress || parsed.isMulticastAddress) return null
        if (!isUsableUsbAddress(parsed)) return null
        return PeerAddress(parsed.hostAddress ?: return null, scope)
    }

    /** mDNS 结果只接受 USB 链路本地地址，避免同一服务从 Wi-Fi 私有网段泄漏进来。 */
    fun endpointHostFromMdns(
        address: InetAddress,
        scopeId: Int,
    ): String? {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
        if (!address.isLinkLocalAddress) return null
        if (!isUsableUsbAddress(address)) return null
        if (address is Inet4Address) return address.hostAddress
        if (address is Inet6Address) {
            val scoped =
                if (address.isLinkLocalAddress) {
                    runCatching { Inet6Address.getByAddress(null, address.address, scopeId) }.getOrNull()
                } else {
                    address
                } ?: return null
            // JmDNS 给出的 hostAddress 可能带 %scope；内核路由参数不能带 scope。
            return scoped.hostAddress?.substringBefore('%')
        }
        return null
    }

    private fun isUsableUsbAddress(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> {
                val bytes = address.address
                address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    ((bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xff) in 64..127)
            }
            is Inet6Address -> address.isLinkLocalAddress || address.isSiteLocalAddress || isUniqueLocalIpv6(address)
            else -> false
        }

    private fun isUniqueLocalIpv6(address: Inet6Address): Boolean = (address.address.first().toInt() and 0xfe) == 0xfc

    /** root shell 参数引用；输入已限制为固定路径、网卡名和校验过的数字地址。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun formatLocalMac(bytes: ByteArray): String {
        require(bytes.size == 6)
        val normalized = bytes.copyOf()
        normalized[0] = ((normalized[0].toInt() and 0xfe) or 0x02).toByte()
        return normalized.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
    }
}
