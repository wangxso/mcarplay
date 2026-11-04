package com.mcarplay.app.net

import com.mcarplay.app.shizuku.Shell
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 本机在车机热点上的 Wi-Fi MAC（会话 SETUP 的 `macAddress`）。
 *
 * 2026-11-02 艾瑞泽 8 车机日志：真 iPhone 在这里填自己的 Wi-Fi 地址（热点上的随机化地址），车机记为
 * `mAutoReConnectMacAddress`，用来把热点上的终端与会话对上；mcarplay 一直填占位 `aa:bb:cc:dd:ee:ff`。
 *
 * Android 11+ 普通应用拿不到硬件地址，依次尝试：接口硬件地址 → sysfs 直读 → Shizuku shell 读 sysfs →
 * 由 EUI-64 形式的链路本地 IPv6 反推。都失败返回 null（调用方保留旧值）。随机化地址的本地管理位是置位的，
 * 与蓝牙地址的校验不同，这里必须接受。
 */
object WifiMacResolver {
    data class Resolved(
        val mac: String,
        val source: String,
    )

    private val MAC_REGEX = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,32}")

    /** 与 [localAddress] 同一接口的 Wi-Fi MAC；须在后台线程调用（可能起 shell）。 */
    fun resolve(localAddress: InetAddress?): Resolved? {
        val nif = localAddress?.let { runCatching { NetworkInterface.getByInetAddress(it) }.getOrNull() } ?: return null
        val name = nif.name
        runCatching { nif.hardwareAddress }.getOrNull()?.let(::formatMac)?.let { return Resolved(it, "接口硬件地址") }
        if (name != null && INTERFACE_NAME.matches(name)) {
            runCatching { File("/sys/class/net/$name/address").readText() }.getOrNull()?.let(::parseMac)?.let {
                return Resolved(it, "sysfs")
            }
            runCatching { Shell.exec("cat /sys/class/net/$name/address", timeoutMs = 2_000).output }.getOrNull()?.let(::parseMac)?.let {
                return Resolved(it, "Shizuku 读 sysfs")
            }
        }
        val addresses = runCatching { nif.inetAddresses.toList() }.getOrDefault(emptyList())
        return addresses.firstNotNullOfOrNull(::macFromEui64)?.let { Resolved(it, "链路本地 IPv6（EUI-64）") }
    }

    /** 文本里第一个可用的单播 MAC，输出小写冒号格式。 */
    internal fun parseMac(text: String?): String? {
        val match = text?.let { MAC_REGEX.find(it)?.value } ?: return null
        val bytes = match.split(':').map { it.toInt(16).toByte() }.toByteArray()
        return formatMac(bytes)
    }

    /** 拒绝空值、全 0、系统占位 `02:00:00:00:00:00` 与组播地址；随机化（本地管理位）地址照收。 */
    internal fun formatMac(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size != 6) return null
        if (bytes.all { it.toInt() == 0 }) return null
        if (bytes[0].toInt() and 0x01 != 0) return null
        val mac = bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        return mac.takeUnless { it == "02:00:00:00:00:00" }
    }

    /** `fe80::xxxx:xxff:fexx:xxxx` 反推 MAC（翻转 U/L 位）；随机接口标识的地址返回 null。 */
    internal fun macFromEui64(address: InetAddress): String? {
        if (address !is Inet6Address) return null
        val b = address.address
        if ((b[0].toInt() and 0xFF) != 0xFE || (b[1].toInt() and 0xC0) != 0x80) return null
        if ((b[11].toInt() and 0xFF) != 0xFF || (b[12].toInt() and 0xFF) != 0xFE) return null
        return formatMac(byteArrayOf((b[8].toInt() xor 0x02).toByte(), b[9], b[10], b[13], b[14], b[15]))
    }
}
