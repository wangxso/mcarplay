package com.mcarplay.app.net

/**
 * 车机 Wi-Fi 入网与进程网络绑定机制抽象（Phase 2c-1）。
 *
 * 生产环境由 [WifiCarPlayConnector] 实现（`N = android.net.Network`）；
 * JVM 单测可使用不依赖 Android `Network` 空壳的假实现（如 `FakeCarWifiLink<WifiId>`）。
 */
internal interface CarWifiLink<N : Any> {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult<out N : Any> {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined<out N : Any>(
            val network: N,
        ) : JoinResult<N>

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult<Nothing>

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult<Nothing>
    }

    enum class SystemWifiState {
        NONE,
        TARGET,
        OTHER,
        UNKNOWN,
    }

    val currentNetwork: N?

    /** 已核验 SSID 对应的网络句柄；只接受绑定网络或系统 active Wi-Fi。 */
    fun currentWifiNetwork(): N?

    /** 读取现有已连接 Wi-Fi 的 SSID；无定位权限或不可读时返回 null。 */
    fun currentWifiSsid(): String?

    /** 系统当前是否存在任意已连接 Wi-Fi 网络。 */
    fun hasExistingWifi(): Boolean

    /** 系统里唯一的一张 Wi-Fi 网络；两张及以上并存或无 Wi-Fi 时返回 null。 */
    fun singleWifiNetwork(): N?

    /** 独立于进程绑定读取系统默认 active Wi-Fi 状态。 */
    fun systemActiveWifiState(ssid: String): SystemWifiState

    /** 在同一次网络回调中同时取得目标网络句柄与其 SSID。 */
    fun verifiedWifiNetwork(ssid: String): N?

    /** 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点并绑定到本进程。 */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
        quickFallback: (() -> N?)? = null,
        deepFallback: (() -> N?)? = null,
        userChoicePending: ((Long) -> Boolean)? = null,
    ): JoinResult<N>

    /** 绑定当前已连接的 Wi-Fi 网络。 */
    fun bindCurrentWifi(
        ssidHint: String? = null,
        expectedSsid: String? = null,
        onResult: (Boolean) -> Unit = {},
    )

    /** 直接绑定已由调用方核验过的 [network]，不再发通配 requestNetwork。 */
    fun bindVerifiedNetwork(
        network: N,
        ssidHint: String? = null,
    ): Boolean

    /** 释放当前网络请求或回调并解绑进程网络。 */
    fun release()
}
