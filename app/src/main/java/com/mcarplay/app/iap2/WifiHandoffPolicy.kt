package com.mcarplay.app.iap2

/** 端点到达后的下一步网络动作；所有“加入”动作都必须基于完整凭据或缓存。 */
internal enum class WifiHandoffAction {
    /** 车机明确下发的完整配置（含真正的开放热点）。 */
    JOIN_ACCESSORY,

    /** 目标车机与 SSID 都匹配的进程内缓存。 */
    JOIN_CACHED,

    /** 当前网络已核对为目标 SSID：只绑定，不重新加入。 */
    BIND_KNOWN,

    /** 新鲜 ctrl 邀请已证明当前网络就是车机网络：只绑定。 */
    BIND_CURRENT,

    /** 有当前 Wi-Fi，但读不到 SSID；必须让用户明确确认后才能绑定。 */
    ASK_CONFIRM_CURRENT,

    /** 无法确认当前网络归属：请用户到系统 WLAN 手动加入已知 SSID。 */
    ASK_JOIN_KNOWN,

    /** 没有可用信息，等待晚到端点或邀请。 */
    WAIT,

    /** 端点不属于当前连接轮次/目标。 */
    IGNORE,
}

/**
 * 纯决策：把端点来源、当前网络可见性和邀请新鲜度映射成网络动作。
 *
 * `liveSsidMatches = null` 表示系统没有给出可读 SSID（常见于未授予精确定位的手机），
 * 此时只有新鲜邀请或用户确认才能证明当前网络的归属。
 */
internal object WifiHandoffPolicy {
    fun decide(
        source: WifiCredentialSource,
        targetCurrent: Boolean,
        liveSsidMatches: Boolean?,
        liveWifiPresent: Boolean,
        freshInvite: Boolean,
        cachedCredentials: Boolean,
    ): WifiHandoffAction {
        if (!targetCurrent) return WifiHandoffAction.IGNORE
        return when (source) {
            WifiCredentialSource.ACCESSORY -> WifiHandoffAction.JOIN_ACCESSORY
            WifiCredentialSource.CURRENT_NETWORK -> WifiHandoffAction.BIND_CURRENT
            WifiCredentialSource.KNOWN_SSID ->
                when {
                    cachedCredentials -> WifiHandoffAction.JOIN_CACHED
                    liveSsidMatches == true -> WifiHandoffAction.BIND_KNOWN
                    liveSsidMatches == false -> WifiHandoffAction.ASK_JOIN_KNOWN
                    freshInvite && liveWifiPresent -> WifiHandoffAction.BIND_CURRENT
                    liveWifiPresent -> WifiHandoffAction.ASK_CONFIRM_CURRENT
                    else -> WifiHandoffAction.ASK_JOIN_KNOWN
                }
            WifiCredentialSource.NONE ->
                if (freshInvite && liveWifiPresent) {
                    WifiHandoffAction.BIND_CURRENT
                } else {
                    WifiHandoffAction.WAIT
                }
        }
    }
}
