package com.mcarplay.app.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import androidx.annotation.WorkerThread
import com.mcarplay.app.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 用车机热点承载 CarPlay 流量。
 *
 * **两种入网方式（2026-10-22 起默认静默命令行）**：
 *  - 静默路径（默认，见 `SessionController.joinCarWifiSilently`）：Shizuku 执行
 *    `cmd wifi connect-network`，系统直接把热点连上（**0 弹窗**），随后
 *    [bindCurrentWifi] 用**不带 specifier** 的 `requestNetwork` 捕获 `Network` 句柄并绑定。
 *  - [joinCarPlayNetwork]：`WifiNetworkSpecifier` 应用专属网络——静默路径不可用时的兜底。
 *    网络只对本进程可见（不保存、不成为默认网络），但首次加入会弹系统确认框。
 *
 * 关键点：
 *  - 请求不带 `NET_CAPABILITY_INTERNET`，系统不会因「无互联网」拆连接；
 *  - 两条路径都必须 `bindProcessToNetwork` 后再建 socket / 注册 mDNS——mDNS 广播
 *    (`NsdManager.registerService`) 没有 Network 参数，只能跟随进程默认网络；
 *  - SSID 必须精确匹配（热点名可能带空格/中文）：静默路径用 `cmd wifi status` 核对。
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val binding =
        NetworkBinding<Network, ConnectivityManager.NetworkCallback>(
            bindProcess = { connectivityManager.bindProcessToNetwork(it) },
            isUsable = { network ->
                val live =
                    runCatching {
                        connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    }.getOrDefault(false)
                if (!live) log("WiFi: 已丢弃失效的网络绑定（$network），下次连接将重新入网")
                live
            },
        )

    val currentNetwork: Network? get() = binding.current()

    /**
     * 应用专属网络被系统断开（热点关闭/离开范围）时回调。会话层据此提示用户；
     * 空实现 = 不关心（例如手动绑定路径）。
     */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * 当前绑定网络的网关地址（车机热点场景 = 车机 IP）。
     * mDNS 发现失败时的兜底路径。
     */
    fun gatewayAddress(): String? {
        val network = currentNetwork ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 本机在当前绑定网络上的 IPv4 地址（如 10.174.189.57）。
     * 用于 mDNS 结果过滤：手机双归属（连车机热点 + 自己开热点共享给电脑）时，
     * mDNS 会跨网发现电脑的 AirPlay 接收器——非本网段地址一律不是车机。
     */
    fun localIpv4Address(): String? {
        val network = currentNetwork ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads existing Wi-Fi identities without requesting another connection.
     * Android 12+ always redacts SSIDs from synchronous getNetworkCapabilities;
     * location permission plus FLAG_INCLUDE_LOCATION_INFO are required here.
     */
    @WorkerThread
    fun currentWifiSsid(): String? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val observer =
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val ssid = WifiIdentity.normalizeSsid((capabilities.transportInfo as? WifiInfo)?.ssid) ?: return
                    if (result.compareAndSet(null, ssid)) latch.countDown()
                }
            }
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        return try {
            connectivityManager.registerNetworkCallback(request, observer)
            latch.await(1_000, TimeUnit.MILLISECONDS)
            result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connectivityManager.unregisterNetworkCallback(observer) }
        }
    }

    /** Match a live system Wi-Fi without opening the specifier confirmation dialog. */
    fun isConnectedTo(ssid: String): Boolean = WifiIdentity.matches(ssid, currentWifiSsid())

    /** Availability only; does not infer the identity of a Wi-Fi whose SSID is redacted. */
    fun hasExistingWifi(): Boolean =
        runCatching {
            connectivityManager.allNetworks.any {
                connectivityManager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrDefault(false)

    /**
     * 本机在当前绑定网络上的全部 IPv6 地址。
     *
     * mDNS 结果的网段验收用：奥迪 4xxx 的 `_airplay._tcp` 常只发链路本地 IPv6，
     * 旧过滤只按 IPv4 前缀判定，会把车机本身也当成「非本网段」丢掉（2026-10-19）。
     */
    fun localIpv6Addresses(): List<String> {
        val network = currentNetwork ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点——**静默路径不可用时的兜底**。
     * 首次加入会弹一次系统确认框；网络不保存、不成为默认网络。
     *
     * 阻塞直到连接成功/系统拒绝/超时（内部 200ms 分片，[isCancelled] 可即时退出）。
     * 成功后进程网络已绑定，调用方可直接注册 mDNS / 建 socket。
     *
     * 安全类型映射按 0x5703（xcertplay Iap2WirelessSecurity）：
     * 0=NONE 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 仅。
     * WEP 无对应 API（返回 [JoinResult.Unsupported]，调用方走旧路径）。
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
        /** 首窗无回调时的快速探测（毫秒级 shell 往返 + ≤3s 绑定，必须不阻塞重发）。 */
        quickFallback: (() -> Network?)? = null,
        /** 重发窗也无回调后的深度兜底（connect-network，10s 量级——本轮窗口已过，为下一轮省时间）。 */
        deepFallback: (() -> Network?)? = null,
        /** 参数为请求发出时刻（uptimeMillis）；返回 true 表示系统选择框正等用户选择。 */
        userChoicePending: ((Long) -> Boolean)? = null,
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        // 等待节奏按「车机窗口」设计（2026-10-23 奥迪 3315 真机日志定谳）：0x4301 后
        // 车机 ~15s 拆 BT、~21s 后 AirPlay 服务窗关闭（晚到即 ECONNREFUSED，BT 再健康
        // 也无法激活）——从 0x5703 起算的入网预算只有 ~9-14s。 specifier 挂起形态下
        // （「系统已保存该 SSID + 部分 ROM（Android 16/小米实测）」或 forget→request
        // 紧邻操作，请求**永久 pending** 零回调）各阶段的预算：
        //  ① 首窗 5s（正常入网实测 0.03~3s；5s 零回调即挂起特征）；
        //  ② 快速探测（quickFallback：系统已自己连上该 SSID 则直接绑定，≤3s）；
        //  ③ 重发 specifier（实测挂起后 release→重发 1.6s 即入网——**被验证有效的
        //     恢复手段，必须紧跟探测**），20s 窗；
        //  ④ 深度兜底（deepFallback：connect-network 直连，10s 量级）——本轮窗口必然
        //     已过，价值是让手机先挂上热点，下一轮（车机 0.17s 即重连 BT）走「已连着」近路。
        // 旧 20s 首窗的教训：第 1 轮首窗没走完车机就拆链（15.2s），排在首窗**之后**的
        // 任何兜底都轮不到执行；第 2 轮 20s+1.6s=21.6s 入网时 7000 端口已拒绝连接。
        // 系统选择框正在等用户时不按首窗撤回：挂起形态没有选择框，重发才有意义；框在屏
        // 时重发只会撤掉用户正在看的框（提示「该应用已取消选择设备的请求」）再弹一次，
        // 2026-10-27 小米实测。此时首窗延长到用户选择或关闭框，最长 timeoutMillis。
        val firstWindowMs = minOf(timeoutMillis, 5_000L)
        val secondWindowMs = minOf(timeoutMillis - firstWindowMs, 20_000L).coerceAtLeast(0)
        val unavailable = AtomicBoolean(false)
        var network =
            requestAndAwait(ssid, request, firstWindowMs, isCancelled, unavailable, userChoicePending, timeoutMillis)
        // 系统明确拒绝（onUnavailable：用户拒了授权框/热点不在范围）后重发无意义，
        // 只会再拒一次——与挂起（零回调）必须区分开。
        if (network == null && !isCancelled() && !unavailable.get() && secondWindowMs > 0) {
            release()
            if (!isCancelled()) {
                // 快速兜底成功 = 已绑定到车机热点（系统自动重连的场景），直接返回；
                // 对「系统已保存并连着热点」的车型重发 specifier 只会再挂（已保存
                // SSID 正是挂起条件之一），短路是正确行为。
                quickFallback?.invoke()?.let { fallback ->
                    log("WiFi: 快速兜底入网成功（$fallback）——跳过 specifier 重发")
                    return JoinResult.Joined(fallback)
                }
            }
            if (!isCancelled()) {
                log("WiFi: 首窗 ${firstWindowMs / 1000}s 零回调——specifier 可能挂起，重发网络请求（实测重发 1.6s 可入网）")
                network = requestAndAwait(ssid, request, secondWindowMs, isCancelled, unavailable)
            }
            if (network == null && !isCancelled() && !unavailable.get()) {
                release()
                if (!isCancelled()) {
                    deepFallback?.invoke()?.let { fallback ->
                        log("WiFi: 深度兜底入网成功（$fallback）")
                        return JoinResult.Joined(fallback)
                    }
                }
            }
        }
        if (network == null) {
            log("WiFi: 等待加入 \"$ssid\" 失败（首窗 ${firstWindowMs / 1000}s + 重发窗 ${secondWindowMs / 1000}s + 两级兜底）")
            release()
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** 发起一次 requestNetwork 并等待到窗口结束/成功/取消；调用方负责 release。 */
    private fun requestAndAwait(
        ssid: String,
        request: NetworkRequest,
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        unavailable: AtomicBoolean,
        userChoicePending: ((Long) -> Boolean)? = null,
        holdLimitMillis: Long = timeoutMillis,
    ): Network? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!binding.bind(this, network)) {
                        if (binding.isCurrent(this)) {
                            log("WiFi: 应用专属网络绑定失败（$network），网络可能已失效")
                            unavailable.set(true)
                            latch.countDown()
                        }
                        return
                    }
                    result.set(network)
                    log("WiFi: 已加入应用专属网络 \"$ssid\"（$network bindProcessToNetwork=true）——不影响其它应用联网")
                    latch.countDown()
                }

                override fun onUnavailable() {
                    if (!binding.isCurrent(this)) return
                    unavailable.set(true)
                    log("WiFi: 系统未允许加入 \"$ssid\"（用户拒绝或热点不在范围内）")
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (binding.lost(this, network)) {
                        log("WiFi: 应用专属网络 \"$ssid\" 已断开（$network）")
                        onNetworkLost?.invoke(ssid)
                    }
                }
            }
        // 先撤上一任回调：快速兜底的 bindCurrentWifi 与本请求共用 callback 槽位，
        // 直接覆盖会让旧 callback 滞留在 ConnectivityManager 里直到 disconnect。
        binding.replaceRequest(newCallback)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        val requestedAt = SystemClock.uptimeMillis()
        val accepted =
            try {
                connectivityManager.requestNetwork(request, newCallback)
                if (!binding.isCurrent(newCallback)) {
                    runCatching { connectivityManager.unregisterNetworkCallback(newCallback) }
                    return null
                }
                true
            } catch (error: Exception) {
                log("WiFi: 发起应用专属网络请求失败（${error.message}）——可能需要位置权限或系统不支持")
                false
            }
        if (!accepted) return null
        val deadline = requestedAt + timeoutMillis
        val holdDeadline = requestedAt + maxOf(timeoutMillis, holdLimitMillis)
        var holdLogged = false
        while (true) {
            val now = SystemClock.uptimeMillis()
            val holding = userChoicePending?.invoke(requestedAt) == true
            if (!shouldKeepWaiting(now, deadline, holdDeadline, holding)) break
            if (holding && now >= deadline && !holdLogged) {
                holdLogged = true
                log("WiFi: 系统选择框正等待用户选择——继续等待，不撤回重发")
            }
            if (isCancelled()) return null
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                return null
            }
        }
        return result.get()?.takeIf { it == currentNetwork }
    }

    /** 构造 specifier；WEP 或参数不合法返回 null（走旧路径）。 */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // 开放热点：无密码
                0 -> Unit
                // WEP：WifiNetworkSpecifier 无对应 API（现代车机不再使用）
                1 -> return null
                // WPA/WPA2 与 WPA3 过渡模式：过渡模式 AP 同时接受 WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 仅
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // 未知类型：按有密码的 WPA2 尝试（比直接放弃更可能成功）
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: 构造应用专属网络参数失败（${error.message}）")
            null
        }
    }

    /**
     * 绑定「当前已连接的 WiFi」——静默命令行连网成功后用它捕获 `Network` 句柄。
     *
     * 请求**不带 specifier**（只声明 TRANSPORT_WIFI、移除 INTERNET），所以系统不会弹
     * 任何确认框，也会把刚连上的车机 WiFi 立刻回调过来。热点无互联网时系统可能不把它
     * 设为默认网络，必须显式 bindProcessToNetwork，我们的 socket / mDNS 才会走 WiFi。
     * 带看门狗：WiFi 未连接时 requestNetwork 会无限静默挂起——5 秒无果给出明确指引
     * （请求保持挂起，用户加入热点后仍会自动完成绑定并回调）。
     */
    fun bindCurrentWifi(
        /** 绑定目标热点的 SSID——断开通知（[onNetworkLost]）需要它；未知则传 null（不断开通知）。 */
        ssidHint: String? = null,
        /** 指定时必须核对 live SSID，避免把另一张 Wi-Fi 当成同一局域网目标。 */
        expectedSsid: String? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        // 网络回调线程与 Bg 看门狗线程并发读写——原子标志保证可见性与单次投递
        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    tryBind(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    tryBind(network, capabilities)
                }

                override fun onUnavailable() {
                    if (!binding.isCurrent(this)) return
                    log("WiFi: 没有可用的WiFi连接——请先在系统设置里加入接收端热点")
                    if (reported.compareAndSet(false, true)) onResult(false)
                }

                override fun onLost(network: Network) {
                    if (binding.lost(this, network)) {
                        // 熄火/离开范围：解绑进程网络，否则所有 socket 继续指向死网络；
                        // 并通知会话层（英雄卡不能停在「已连接」）。
                        // 与 specifier 路径同语义：兜底入网（bindCurrentWifi）是奥迪 3315
                        // 的主路径，不转发 onLost 会停在假「已连接」拖到心跳超时才死
                        // （2026-10-23 审阅定谳）。
                        log("WiFi: 绑定的 WiFi 网络已断开（$network）")
                        ssidHint?.let { onNetworkLost?.invoke(it) }
                    }
                }

                private fun tryBind(
                    network: Network,
                    capabilities: NetworkCapabilities? =
                        connectivityManager.getNetworkCapabilities(network),
                ) {
                    if (reported.get() || !binding.isCurrent(this)) return
                    if (expectedSsid != null) {
                        val actual =
                            WifiIdentity.normalizeSsid(
                                (capabilities?.transportInfo as? WifiInfo)?.ssid,
                            )
                        if (!WifiIdentity.matches(expectedSsid, actual)) return
                    }
                    val bound = binding.bind(this, network)
                    log(
                        "WiFi: 已绑定现有WiFi网络 $network " +
                            "ssid=${expectedSsid ?: "unknown"} bindProcessToNetwork=$bound",
                    )
                    if (bound && reported.compareAndSet(false, true)) onResult(true)
                }
            }
        binding.replaceRequest(newCallback)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        connectivityManager.requestNetwork(request, newCallback)
        if (!binding.isCurrent(newCallback)) {
            runCatching { connectivityManager.unregisterNetworkCallback(newCallback) }
            return
        }
        // 提示性看门狗（**不决定成败**）：requestNetwork 在「手机尚未连上 WiFi」时
        // 会一直挂起，等系统关联+DCHP 完成才回调 onAvailable——实测用户手动加入
        // 热点时可达 10s+。这里只在 5s 时给用户一句指引，超时判定由调用方的
        // WIFI_BIND_WAIT_S 负责；不要在这里取消请求（回调晚到仍会正确绑定）。
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (!reported.get() && binding.isCurrent(newCallback)) {
                log("WiFi: 5 秒尚未绑定（手机可能还没连上热点）——请到 设置>WLAN 加入接收端热点，加入后本轮绑定会自动完成")
            }
        }
    }

    fun release() {
        binding.replaceRequest(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
    }

    companion object {
        /** 窗口内继续等；窗口已过但选择框仍在等用户时，等到 [holdDeadline]。 */
        internal fun shouldKeepWaiting(
            now: Long,
            deadline: Long,
            holdDeadline: Long,
            userChoicePending: Boolean,
        ): Boolean = now < deadline || (userChoicePending && now < holdDeadline)

        /** 本应用界面在请求发出后才被盖住，才视为系统选择框在等用户（之前已离开则无法区分）。 */
        internal fun isUserChoicePending(
            uiPausedAtMs: Long?,
            requestedAtMs: Long,
        ): Boolean = uiPausedAtMs != null && uiPausedAtMs >= requestedAtMs
    }
}

/** Stable SSID comparisons for live Same-LAN and hotspot networks. */
internal object WifiIdentity {
    fun normalizeSsid(raw: String?): String? {
        val value = raw?.removeSurrounding("\"")
        return value?.takeUnless {
            it.isEmpty() || it == "<unknown ssid>" || it == "0x" || it.matches(Regex("0x0+"))
        }
    }

    fun matches(
        expected: String?,
        actual: String?,
    ): Boolean {
        val expectedSsid = normalizeSsid(expected) ?: return false
        return expectedSsid == normalizeSsid(actual)
    }
}
