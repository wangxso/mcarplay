package com.mcarplay.app

import com.mcarplay.app.net.WifiIdentity

/**
 * 网络绑定目标选择（纯逻辑）：只允许绑定调用方**已核验**的 Network 句柄。
 *
 * 两张 Wi-Fi 并存时，通配 `requestNetwork(TRANSPORT_WIFI)` 的回调可能先把另一张
 * 网络交给进程；随后会话层会把车机 SSID 记到错的网络上并重注册 ctrl，AirPlay 便
 * 走了错误网络。因此拿不到唯一句柄时只能是 [Decision.NeedsConfirmation]，绝不能
 * 退化成通配绑定。
 */
internal object WifiBindPlan {
    sealed interface Decision<out N> {
        /** 进程已绑定同一 SSID 的网络，且没有核验到不同的句柄。 */
        data object AlreadyBound : Decision<Nothing>

        /** 只绑定这个已核验的 Network 句柄。 */
        data class BindVerified<N>(
            val network: N,
        ) : Decision<N>

        /** 拿不到唯一的 Network 句柄：转用户确认/手动加入，不发起通配请求。 */
        data object NeedsConfirmation : Decision<Nothing>

        /** 调用方要求当前网络必须是某个已核验句柄，但它已经变化。 */
        data object Rejected : Decision<Nothing>
    }

    fun <N : Any> decide(
        ssid: String,
        boundSsid: String?,
        verifiedNetwork: N?,
        currentNetwork: N?,
        requiredCurrent: N? = null,
    ): Decision<N> =
        when {
            requiredCurrent != null && currentNetwork != requiredCurrent -> Decision.Rejected
            boundSsid == ssid && currentNetwork != null && (verifiedNetwork == null || currentNetwork == verifiedNetwork) ->
                Decision.AlreadyBound
            verifiedNetwork != null -> Decision.BindVerified(verifiedNetwork)
            else -> Decision.NeedsConfirmation
        }
}

/**
 * 用户确认弹窗展示的网络身份快照。
 *
 * 弹窗返回后必须核对仍是同一网络：用户在弹窗期间切换到另一张 Wi-Fi 时，若直接
 * 绑定新的 current，就会把「A 的确认」套到 B 上，SSID 与 Network 句柄错配。
 * 句柄可比时以句柄为准；读不到句柄时退回可读 SSID 比对；两者都没有则认为已变化。
 */
internal data class WifiNetworkToken<N : Any>(
    val network: N?,
    val ssid: String?,
) {
    val hasIdentity: Boolean get() = network != null || ssid != null

    fun matches(
        currentNetwork: N?,
        currentSsid: String?,
    ): Boolean =
        when {
            network != null -> currentNetwork == network
            ssid != null -> currentSsid != null && WifiIdentity.matches(ssid, currentSsid)
            else -> false
        }
}

/**
 * 目标 SSID 可安全绑定的 `Network` 句柄（无定位权限时的唯一性证明）。
 *
 * Android 13+ 上本应用只申请 `NEARBY_WIFI_DEVICES`，没有定位权限时
 * `verifiedWifiNetwork` 恒为 null（它靠 `FLAG_INCLUDE_LOCATION_INFO` 同时读句柄与 SSID）。
 * 此时唯一的依据是「Shizuku 读到的站端 SSID 是目标 + 系统只有这一张 Wi-Fi」。
 *
 * 句柄与 SSID 来自两次不同的系统查询，**一次快照不足以证明它们属于同一张网络**：
 * 手机恰在这两次查询之间从 A 换到 B 时，会把 A 的句柄贴上 B 的 SSID。因此要求
 * 连续两次采样都成立、且两次拿到的是同一个句柄（[confirmed]）。
 */
internal object VerifiedWifiSelection {
    /** 一次采样：系统唯一 Wi-Fi 的句柄 + 同时可读的站端 SSID。 */
    data class Sample<N : Any>(
        val network: N?,
        val ssid: String?,
    )

    fun <N : Any> choose(
        ssid: String,
        locationVerified: N?,
        singleWifi: N?,
        liveSsid: String?,
    ): N? =
        locationVerified
            ?: singleWifi?.takeIf { WifiIdentity.matches(ssid, liveSsid) }

    /**
     * 连续两次采样都指向目标 SSID、且句柄相同才采信；任一采样不成立（换网、出现第二张
     * Wi-Fi、SSID 读不到）都返回 null，交回用户确认。
     */
    fun <N : Any> confirmed(
        ssid: String,
        first: Sample<N>,
        second: Sample<N>,
    ): N? {
        val a = first.network?.takeIf { WifiIdentity.matches(ssid, first.ssid) } ?: return null
        val b = second.network?.takeIf { WifiIdentity.matches(ssid, second.ssid) } ?: return null
        return a.takeIf { it == b }
    }
}
