package com.mcarplay.app.usb

import java.net.Inet6Address
import java.net.InetAddress

/** USB NCM 台架在 root shell 输出里的纯解析逻辑。 */
object UsbNcmDiscovery {
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
                val address =
                    compact
                        .chunked(4)
                        .joinToString(":")
                        .lowercase()
                address.takeIf(::isLinkLocalIpv6)
            }.firstOrNull()

    /** root shell 参数引用；输入已限制为固定路径、网卡名和校验过的 IPv6。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun formatLocalMac(bytes: ByteArray): String {
        require(bytes.size == 6)
        val normalized = bytes.copyOf()
        normalized[0] = ((normalized[0].toInt() and 0xfe) or 0x02).toByte()
        return normalized.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
    }
}
