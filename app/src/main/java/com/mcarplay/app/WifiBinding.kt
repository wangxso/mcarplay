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
