package com.mcarplay.app.usb

/**
 * root usbmux 进程与应用之间传递 iAP2 `0x4301 CarPlayStartSession` 的状态文件格式。
 *
 * 车机（DiPlay/LIVI 路径）不在 USB 网卡上发布 mDNS，而是在 iAP2 里给出 AirPlay 端口与
 * USB 网卡上的 IPv6 地址；应用读到后直接连接，mDNS 只作为 MacPlay 的回退。
 * 每行一个 `key=value`，`address` 可重复；未知键忽略，便于以后追加字段。
 */
object WiredCarPlayStartFile {
    private const val MAX_ADDRESSES = 8
    private val SAFE_VALUE = Regex("[A-Za-z0-9:._%-]{1,512}")

    fun encode(start: WiredIap2Session.CarPlayStart): String =
        buildString {
            append("port=").append(start.port).append('\n')
            start.addresses.filter(SAFE_VALUE::matches).take(MAX_ADDRESSES).forEach {
                append("address=").append(it).append('\n')
            }
            start.deviceIdentifier?.takeIf(SAFE_VALUE::matches)?.let { append("device=").append(it).append('\n') }
            start.publicKey?.takeIf(SAFE_VALUE::matches)?.let { append("publicKey=").append(it).append('\n') }
            start.sourceVersion?.takeIf(SAFE_VALUE::matches)?.let { append("sourceVersion=").append(it).append('\n') }
        }

    /** 端口无效或没有地址时返回 null；地址去掉 scope，由调用方按 USB 网卡补齐。 */
    fun decode(text: String): WiredIap2Session.CarPlayStart? {
        var port = 0
        val addresses = mutableListOf<String>()
        var device: String? = null
        var publicKey: String? = null
        var sourceVersion: String? = null
        for (line in text.lineSequence()) {
            val key = line.substringBefore('=', "").trim()
            val value = line.substringAfter('=', "").trim()
            if (key.isEmpty() || !SAFE_VALUE.matches(value)) continue
            when (key) {
                "port" -> port = value.toIntOrNull() ?: 0
                "address" -> value.substringBefore('%').takeIf { it.isNotEmpty() }?.let(addresses::add)
                "device" -> device = value
                "publicKey" -> publicKey = value
                "sourceVersion" -> sourceVersion = value
            }
        }
        if (port !in 1..65535 || addresses.isEmpty()) return null
        return WiredIap2Session.CarPlayStart(
            addresses = addresses.distinct().take(MAX_ADDRESSES),
            port = port,
            deviceIdentifier = device,
            publicKeyLength = publicKey?.length ?: 0,
            sourceVersion = sourceVersion,
            publicKey = publicKey,
        )
    }
}
