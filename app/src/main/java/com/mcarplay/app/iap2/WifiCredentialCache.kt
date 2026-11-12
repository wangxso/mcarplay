package com.mcarplay.app.iap2

/**
 * 进程内车机热点凭据缓存。
 *
 * 键必须同时包含目标实例与 SSID：同名 SSID 可能属于另一台车机，不能仅按名称复用密码。
 * 密码只留在内存，不写日志、不落盘。
 */
internal class WifiCredentialCache {
    private data class Entry(
        val targetBinding: String,
        val configuration: Iap2SenderSession.WifiConfiguration,
    )

    private var entry: Entry? = null

    @Synchronized
    fun remember(
        targetBinding: String?,
        configuration: Iap2SenderSession.WifiConfiguration,
    ): Boolean {
        val target = targetBinding?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        // 首尾空格可以属于真实 SSID；只检查“全空白”，不得 trim 后写回配置。
        if (configuration.ssid.isBlank() || configuration.securityType !in 0..4) return false
        if (configuration.securityType != 0 && configuration.passphrase.isEmpty()) return false
        entry = Entry(target, configuration)
        return true
    }

    @Synchronized
    fun lookup(
        targetBinding: String?,
        ssid: String,
    ): Iap2SenderSession.WifiConfiguration? {
        val target = targetBinding?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ssid.isBlank()) return null
        val cached = entry ?: return null
        return cached.configuration.takeIf {
            cached.targetBinding == target && it.ssid == ssid
        }
    }

    @Synchronized
    fun clear() {
        entry = null
    }
}

/** 0x4301 的完整端点与 0x5703 走同一缓存键规则；仅 SSID 端点返回 false。 */
internal fun WifiCredentialCache.rememberEndpoint(
    targetBinding: String?,
    endpoint: Iap2SenderSession.CarPlayEndpoint,
): Boolean {
    val wifi = endpoint.accessoryWifiOrNull() ?: return false
    return remember(targetBinding, wifi)
}
