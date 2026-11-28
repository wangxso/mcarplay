package com.mcarplay.app.net

import androidx.annotation.StringRes
import com.mcarplay.app.R
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.WifiCredentialSource
import com.mcarplay.app.iap2.WifiHandoffAction
import com.mcarplay.app.iap2.WifiHandoffPolicy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [CarWifiJoiner] 向编排层输出阶段提示与惰性 Snackbar 提示的窄 UI 端口。
 *
 * `phase` 同步更新阶段副标题；`hint` 保持 `() -> String` 惰性传递，不提前求值。
 */
internal interface CarWifiUiPort {
    fun phase(hint: String?)

    fun hint(message: () -> String)
}

/**
 * [CarWifiJoiner] 发起两按钮确认弹窗的中性文案数据，由编排层转换为 `DialogSpec` 并接入单飞代理。
 */
internal data class CarWifiDialogPrompt(
    val title: String,
    val message: String,
    val positive: String,
    val negative: String,
)

/**
 * `cmd wifi` shell 命令执行结果的中性视图。
 */
internal data class CarWifiShellResult(
    val exitCode: Int,
    val output: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * [CarWifiJoiner] 执行 `cmd wifi` 命令的窄端口。
 */
internal fun interface CarWifiShellPort {
    fun exec(
        command: String,
        timeoutMs: Long,
    ): CarWifiShellResult
}

/**
 * 互联网网络路由偏好的引擎层中性表示（避免 `net/` 反向依赖 `settings/`）。
 */
internal enum class CarWifiInternetPreference {
    PHONE,
    CAR,
}

/** 端点网络分派的凭据来源（与 `WifiCredentialSource` 一一映射，net 不反向依赖 iap2）。 */
internal enum class CarWifiHandoffSource {
    ACCESSORY,
    KNOWN_SSID,
    CURRENT_NETWORK,
    NONE,
}

/** 等待已知/手动 SSID 时，上层消费晚到完整 0x4301 的窄端口。 */
internal interface CarWifiPendingEndpointPort {
    /** 等待轮询中发现完整 0x4301：先执行 [onClaimed] 记录原等待日志，再接替本轮连接并返回 true。 */
    fun tryContinueDuringWait(onClaimed: () -> Unit): Boolean

    /** 超时前最后一次发现完整 0x4301：先执行 [onClaimed] 记录原等待日志，再接替本轮连接并返回 true。 */
    fun tryContinueBeforeTimeout(onClaimed: () -> Unit): Boolean
}

/**
 * 端点交接的网络分派输入。
 *
 * scope、交接归属、端点对象与配对/推流续体仍由上层闭包持有；joiner 只读取中性来源、
 * 目标是否当前轮次、邀请新鲜度与缓存快照，再执行八个网络动作。
 */
internal class CarWifiHandoffRequest<N : Any>(
    val sourceLabel: String,
    val source: CarWifiHandoffSource,
    val ssid: String,
    val accessoryWifi: Iap2SenderSession.WifiConfiguration?,
    val cachedWifi: Iap2SenderSession.WifiConfiguration?,
    val verifiedCurrentNetwork: N?,
    val requireCurrentBinding: Boolean,
    val pendingEndpoint: CarWifiPendingEndpointPort?,
    val targetCurrent: () -> Boolean,
    val scopeActive: () -> Boolean,
    val cachedRetryCancelled: () -> Boolean,
    val handoffCancelled: () -> Boolean,
    val freshInvite: () -> Boolean,
    val continueWithNetwork: (Boolean) -> Unit,
    val releaseHandoff: () -> Unit,
    val clearCachedCredentials: () -> Unit,
    val inviteNetwork: () -> N? = { null },
    val sleep: ((Long) -> Unit)? = null,
    val awaitLatch: ((CountDownLatch, Long) -> Boolean)? = null,
    val detached: (() -> Boolean)? = null,
    val earlyJoin: CompletableFuture<Boolean>? = null,
)

/**
 * 车机热点四条凭据入网路径（静默 shell、应用专属网络 specifier、旧安全类型 legacy、手动 WLAN 指引）
 * 与手机流量优先失败引导（Phase 2c-2）。
 *
 * 严格保持原有锁（注入同一 `wifiHandoffGuard.lock`）、后台任务名、超时轮询边界、取消分层与全部日志模板。
 */
internal class CarWifiJoiner<N : Any, T : Any>(
    private val link: () -> CarWifiLink<N>?,
    private val state: CarWifiState,
    bindingLock: Any,
    private val globalCancelled: () -> Boolean,
    private val ui: CarWifiUiPort,
    private val text: (Int, Array<out Any>) -> String,
    private val askChoice: (CarWifiDialogPrompt, T?, () -> Boolean, (CountDownLatch, Long) -> Boolean) -> Boolean?,
    private val captureToken: () -> T?,
    private val matchesToken: (T, N?, String?) -> Boolean,
    private val shellReady: () -> Boolean,
    private val shell: CarWifiShellPort,
    private val readPreference: () -> CarWifiInternetPreference,
    private val useSystemWifi: (CarWifiInternetPreference, Boolean) -> Boolean = { preference, shizukuReady ->
        preference == CarWifiInternetPreference.CAR && shizukuReady
    },
    private val allowExistingSystemWifi: (CarWifiInternetPreference) -> Boolean = { preference ->
        preference == CarWifiInternetPreference.CAR
    },
    private val allowSystemFallback: (CarWifiInternetPreference) -> Boolean = { preference ->
        preference == CarWifiInternetPreference.CAR
    },
    private val bindVerifiedAndRegister: (String, N?, () -> Boolean, N?) -> Boolean,
    private val verifiedNetworkFor: (String) -> N?,
    private val reannounce: (String, Boolean, N?) -> Unit,
    private val background: (String, () -> Unit) -> Unit,
    private val uptimeMillis: () -> Long,
    private val sleep: (Long) -> Unit,
    private val awaitLatch: (CountDownLatch, Long) -> Boolean = { latch, ms -> latch.await(ms, TimeUnit.MILLISECONDS) },
    private val openWlanSettings: () -> Unit,
    private val copyHotspotPsk: (String) -> Unit,
    private val userChoicePending: (Long) -> Boolean = { false },
    private val log: (String) -> Unit,
    private val invitedWifiLabel: String,
) {
    private val wifiBindLock: Any = bindingLock

    private val wifiConnector: CarWifiLink<N>?
        get() = link()

    private val connectCancelled: Boolean
        get() = globalCancelled()

    private var boundCarSsid: String?
        get() = state.boundCarSsid
        set(value) {
            state.boundCarSsid = value
        }

    private val forgetPromptHandled: MutableSet<String>
        get() = state.forgetPromptHandled

    private var shellFallbackCleanupSsid: String?
        get() = state.shellFallbackCleanupSsid
        set(value) {
            state.shellFallbackCleanupSsid = value
        }

    private fun appendLog(message: String) = log(message)

    private fun t(
        @StringRes id: Int,
        vararg args: Any,
    ): String = text(id, args)

    private fun currentNetworkToken(): T? = captureToken()

    private fun askWifiJoinChoice(
        spec: CarWifiDialogPrompt,
        shownNetwork: T? = null,
        isCancelled: () -> Boolean = { connectCancelled },
        awaitLatch: (CountDownLatch, Long) -> Boolean = this.awaitLatch,
    ): Boolean? = askChoice(spec, shownNetwork, isCancelled, awaitLatch)

    /**
     * 校对真实 WiFi 并加入车机热点。按「互联网网络」偏好选择入网方式：
     *  - **车机网络优先且 Shizuku 就绪**时走静默路径：
     *  1. `cmd wifi set-wifi-enabled` + `cmd wifi connect-network`（Shizuku）：系统直接
     *     连上热点，**0 弹窗、0 用户操作**；热点被存进系统配置，下次进入范围自动重连；
     *  2. `requestNetwork(TRANSPORT_WIFI)`（**不带 specifier**）拿到 WiFi 的 `Network`
     *     句柄并绑定进程网络——没有 specifier 就不会弹系统确认框；
     *  3. 各环节检查 [connectCancelled]——用户取消后不得继续跑（否则 phaseHint 被覆盖、
     *     ctrl 广播被重新拉起，表现为「取消不了」）。
     *  - **手机流量优先**或静默路径失败时走应用专属网络（`WifiNetworkSpecifier`）：
     *    应用不主动保存热点或将其设为系统默认；首次加入会弹系统确认框。
     * 全失败时提示重试或检查系统 WLAN；车机网络模式可手动加入。
     */
    fun autoJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        cancelled: () -> Boolean = { connectCancelled },
        canPrompt: () -> Boolean = { !cancelled() },
        onResult: (Boolean) -> Unit = {},
    ) {
        fun run(): Boolean {
            if (cancelled()) return false
            val internetNetwork = preferredInternetNetwork()
            val shellReady = shellWifiReady()
            appendLog(
                "NETWORK: 互联网优先=${if (internetNetwork == CarWifiInternetPreference.CAR) "车机网络" else "手机流量"}" +
                    "（${if (internetNetwork == CarWifiInternetPreference.CAR) "允许系统级使用车机热点" else "车机热点仅绑定本进程"}）",
            )
            // 1) 首选静默路径：Shizuku 命令行连网（0 弹窗）
            if (useSystemWifi(internetNetwork, shellReady)) {
                val silent =
                    synchronized(wifiBindLock) {
                        if (cancelled()) return false
                        joinCarWifiSilentlyLocked(wifi, isCancelled = cancelled)
                    }
                if (silent is CarWifiLink.JoinResult.Joined) return true
                if (cancelled()) return false
                // 热点已连上、只是 Network 通道没绑成：绝不能退回 specifier——那会重新
                // 关联热点并弹系统确认框（用户看到「连上了又弹窗」）。
                // 有核验句柄就只绑它；拿不到句柄时转用户确认，绝不发起通配绑定。
                if (liveWifiSsid() == wifi.ssid) {
                    var bound = false
                    bindVerifiedCarWifi(wifi.ssid, "静默入网", isCancelled = cancelled) { bound = it }
                    if (bound) return true
                    ui.phase(t(R.string.sc_bind_failed))
                    appendLog(
                        "WIFI: 已连在 \"${wifi.ssid}\" 但未能绑定唯一的 Network 句柄——本轮中止" +
                            "（不退回会弹窗的 specifier，也不发起通配绑定）",
                    )
                    return false
                }
            }
            // 2) 回退：应用专属网络（不主动保存为系统网络）
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled(isCancelled = cancelled)) return false
            if (cancelled()) return false
            if (internetNetwork == CarWifiInternetPreference.PHONE && shellReady) {
                appendLog("NETWORK: 手机流量优先——跳过系统级存储与 connect-network 兜底")
            }
            val result =
                synchronized(wifiBindLock) {
                    if (cancelled()) return false
                    joinCarWifiSpecifier(
                        wifi,
                        allowExistingSystemWifi = allowExistingSystemWifi(internetNetwork),
                        allowSystemFallback = allowSystemFallback(internetNetwork),
                        isCancelled = cancelled,
                    )
                }
            if (cancelled()) return false
            when (result) {
                is CarWifiLink.JoinResult.Joined -> return true

                CarWifiLink.JoinResult.Unsupported -> {
                    if (internetNetwork == CarWifiInternetPreference.CAR) {
                        appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                        legacyJoinCarWifi(wifi, shellReady, isCancelled = cancelled)
                    } else {
                        appendLog("WIFI: 手机流量优先——该热点安全类型无法使用应用专属网络，请到 WLAN 手动加入")
                        promptSpecifierFailed(wifi, shellReady, isCancelled = { !canPrompt() })
                    }
                }

                CarWifiLink.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady, isCancelled = { !canPrompt() })
            }
            return !cancelled() && boundCarSsid == wifi.ssid && wifiConnector?.currentNetwork != null
        }

        background("wifi-auto-join") {
            var joined = false
            try {
                joined = run()
            } finally {
                onResult(joined)
            }
        }
    }

    /** 端点先于 0x5703 到达（或上一轮换网失败）时的入网入口，遵循「互联网网络」偏好。 */
    fun joinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
    ): CarWifiLink.JoinResult<N> {
        val internetNetwork = preferredInternetNetwork()
        if (!useSystemWifi(internetNetwork, shellReady)) {
            return joinCarWifiSpecifier(
                wifi,
                allowExistingSystemWifi = allowExistingSystemWifi(internetNetwork),
                allowSystemFallback = allowSystemFallback(internetNetwork),
                sleepFn = sleepFn,
                awaitLatchFn = awaitLatchFn,
                isCancelled = isCancelled,
            )
        }
        val silent = joinCarWifiSilently(wifi, sleepFn, isCancelled)
        if (silent is CarWifiLink.JoinResult.Joined) return silent
        // 热点已连上但通道没绑成：不再走会弹窗的 specifier（见 autoJoinCarWifi 同款判断）；
        // 只接受核验过的 Network 句柄，拿不到时请用户确认当前网络。
        if (!isCancelled() && liveWifiSsid() == wifi.ssid) {
            var bound = false
            bindVerifiedCarWifi(wifi.ssid, "静默入网", isCancelled, sleepFn, awaitLatchFn) { bound = it }
            val boundNetwork = wifiConnector?.currentNetwork
            return if (bound && boundNetwork != null) CarWifiLink.JoinResult.Joined(boundNetwork) else silent
        }
        return joinCarWifiSpecifier(wifi, sleepFn = sleepFn, awaitLatchFn = awaitLatchFn, isCancelled = isCancelled)
    }

    private fun preferredInternetNetwork(): CarWifiInternetPreference = readPreference()

    /**
     * 静默加入车机热点（**两步式，全程 0 弹窗**）。
     *
     * 第一步：Shizuku（uid 2000）执行 `cmd wifi connect-network "<SSID>" wpa2 "<密码>"`，
     * 系统直接关联热点并把它存成普通网络——不经过 `WifiNetworkSpecifier`，
     * 因此**不触发系统确认框**（specifier 的弹窗是框架层强制行为，应用关不掉）。
     *
     * 第二步：用**不带 specifier** 的 `requestNetwork(TRANSPORT_WIFI)` 捕获刚连上的
     * WiFi 的 `Network` 句柄并绑定进程网络（[bindVerifiedAndRegister]）。没有 specifier，
     * 系统不会弹窗，也能立刻把车机 WiFi 的 `Network` 回调给我们。
     */
    fun joinCarWifiSilently(
        wifi: Iap2SenderSession.WifiConfiguration,
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
    ): CarWifiLink.JoinResult<N> {
        // 与 specifier 兜底路径共用同一把锁：两条路径都会 release + 重绑进程网络。
        // 持锁实现见 [joinCarWifiSilentlyLocked]。
        return synchronized(wifiBindLock) { joinCarWifiSilentlyLocked(wifi, sleepFn, isCancelled) }
    }

    /** [joinCarWifiSilently] 的持锁实现（调用方必须持有 [wifiBindLock]）。 */
    fun joinCarWifiSilentlyLocked(
        wifi: Iap2SenderSession.WifiConfiguration,
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
    ): CarWifiLink.JoinResult<N> {
        val connector = wifiConnector ?: return CarWifiLink.JoinResult.Unavailable
        if (isCancelled()) return CarWifiLink.JoinResult.Unavailable
        val existing = connector.currentNetwork
        if (boundCarSsid == wifi.ssid && existing != null) {
            appendLog("WIFI: 车机热点已就绪且网络通道已绑定——跳过重复加入")
            return CarWifiLink.JoinResult.Joined(existing)
        }
        if (!ensureWifiEnabledSilently(sleepFn, isCancelled)) return CarWifiLink.JoinResult.Unavailable
        if (isCancelled()) return CarWifiLink.JoinResult.Unavailable
        val current = liveWifiSsid()
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已连在车机热点 \"${wifi.ssid}\"——直接建立网络通道并绑定")
        } else {
            ui.phase(t(R.string.sc_silent_join, wifi.ssid))
            appendLog(
                ">>> 静默加入车机热点 \"${wifi.ssid}\"（cmd wifi connect-network，0 弹窗；" +
                    "当前 ${wifiForLog(current, "未连接")}）",
            )
            // 命令把连接投给 WifiService 就返回，exit=0 不代表已连上 —— 必须轮询 SSID
            val result =
                shell.exec(
                    WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                    20_000L,
                )
            if (!result.ok) {
                appendLog(
                    "WIFI: connect-network 失败（exit=${result.exitCode} " +
                        "${result.output.take(80)}）",
                )
                return CarWifiLink.JoinResult.Unavailable
            }
            if (!waitForCarSsid(wifi.ssid, CAR_WIFI_JOIN_WAIT_MS, sleepFn, isCancelled)) {
                return CarWifiLink.JoinResult.Unavailable
            }
        }
        if (isCancelled()) return CarWifiLink.JoinResult.Unavailable
        // SSID 已核对，但必须拿到同一张 Wi-Fi 的 Network 句柄再绑：通配 requestNetwork
        // 在两张 Wi-Fi 并存时可能先返回另一张（句柄交给用户确认路径去取）。
        val verified = verifiedNetworkFor(wifi.ssid)
        if (verified == null) {
            appendLog("WIFI: 已连在车机热点但拿不到核验过的 Network 句柄——转用户确认，不发起通配绑定")
            return CarWifiLink.JoinResult.Unavailable
        }
        val bound = bindVerifiedAndRegister(wifi.ssid, verified, isCancelled, null)
        val network = connector.currentNetwork
        return if (bound && network != null) {
            CarWifiLink.JoinResult.Joined(network)
        } else {
            CarWifiLink.JoinResult.Unavailable
        }
    }

    /**
     * 绑定已核对 SSID 的目标网络：只使用 [verifiedNetworkFor] 返回的同一 Network 句柄。
     * 拿不到句柄时请用户确认当前网络（[confirmCurrent]），绝不回退到通配 requestNetwork。
     */
    private fun bindVerifiedCarWifi(
        ssid: String,
        source: String,
        isCancelled: () -> Boolean = { connectCancelled },
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        onResult: (Boolean) -> Unit,
    ) {
        val verified = verifiedNetworkFor(ssid)
        if (verified != null) {
            onResult(bindVerifiedAndRegister(ssid, verified, isCancelled, null))
            return
        }
        appendLog("WIFI: [$source] 已核对 SSID 但拿不到唯一的 Network 句柄——请求用户确认当前网络")
        confirmCurrentWifiThenContinue(ssid, source, null, isCancelled, sleepFn, awaitLatchFn, onResult)
    }

    /** WLAN 关着时经 Shizuku 直接打开（`cmd wifi set-wifi-enabled`），返回是否已开启。 */
    fun ensureWifiEnabledSilently(
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
    ): Boolean {
        if (!shellWifiDisabled()) return true
        appendLog("WIFI: WLAN 未开启——经 Shizuku 打开（cmd wifi set-wifi-enabled enabled，免手动）")
        val result = shell.exec("cmd wifi set-wifi-enabled enabled", 8_000L)
        if (!result.ok) {
            appendLog("WIFI: 打开 WLAN 失败（exit=${result.exitCode} ${result.output.take(60)}）")
            return false
        }
        val deadline = uptimeMillis() + WIFI_ENABLE_WAIT_MS
        while (uptimeMillis() < deadline && !isCancelled()) {
            if (!shellWifiDisabled()) return true
            sleepFn(500)
        }
        if (shellWifiDisabled()) {
            appendLog("WIFI: 等待 WLAN 开启超时（${WIFI_ENABLE_WAIT_MS / 1000}s）")
            return false
        }
        return true
    }

    /** 轮询 `cmd wifi status` 直到关联到 [ssid]（可取消）。 */
    fun waitForCarSsid(
        ssid: String,
        timeoutMs: Long,
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
    ): Boolean {
        val deadline = uptimeMillis() + timeoutMs
        var lastLogAt = 0L
        var current: String? = null
        while (uptimeMillis() < deadline) {
            if (isCancelled()) return false
            current = liveWifiSsid()
            if (current == ssid) {
                appendLog("WIFI: 已切到车机热点 \"$ssid\"")
                return true
            }
            val now = uptimeMillis()
            if (now - lastLogAt >= 3_000L) {
                lastLogAt = now
                ui.phase(t(R.string.sc_switching, ssid))
                appendLog("WIFI: 等待切换到车机热点（当前 ${wifiForLog(current, "未连接")}）…")
            }
            sleepFn(500)
        }
        appendLog("WIFI: 等待切换到车机热点超时（当前 ${wifiForLog(current, "未连接")}）")
        return false
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点，成功后进程网络已绑定。
     *
     * 该网络只对本进程可见、不保存、不成为默认网络，但首次加入会弹系统确认框。
     * 手机流量优先时会禁用「直接采用系统现有 Wi-Fi」与 connect-network 兜底，
     * 避免偏好被保存网络或 ROM 自动切网绕过。
     */
    fun joinCarWifiSpecifier(
        wifi: Iap2SenderSession.WifiConfiguration,
        allowExistingSystemWifi: Boolean = true,
        allowSystemFallback: Boolean = true,
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        isCancelled: () -> Boolean = { connectCancelled },
    ): CarWifiLink.JoinResult<N> {
        val connector = wifiConnector ?: return CarWifiLink.JoinResult.Unavailable
        if (allowExistingSystemWifi && liveWifiMatches(wifi.ssid) == true) {
            appendLog("WIFI: 系统已连接同一局域网 \"${wifi.ssid}\"——直接绑定现有 Wi-Fi，跳过加入请求")
            val existing = bindExistingCarWifi(connector, wifi.ssid, sleepFn = sleepFn, isCancelled = isCancelled)
            if (existing != null) {
                boundCarSsid = wifi.ssid
                ui.phase(t(R.string.sc_bound_existing))
                reannounce(wifi.ssid, true, existing)
                appendLog(">>> 现有 Wi-Fi 已绑定，ctrl 广播已重新注册，等待车机邀请")
                return CarWifiLink.JoinResult.Joined(existing)
            }
            appendLog("WIFI: 检测到同一局域网，但现有 Wi-Fi 网络通道绑定失败——继续尝试专用网络")
        }
        ui.phase(t(R.string.sc_joining_no_data, wifi.ssid))
        appendLog(
            ">>> 以应用专属网络加入 \"${wifi.ssid}\"（不保存为系统 WiFi，其它应用继续走 4G/5G）",
        )
        val result =
            synchronized(wifiBindLock) {
                val existing = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existing != null) {
                    appendLog("WIFI: 应用专属网络已在位——跳过重复加入")
                    CarWifiLink.JoinResult.Joined(existing)
                } else {
                    connector.joinCarPlayNetwork(
                        ssid = wifi.ssid,
                        passphrase = wifi.passphrase,
                        securityType = wifi.securityType,
                        timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                        isCancelled = isCancelled,
                        quickFallback = if (allowSystemFallback) ({ specifierQuickFallback(wifi, sleepFn, isCancelled) }) else null,
                        deepFallback = if (allowSystemFallback) ({ specifierDeepFallback(wifi, sleepFn, isCancelled) }) else null,
                        userChoicePending = userChoicePending,
                        awaitLatch = awaitLatchFn,
                    )
                }
            }
        if (result is CarWifiLink.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            ui.phase(t(R.string.sc_joined))
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            reannounce(wifi.ssid, true, connector.currentNetwork)
            appendLog(">>> WiFi 已加入车机热点（应用专属网络），ctrl 广播已重新注册，等待车机 ctrl 邀请")
        } else if (allowExistingSystemWifi && !isCancelled()) {
            appendLog("WIFI: 应用专属网络加入未成功（${result.javaClass.simpleName}）")
            // 兜底：系统里保存过该热点时，系统可能已自动全局连上它——specifier 挂起
            // 的形态下这条近路反而最稳（2026-10-22 真机：用户选「保留系统网络」+
            // Android 16 specifier 永久 pending）。校验当前 SSID 后直接绑定现有连接。
            val currentSsid = liveWifiSsid()
            if (currentSsid == wifi.ssid) {
                appendLog("WIFI: 系统当前已连着 \"$currentSsid\"——直接绑定现有连接（保存网络自动连上的近路）")
                // bindCurrentWifi 异步回调：短等绑定完成（回调很快，系统已在网）
                if (bindExistingCarWifi(connector, wifi.ssid, sleepFn = sleepFn, isCancelled = isCancelled) != null) {
                    boundCarSsid = wifi.ssid
                    ui.phase(t(R.string.sc_joined))
                    reannounce(wifi.ssid, true, connector.currentNetwork)
                    appendLog(">>> WiFi 已绑定现有 \"$currentSsid\" 连接，ctrl 广播已重新注册")
                    return CarWifiLink.JoinResult.Joined(connector.currentNetwork!!)
                }
                appendLog("WIFI: 绑定现有连接未成功——按失败处理")
            }
        }
        return result
    }

    /**
     * specifier 挂起时的快速兜底（首窗 5s 零回调后调用，运行于连接流程线程）。
     *
     * @return 已绑定并可直接使用的网络；null = 未命中（调用方继续重发 specifier）。
     */
    fun specifierQuickFallback(
        wifi: Iap2SenderSession.WifiConfiguration,
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
    ): N? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        if (isCancelled()) return null
        val current = liveWifiSsid()
        if (current != wifi.ssid) return null
        appendLog("WIFI: 系统已连着 \"$current\"——直接绑定（快速兜底）")
        val network = bindExistingCarWifi(connector, wifi.ssid, sleepFn = sleepFn, isCancelled = isCancelled)
        if (network != null) boundCarSsid = wifi.ssid
        return network
    }

    /**
     * specifier 重发窗也无回调后的深度兜底：`cmd wifi connect-network` 直连热点。
     *
     * 清理登记的时机是**关键**：connect-network 返回 ok 的瞬间该热点已进系统配置，
     * 必须立即登记 [shellFallbackCleanupSsid]。例外：该 SSID 在本轮**之前**就已保存在系统里
     * 或保存状态不可读时**不登记**清理，防止误删用户决定保留的网络。
     */
    fun specifierDeepFallback(
        wifi: Iap2SenderSession.WifiConfiguration,
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
    ): N? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        appendLog(
            "WIFI: specifier 两窗均无回调——深度兜底 connect-network 直连 \"${wifi.ssid}\"" +
                "（本轮窗口大概率已过，为下一轮占位）",
        )
        val savedIds = savedNetworkIds(wifi.ssid)
        val preExisting = savedIds == null || savedIds.isNotEmpty()
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val r = shell.exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!r.ok) {
            appendLog("WIFI: connect-network 失败（${r.output.take(80)}）")
            return null
        }
        when {
            savedIds == null ->
                appendLog("WIFI: 无法读取 \"${wifi.ssid}\" 的系统保存状态——为防误删，不作会话末清除")
            preExisting ->
                appendLog("WIFI: \"${wifi.ssid}\" 此前已保存在系统（用户选过保留）——不作会话末清除")
            else -> {
                shellFallbackCleanupSsid = wifi.ssid
                appendLog("WIFI: connect-network 已执行（新存系统网络，已登记会话末自动清除）")
            }
        }
        // 轮询切网完成（断旧网/关联/DHCP，实测 2-10s）
        repeat(30) {
            if (isCancelled()) return null
            sleepFn(300)
            if (isCancelled()) return null
            if (liveWifiSsid() == wifi.ssid) {
                val network = bindExistingCarWifi(connector, wifi.ssid, sleepFn = sleepFn, isCancelled = isCancelled)
                if (network != null) {
                    boundCarSsid = wifi.ssid
                    appendLog("WIFI: connect-network 已连上 \"${wifi.ssid}\"")
                }
                return network
            }
        }
        appendLog("WIFI: connect-network 后 9s 未切到车机热点——按失败返回（系统网络已登记，会话末清除）")
        return null
    }

    /** 绑定当前已连接的 WiFi 并短等绑定完成（兜底路径共用）。返回绑定的网络或 null。 */
    fun bindExistingCarWifi(
        connector: CarWifiLink<N>,
        ssid: String,
        verifySsid: Boolean = true,
        sleepFn: (Long) -> Unit = sleep,
        isCancelled: () -> Boolean = { connectCancelled },
    ): N? {
        val verifiedNetwork = if (verifySsid) verifiedNetworkFor(ssid) else connector.currentWifiNetwork()
        if (verifiedNetwork != null) {
            return if (connector.bindVerifiedNetwork(verifiedNetwork, ssid)) verifiedNetwork else null
        }
        // 用户确认的是当前网络；拿不到当前句柄时不能请求并绑定任意 Wi-Fi。
        if (!verifySsid) return null
        var boundOk = false
        // ssidHint：绑定断开（熄火）时经 onNetworkLost 通知会话层——兜底入网是主路径，
        // 不通知的话英雄卡停在「已连接」、会话拖到心跳超时才死
        connector.bindCurrentWifi(ssid, expectedSsid = ssid.takeIf { verifySsid }) { boundOk = it }
        val waitUntil = uptimeMillis() + 3_000
        while (!boundOk &&
            connector.currentNetwork == null &&
            uptimeMillis() < waitUntil &&
            !isCancelled()
        ) {
            sleepFn(100)
        }
        return connector.currentNetwork
    }

    /** Shizuku 就绪才可跑 `cmd wifi`（与其它 shell 调用同一守卫）。 */
    private fun shellWifiReady(): Boolean = shellReady()

    /**
     * 系统里已保存同名热点时，询问用户是否清除（**不擅自删除**）。
     *
     * 每个 SSID 每个进程只问一次；保持原未调用状态，不因抽取接入热路径。
     */
    fun promptForgetSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid) ?: return
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: 系统里已保存热点 \"$ssid\"（netId=${ids.joinToString(",")}）" +
                "——询问用户是否清除（保留也能正常连接）",
        )
        val forget =
            askWifiJoinChoice(
                CarWifiDialogPrompt(
                    title = t(R.string.sc_saved_title),
                    message =
                        t(R.string.sc_saved_body, ssid),
                    positive = t(R.string.sc_saved_forget),
                    negative = t(R.string.sc_saved_keep),
                ),
            )
        if (connectCancelled) return
        when (forget) {
            true -> {
                ids.forEach { id ->
                    val result = shell.exec("cmd wifi forget-network $id", 5_000L)
                    appendLog(
                        "WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=$id ok=${result.ok}）",
                    )
                }
            }

            false -> appendLog("WIFI: 用户选择保留系统保存的 \"$ssid\"——继续以应用专属网络连接")

            // 无人应答（UI 不在前台）：按「保留」处理，绝不在用户未确认时删网络
            null -> appendLog("WIFI: 无人应答是否清除保存的网络——按保留处理（不改用户配置）")
        }
    }

    /** 查系统已保存的、SSID 与目标完全一致的网络 id（`cmd wifi list-networks`）。 */
    fun savedNetworkIds(ssid: String): List<Int>? {
        val listing = shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: 无法读取系统已保存网络（exit=${listing.exitCode}）——跳过检查")
            return null
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** WiFi 未开启时的引导与等待；true = 已开启可继续。 */
    fun waitForWifiEnabled(
        isCancelled: () -> Boolean = { connectCancelled },
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
    ): Boolean {
        val open =
            askWifiJoinChoice(
                CarWifiDialogPrompt(
                    title = t(R.string.sc_wifi_off_title),
                    message = t(R.string.sc_wifi_off_body),
                    positive = t(R.string.sc_open_wifi_settings),
                    negative = t(R.string.sc_cancel),
                ),
                isCancelled = isCancelled,
                awaitLatch = awaitLatchFn,
            )
        if (isCancelled()) return false
        when (open) {
            true -> {
                openWlanSettings()
                ui.hint { t(R.string.sc_turn_on_wifi) }
            }

            false -> {
                ui.phase(t(R.string.sc_cancelled_wifi_needed))
                appendLog("WIFI: 用户取消——自动加入中止（WiFi 未开启）")
                return false
            }

            null -> {
                ui.phase(t(R.string.sc_wifi_off_needed))
                appendLog("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）")
                return false
            }
        }
        var waited = 0
        // 用调用方的判据而不是全局标志：交接被别的链路接手时全局标志可能仍为 false。
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !isCancelled()) {
            if (waited == 0) {
                ui.phase(t(R.string.sc_waiting_wifi))
                appendLog("WIFI: 等待用户打开 WLAN（最长 120 秒，取消连接可中止）")
            }
            sleepFn(2_000)
            waited += 2_000
        }
        if (isCancelled()) {
            ui.phase(t(R.string.sc_cancelled))
            return false
        }
        if (shellWifiDisabled()) {
            ui.phase(t(R.string.sc_wifi_off))
            appendLog("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接")
            return false
        }
        return true
    }

    /** 静默路径与应用专属网络都失败：让用户重试或转手动连接。 */
    fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        isCancelled: () -> Boolean = { connectCancelled },
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
    ) {
        if (isCancelled()) return
        val phoneMode = preferredInternetNetwork() == CarWifiInternetPreference.PHONE
        val systemOnTarget =
            phoneMode &&
                (liveWifiSsid() == wifi.ssid || wifiConnector?.systemActiveWifiState(wifi.ssid) == CarWifiLink.SystemWifiState.TARGET)
        if (isCancelled()) return
        if (systemOnTarget) appendLog("NETWORK: phone-private-join-failed systemOnCar=true")
        val choice =
            askWifiJoinChoice(
                CarWifiDialogPrompt(
                    title = t(R.string.sc_join_failed_title),
                    message =
                        t(if (phoneMode) R.string.sc_phone_join_failed_body else R.string.sc_join_failed_body, wifi.ssid),
                    positive = t(R.string.sc_retry),
                    negative = t(R.string.sc_open_wifi_settings),
                ),
                isCancelled = isCancelled,
                awaitLatch = awaitLatchFn,
            )
        if (isCancelled()) return
        when (choice) {
            true -> {
                when (joinCarWifi(wifi, shellReady, sleepFn, isCancelled, awaitLatchFn)) {
                    is CarWifiLink.JoinResult.Joined -> Unit
                    CarWifiLink.JoinResult.Unsupported ->
                        if (preferredInternetNetwork() == CarWifiInternetPreference.CAR) {
                            legacyJoinCarWifi(wifi, shellReady, isCancelled, sleepFn, awaitLatchFn)
                        } else {
                            ui.phase(t(R.string.sc_phone_join_failed_retry))
                        }
                    CarWifiLink.JoinResult.Unavailable -> {
                        ui.phase(t(if (phoneMode) R.string.sc_phone_join_failed_retry else R.string.sc_join_failed_manual))
                        appendLog("WIFI: 重试仍未加入 \"${wifi.ssid}\"——请到系统 WLAN 手动连接")
                    }
                }
            }

            false ->
                if (phoneMode) {
                    openWlanSettings()
                } else {
                    manualJoinCarWifi(wifi, isCancelled, sleepFn, awaitLatchFn)
                }

            null -> {
                ui.phase(t(R.string.sc_join_failed_retry))
                appendLog("WIFI: 弹窗无人应答——自动加入中止")
            }
        }
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    fun manualJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        isCancelled: () -> Boolean = { connectCancelled },
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
    ) {
        val connector = wifiConnector
        if (connector != null && connector.hasExistingWifi() && connector.currentWifiSsid() == null) {
            val shown = currentNetworkToken()
            val useCurrent =
                askWifiJoinChoice(
                    CarWifiDialogPrompt(
                        title = t(R.string.sc_use_current_title),
                        message =
                            t(R.string.sc_use_current_body, wifi.ssid),
                        positive = t(R.string.sc_use_current),
                        negative = t(R.string.sc_open_wifi_settings),
                    ),
                    shownNetwork = shown,
                    isCancelled = isCancelled,
                    awaitLatch = awaitLatchFn,
                )
            if (isCancelled() || useCurrent == null) return
            if (useCurrent) {
                // 用户确认的是弹窗里那张网络：期间换网则不能把确认套到新网络上。
                if (shown == null || !matchesToken(shown, connector.currentWifiNetwork(), liveWifiSsid())) {
                    appendLog("WIFI: 用户确认期间当前网络已变化——不绑定新的 current")
                    return
                }
                // The user explicitly confirmed Same-LAN; do not require location access to read the SSID.
                val network =
                    synchronized(wifiBindLock) {
                        bindExistingCarWifi(connector, wifi.ssid, verifySsid = false, sleepFn = sleepFn, isCancelled = isCancelled)
                    }
                if (network != null) {
                    boundCarSsid = wifi.ssid
                    ui.phase(t(R.string.sc_bound_current))
                    reannounce(wifi.ssid, true, network)
                    appendLog("WIFI: 用户确认同一局域网，已绑定当前 Wi-Fi（$network）")
                    return
                }
            }
        }
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        ui.phase(t(R.string.sc_connect_in_settings, wifi.ssid))
        appendLog(
            "WIFI: 用户选择手动连接——等待 WLAN 连上 \"${wifi.ssid}\"（最长 120 秒）；" +
                "注意：手动连接会把该热点保存为系统网络，若它没有互联网可能影响移动数据",
        )
        val manualDeadline = uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (uptimeMillis() < manualDeadline) {
            // 用调用方的判据（含交接所有权），而不是全局 connectCancelled：交接被
            // 别的链路接手后全局标志可能仍为 false，旧任务继续跑会在一轮新交接里
            // 绑定旧端点的热点并重注册 ctrl 广播。
            if (isCancelled()) return
            sleepFn(5_000)
            if (isCancelled()) return
            if (liveWifiSsid() == wifi.ssid) {
                bindVerifiedCarWifi(wifi.ssid, "手动入网", isCancelled, sleepFn, awaitLatchFn) { }
                return
            }
        }
        ui.phase(t(R.string.sc_join_aborted, wifi.ssid))
        appendLog("WIFI: 手动连接等待超时——自动加入中止")
    }

    /**
     * 旧路径（WEP 等 specifier 无法表达的安全类型）：`cmd wifi connect-network`
     * 系统级保存并切换。注意这会保存热点为系统网络——无互联网时可能影响移动数据，
     * 只作为兼容兜底。
     */
    fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        isCancelled: () -> Boolean = { connectCancelled },
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
    ) {
        if (!shellReady) {
            ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))
            appendLog(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且 Shizuku 未就绪（无法自动加入）——" +
                    "请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        val current = liveWifiSsid()
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已在车机热点 \"$current\"——直接绑定")
            bindVerifiedCarWifi(wifi.ssid, "旧安全类型入网", isCancelled, sleepFn, awaitLatchFn) { }
            return
        }
        if (isCancelled()) return
        val choice =
            askWifiJoinChoice(
                CarWifiDialogPrompt(
                    title = t(R.string.sc_join_title),
                    message =
                        t(R.string.sc_join_body, wifi.ssid, wifi.ssid),
                    positive = t(R.string.sc_join_continue),
                    negative = t(R.string.sc_open_wifi_settings),
                ),
                isCancelled = isCancelled,
                awaitLatch = awaitLatchFn,
            )
        if (isCancelled()) return
        if (choice == false) {
            manualJoinCarWifi(wifi, isCancelled, sleepFn, awaitLatchFn)
            return
        }
        if (choice == null) {
            ui.phase(t(R.string.sc_join_failed_retry))
            appendLog("WIFI: 弹窗无人应答——自动加入中止")
            return
        }
        ui.phase(t(R.string.sc_auto_joining, wifi.ssid))
        appendLog(
            ">>> 手机当前 ${wifiForLog(current, "未连接")}（非车机热点）——" +
                "经 Shizuku 自动切换到 \"${wifi.ssid}\"",
        )
        if (isCancelled()) return
        val result =
            shell.exec(
                WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                10_000L,
            )
        if (!result.ok) {
            ui.phase(t(R.string.sc_auto_join_failed, wifi.ssid))
            appendLog(
                "WIFI: connect-network 失败（${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        ui.phase(t(R.string.sc_joining_may_revert, wifi.ssid))
        appendLog("WIFI: connect-network 已发起（自动保存网络，等待切换…）")
        // 系统切换是异步的（断开当前网/扫描/关联/DHCP，2-10 秒）：轮询 SSID 确认。
        repeat(20) {
            if (isCancelled()) {
                appendLog("WIFI: 自动加入已取消（用户断开）")
                return
            }
            val ssid = liveWifiSsid()
            if (ssid == null) {
                // status 命令不可用（非 AOSP 系统）：不做无 SSID 校验的通配绑定，
                // 只接受核验句柄或用户确认的当前网络。
                bindVerifiedCarWifi(wifi.ssid, "旧安全类型入网", isCancelled, sleepFn, awaitLatchFn) { }
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindVerifiedCarWifi(wifi.ssid, "旧安全类型入网", isCancelled, sleepFn, awaitLatchFn) { }
                return
            }
            sleepFn(800)
            if (isCancelled()) return
        }
        ui.phase(t(R.string.sc_not_staying, wifi.ssid))
        ui.hint { t(R.string.sc_not_staying_hint, wifi.ssid) }
        appendLog(
            ">>> WiFi 自动加入超时（可能被系统切回原 WiFi）——" +
                "请到 系统设置>WLAN 手动连接 \"${wifi.ssid}\"（手动连接不会被自动切回）",
        )
    }

    /**
     * WiFi 开关与当前连接的 SSID（一次 `cmd wifi status` 查询）。
     * 输出形如：`Wifi is enabled` + `Wifi is connected to "Redmi K40"`（未连接无此行）。
     */
    fun shellWifiStatus(): Pair<Boolean, String?> {
        val result = shell.exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    /** 当前 Wi-Fi SSID：优先系统 API（有定位权限时），否则用 Shizuku 的 shell 状态。 */
    fun liveWifiSsid(): String? =
        wifiConnector
            ?.currentWifiSsid()
            ?.takeIf { it.isNotBlank() }
            ?: shellWifiStatus().second?.takeIf { it.isNotBlank() }

    /** 日志只记录可读性，不输出可能包含引号而绕过 redactor 的 SSID 原文。 */
    fun wifiForLog(
        ssid: String?,
        unknown: String = "SSID 不可读",
    ): String = if (ssid.isNullOrBlank()) "WiFi（$unknown）" else "WiFi（SSID 已读取）"

    fun liveWifiMatches(ssid: String): Boolean? {
        val live = liveWifiSsid() ?: return null
        return WifiIdentity.matches(ssid, live)
    }

    /**
     * 端点交接的网络分派（Phase 2c-3）：决策八个动作并执行对应的入网/绑定/等待。
     *
     * 上层只提供中性来源、实时 scope/邀请判定与续体；端点对象、缓存、配对与推流保持在上层。
     */
    fun dispatchHandoff(request: CarWifiHandoffRequest<N>): Boolean {
        val sourceKind = request.source
        val targetBinding = request.ssid
        val cachedWifi = request.cachedWifi
        val sleepFn: (Long) -> Unit = request.sleep ?: sleep
        val awaitLatchFn: (CountDownLatch, Long) -> Boolean = request.awaitLatch ?: awaitLatch

        fun liveTargetMatches(): Boolean? =
            if (targetBinding.isNotBlank() && targetBinding != invitedWifiLabel) liveWifiMatches(targetBinding) else null

        fun decideHandoff(
            source: CarWifiHandoffSource,
            liveSsidMatches: Boolean?,
            cachedCredentials: Boolean,
        ): WifiHandoffAction =
            WifiHandoffPolicy.decide(
                source = source.toCredentialSource(),
                targetCurrent = request.targetCurrent(),
                liveSsidMatches = liveSsidMatches,
                liveWifiPresent = wifiConnector?.hasExistingWifi() == true,
                freshInvite = request.freshInvite(),
                cachedCredentials = cachedCredentials,
            )

        val liveMatches = liveTargetMatches()
        val action = decideHandoff(sourceKind, liveMatches, cachedCredentials = cachedWifi != null)
        appendLog(
            "WIFI: 交接决策=$action 来源=$sourceKind 当前网络=" +
                when (liveMatches) {
                    true -> "已核对为目标"
                    false -> "不是目标"
                    null -> "SSID 不可读"
                } +
                if (cachedWifi != null) " 缓存命中=true" else "",
        )

        fun dispatchWifiAction(
            nextAction: WifiHandoffAction,
            cached: Iap2SenderSession.WifiConfiguration?,
        ): Boolean =
            when (nextAction) {
                WifiHandoffAction.JOIN_ACCESSORY -> {
                    val joined: (Boolean) -> Unit = { success ->
                        if (!success && request.scopeActive()) {
                            request.clearCachedCredentials()
                            appendLog("WIFI: 完整车机凭据入网失败——已清除凭据缓存")
                        }
                        request.continueWithNetwork(success)
                    }
                    val early = request.earlyJoin
                    val detached = request.detached
                    if (early == null && detached == null) {
                        joinAccessoryWifi(request.accessoryWifi!!, request.handoffCancelled, sleepFn, awaitLatchFn) {
                            joined(it)
                        }
                    } else {
                        val isDetached = detached ?: { false }
                        val startFallback = {
                            runJoinAccessoryWifi(
                                request.accessoryWifi!!,
                                request.handoffCancelled,
                                sleepFn,
                                awaitLatchFn,
                                isDetached,
                                joined,
                            )
                        }
                        if (early == null) {
                            startFallback()
                        } else {
                            early.thenAccept { success ->
                                if (success || request.handoffCancelled() || isDetached()) {
                                    joined(success)
                                } else {
                                    startFallback()
                                }
                            }
                        }
                    }
                    true
                }

                WifiHandoffAction.JOIN_CACHED -> {
                    val cachedConfiguration = requireNotNull(cached)
                    joinAccessoryWifi(cachedConfiguration, request.handoffCancelled, sleepFn, awaitLatchFn) { success ->
                        if (success) {
                            request.continueWithNetwork(true)
                            return@joinAccessoryWifi
                        }
                        if (request.cachedRetryCancelled()) {
                            request.continueWithNetwork(false)
                            return@joinAccessoryWifi
                        }
                        request.clearCachedCredentials()
                        val retry =
                            decideHandoff(CarWifiHandoffSource.KNOWN_SSID, liveTargetMatches(), cachedCredentials = false)
                        appendLog("WIFI: 缓存热点凭据入网失败——已清除并回退=$retry")
                        if (retry == WifiHandoffAction.JOIN_CACHED) {
                            request.continueWithNetwork(false)
                        } else {
                            dispatchWifiAction(retry, null)
                        }
                    }
                    true
                }

                WifiHandoffAction.BIND_KNOWN -> {
                    bindHandoffWifi(
                        ssid = targetBinding,
                        source = request.sourceLabel,
                        requireLiveMatch = true,
                        requireInvite = false,
                        pendingEndpoint = request.pendingEndpoint,
                        isCancelled = request.handoffCancelled,
                        sleepFn = sleepFn,
                        awaitLatchFn = awaitLatchFn,
                        inviteNetwork = request.inviteNetwork,
                        onResult = request.continueWithNetwork,
                    )
                    true
                }

                WifiHandoffAction.BIND_CURRENT -> {
                    bindHandoffWifi(
                        ssid = targetBinding,
                        source = request.sourceLabel,
                        requireLiveMatch = false,
                        requireInvite = sourceKind != CarWifiHandoffSource.CURRENT_NETWORK,
                        pendingEndpoint = request.pendingEndpoint,
                        networkToBind = request.verifiedCurrentNetwork,
                        requiredCurrentNetwork = request.verifiedCurrentNetwork.takeIf { request.requireCurrentBinding },
                        isCancelled = request.handoffCancelled,
                        sleepFn = sleepFn,
                        awaitLatchFn = awaitLatchFn,
                        inviteNetwork = request.inviteNetwork,
                        onResult = request.continueWithNetwork,
                    )
                    true
                }

                WifiHandoffAction.ASK_CONFIRM_CURRENT -> {
                    background("wifi-confirm-current") {
                        confirmCurrentWifiThenContinue(
                            rawSsid = targetBinding,
                            source = request.sourceLabel,
                            pendingEndpoint = request.pendingEndpoint,
                            isCancelled = request.handoffCancelled,
                            sleepFn = sleepFn,
                            awaitLatchFn = awaitLatchFn,
                            onResult = request.continueWithNetwork,
                        )
                    }
                    true
                }

                WifiHandoffAction.ASK_JOIN_KNOWN -> {
                    background("wifi-manual-known") {
                        joinKnownWifiManually(
                            ssid = targetBinding,
                            source = request.sourceLabel,
                            pendingEndpoint = request.pendingEndpoint,
                            isCancelled = request.handoffCancelled,
                            sleepFn = sleepFn,
                            awaitLatchFn = awaitLatchFn,
                            onResult = request.continueWithNetwork,
                        )
                    }
                    true
                }

                WifiHandoffAction.WAIT -> {
                    appendLog("WIFI: 没有可验证的网络归属——保持等待，不绑定任意 Wi-Fi")
                    request.releaseHandoff()
                    false
                }

                WifiHandoffAction.IGNORE -> {
                    appendLog("WIFI: 旧轮次/非目标端点的网络动作已忽略")
                    request.releaseHandoff()
                    false
                }
            }
        return dispatchWifiAction(action, cachedWifi)
    }

    private fun CarWifiHandoffSource.toCredentialSource(): WifiCredentialSource =
        when (this) {
            CarWifiHandoffSource.ACCESSORY -> WifiCredentialSource.ACCESSORY
            CarWifiHandoffSource.KNOWN_SSID -> WifiCredentialSource.KNOWN_SSID
            CarWifiHandoffSource.CURRENT_NETWORK -> WifiCredentialSource.CURRENT_NETWORK
            CarWifiHandoffSource.NONE -> WifiCredentialSource.NONE
        }

    /** 只有完整车机凭据（或目标+SSID 匹配的缓存）才进入这里。 */
    private fun joinAccessoryWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        isCancelled: () -> Boolean,
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        onResult: (Boolean) -> Unit,
    ) = runJoinAccessoryWifi(wifi, isCancelled, sleepFn, awaitLatchFn, detached = { false }, onResult = onResult)

    private fun runJoinAccessoryWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        isCancelled: () -> Boolean,
        sleepFn: (Long) -> Unit,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean,
        detached: () -> Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        if (boundCarSsid == wifi.ssid && wifiConnector?.currentNetwork != null) {
            appendLog(">>> 已绑定车机热点，跳过重复绑定")
            onResult(true)
            return
        }
        background("wifi-join-on-endpoint") {
            var settled = false
            try {
                while (!isCancelled()) {
                    val shellReady = shellWifiReady()
                    when (val result = joinCarWifi(wifi, shellReady, sleepFn, isCancelled, awaitLatchFn)) {
                        is CarWifiLink.JoinResult.Joined -> {
                            settled = true
                            onResult(true)
                            return@background
                        }

                        CarWifiLink.JoinResult.Unsupported -> {
                            if (detached()) return@background
                            // WEP 等无法用 specifier 表达：等用户在系统 WLAN 里连上后绑定
                            waitForManualWifiThenContinue(wifi, isCancelled, sleepFn, awaitLatchFn) { bound ->
                                settled = true
                                onResult(bound)
                            }
                            return@background
                        }

                        CarWifiLink.JoinResult.Unavailable -> {
                            promptSpecifierFailed(wifi, shellReady, { isCancelled() || detached() }, sleepFn, awaitLatchFn)
                            settled = true
                            val bound = boundCarSsid == wifi.ssid && wifiConnector?.currentNetwork != null
                            onResult(bound)
                            return@background
                        }
                    }
                }
            } finally {
                if (!settled) onResult(false)
            }
        }
    }

    /** 只绑定已验证的当前/已知网络，绝不据此构造 WifiNetworkSpecifier。 */
    private fun bindHandoffWifi(
        ssid: String,
        source: String,
        requireLiveMatch: Boolean,
        requireInvite: Boolean,
        pendingEndpoint: CarWifiPendingEndpointPort?,
        networkToBind: N? = null,
        requiredCurrentNetwork: N? = null,
        inviteNetwork: () -> N? = { null },
        isCancelled: () -> Boolean,
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        onResult: (Boolean) -> Unit,
    ) {
        background("wifi-bind-handoff") {
            if (isCancelled()) {
                onResult(false)
                return@background
            }
            if (requireLiveMatch) {
                val live = liveWifiSsid()
                if (live == null || !WifiIdentity.matches(ssid, live)) {
                    appendLog("WIFI: [$source] 绑定前复核发现当前网络已变化——放弃这次绑定")
                    onResult(false)
                    return@background
                }
            }
            val verifiedNetwork =
                when {
                    requireLiveMatch -> verifiedNetworkFor(ssid)
                    networkToBind != null -> networkToBind
                    requireInvite -> inviteNetwork()
                    else -> wifiConnector?.currentWifiNetwork()
                }
            if (verifiedNetwork == null) {
                if (requireLiveMatch && !isCancelled()) {
                    appendLog("WIFI: [$source] SSID 匹配但无法核验网络句柄——请求用户确认当前网络")
                    confirmCurrentWifiThenContinue(ssid, source, pendingEndpoint, isCancelled, sleepFn, awaitLatchFn, onResult)
                } else {
                    appendLog("WIFI: [$source] 无法核验目标网络句柄或邀请已失效——放弃这次绑定")
                    onResult(false)
                }
                return@background
            }
            val bound =
                bindVerifiedAndRegister(
                    ssid,
                    verifiedNetwork,
                    isCancelled,
                    requiredCurrentNetwork,
                )
            appendLog(
                if (bound) {
                    ">>> [$source] 已绑定已验证的网络（不重新加入、不弹系统选择框）"
                } else {
                    ">>> [$source] 已验证网络绑定失败——等待用户或下一次邀请"
                },
            )
            onResult(bound)
        }
    }

    /**
     * 当前 Wi-Fi 归属读不出来时的唯一自动候选：请用户明确确认“正在使用车机网络”。
     * 用户选「打开 WLAN 设置」即直接打开并等待加入（不再弹第二个同义确认框）；
     * 绝不自行绑定任意 Wi-Fi。
     */
    private fun confirmCurrentWifiThenContinue(
        rawSsid: String,
        source: String,
        pendingEndpoint: CarWifiPendingEndpointPort?,
        isCancelled: () -> Boolean,
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        onResult: (Boolean) -> Unit,
    ) {
        repeat(CONFIRM_CURRENT_MAX_ATTEMPTS) { attempt ->
            if (isCancelled()) {
                onResult(false)
                return
            }
            val knownSsid = rawSsid.takeIf { it.isNotBlank() && it != invitedWifiLabel } ?: liveWifiSsid()
            val label = knownSsid ?: invitedWifiLabel
            // 弹窗展示的是「此刻」的网络：先保存 Network 句柄与可读 SSID 作为令牌。
            // 用户可能在弹窗期间切到另一张 Wi-Fi，返回后必须仍是同一网络，否则会把
            // A 的确认套到 B 上（以 A 的 SSID 绑定 B 并重注册 ctrl）。令牌同时是
            // 弹窗复用/去重的键：换网后既不复用旧答案，也不并入为旧网络展示的弹窗。
            val shown = currentNetworkToken()
            val choice =
                askWifiJoinChoice(
                    CarWifiDialogPrompt(
                        title = t(R.string.sc_use_current_title),
                        message = t(R.string.sc_use_current_body, knownSsid ?: t(R.string.sc_current_network)),
                        positive = t(R.string.sc_use_current),
                        negative = t(R.string.sc_open_wifi_settings),
                    ),
                    shownNetwork = shown,
                    isCancelled = isCancelled,
                    awaitLatch = awaitLatchFn,
                )
            if (isCancelled()) {
                onResult(false)
                return
            }
            when (choice) {
                true -> {
                    val current = wifiConnector?.currentWifiNetwork()
                    val currentSsid = liveWifiSsid()
                    // 无令牌时没有可比对的身份：不能把这次确认套到任意 current 上。
                    if (shown == null || !matchesToken(shown, current, currentSsid)) {
                        appendLog(
                            "WIFI: [$source] 用户确认期间当前网络已变化——重新提示" +
                                "（不把这次确认套到新的 current 上）",
                        )
                        if (attempt == CONFIRM_CURRENT_MAX_ATTEMPTS - 1) {
                            onResult(false)
                            return
                        }
                        return@repeat
                    }
                    val bound = current != null && bindVerifiedAndRegister(label, current, isCancelled, null)
                    if (bound) appendLog("WIFI: [$source] 用户确认当前网络归属，已绑定（$current）")
                    onResult(bound)
                    return
                }

                false -> {
                    // 用户已明确选了「打开 WLAN 设置」：直接打开并等待加入。
                    openWlanSettings()
                    waitForKnownWifiThenContinue(knownSsid, source, pendingEndpoint, isCancelled, sleepFn, awaitLatchFn, onResult)
                    return
                }

                null -> {
                    appendLog("WIFI: [$source] 当前网络确认弹窗无人应答——不绑定任意 Wi-Fi")
                    onResult(false)
                    return
                }
            }
        }
        onResult(false)
    }

    /**
     * 仅已知 SSID：请用户到系统 WLAN 手动加入，之后只绑定验证到的网络。
     * 「打开 WLAN 设置」与「继续等待」都继续核对当前网络——区别只在是否替用户打开设置；
     * 只有弹窗无人应答（无界面可问）才结束本轮。
     */
    private fun joinKnownWifiManually(
        ssid: String,
        source: String,
        pendingEndpoint: CarWifiPendingEndpointPort?,
        isCancelled: () -> Boolean,
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        onResult: (Boolean) -> Unit,
    ) {
        if (ssid.isBlank()) {
            appendLog("WIFI: [$source] 没有可用的 SSID 供手动加入")
            onResult(false)
            return
        }
        val choice =
            askWifiJoinChoice(
                CarWifiDialogPrompt(
                    title = t(R.string.sc_known_ssid_title),
                    message = t(R.string.sc_known_ssid_body, ssid),
                    positive = t(R.string.sc_open_wifi_settings),
                    negative = t(R.string.sc_keep_waiting),
                ),
                isCancelled = isCancelled,
                awaitLatch = awaitLatchFn,
            )
        if (isCancelled()) {
            onResult(false)
            return
        }
        when (choice) {
            true -> openWlanSettings()
            false -> appendLog("WIFI: [$source] 用户选择继续等待——不打开设置，继续核对是否已加入车机热点")
            null -> {
                appendLog("WIFI: [$source] 手动入网提示无人应答")
                onResult(false)
                return
            }
        }
        waitForKnownWifiThenContinue(ssid, source, pendingEndpoint, isCancelled, sleepFn, awaitLatchFn, onResult)
    }

    /**
     * 轮询用户是否已在系统 WLAN 加入已知 SSID。SSID 完全读不到（无定位权限且无
     * Shizuku）或本轮根本不知道目标名称（[ssid] 为 null）时，给用户一次明确的
     * “已切换到车机网络”确认，而不是把权限当唯一路径。
     */
    internal fun waitForKnownWifiThenContinue(
        ssid: String?,
        source: String,
        pendingEndpoint: CarWifiPendingEndpointPort?,
        isCancelled: () -> Boolean,
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        onResult: (Boolean) -> Unit,
    ) {
        val deadline = uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        val startedAt = uptimeMillis()
        var lastHintAt = startedAt
        var askedForConfirmation = false
        var askedForShellOnlyConfirmation = false
        val target = ssid ?: t(R.string.sc_current_network)
        while (uptimeMillis() < deadline && !isCancelled()) {
            if (pendingEndpoint != null &&
                pendingEndpoint.tryContinueDuringWait {
                    appendLog(">>> [$source] 手动入网等待期间收到完整 0x4301——直接接替本轮连接")
                }
            ) {
                return
            }
            val live = liveWifiSsid()
            if (ssid != null && WifiIdentity.matches(ssid, live)) {
                val network = verifiedNetworkFor(ssid)
                if (network == null) {
                    // Shizuku 只能读到 SSID、拿不到带定位信息的 Network 句柄时，
                    // 不用通配请求替用户猜网络；给一次明确的“使用当前网络”确认。
                    if (!askedForShellOnlyConfirmation) {
                        askedForShellOnlyConfirmation = true
                        val shown = currentNetworkToken()
                        val confirmed =
                            askWifiJoinChoice(
                                CarWifiDialogPrompt(
                                    title = t(R.string.sc_use_current_title),
                                    message = t(R.string.sc_use_current_body, target),
                                    positive = t(R.string.sc_use_current),
                                    negative = t(R.string.sc_keep_waiting),
                                ),
                                shownNetwork = shown,
                                isCancelled = isCancelled,
                                awaitLatch = awaitLatchFn,
                            )
                        if (isCancelled()) {
                            onResult(false)
                            return
                        }
                        if (confirmed == true) {
                            val confirmedNetwork = wifiConnector?.currentWifiNetwork()
                            val stillShown = shown != null && matchesToken(shown, confirmedNetwork, liveWifiSsid())
                            onResult(
                                stillShown &&
                                    confirmedNetwork != null &&
                                    bindVerifiedAndRegister(ssid, confirmedNetwork, isCancelled, null),
                            )
                            return
                        }
                        if (confirmed == null) {
                            onResult(false)
                            return
                        }
                    }
                    sleepFn(1_000)
                    continue
                }
                onResult(bindVerifiedAndRegister(ssid, network, isCancelled, null))
                return
            }
            if ((live == null || ssid == null) &&
                !askedForConfirmation &&
                uptimeMillis() - startedAt >= 8_000L
            ) {
                askedForConfirmation = true
                val shown = currentNetworkToken()
                val confirmed =
                    askWifiJoinChoice(
                        CarWifiDialogPrompt(
                            title = t(R.string.sc_use_current_title),
                            message = t(R.string.sc_use_current_body, target),
                            positive = t(R.string.sc_use_current),
                            negative = t(R.string.sc_keep_waiting),
                        ),
                        shownNetwork = shown,
                        isCancelled = isCancelled,
                        awaitLatch = awaitLatchFn,
                    )
                if (isCancelled()) {
                    onResult(false)
                    return
                }
                if (confirmed == true) {
                    val confirmedNetwork = wifiConnector?.currentWifiNetwork()
                    val stillShown = shown != null && matchesToken(shown, confirmedNetwork, liveWifiSsid())
                    onResult(
                        stillShown &&
                            confirmedNetwork != null &&
                            bindVerifiedAndRegister(ssid ?: live ?: invitedWifiLabel, confirmedNetwork, isCancelled, null),
                    )
                    return
                }
            }
            val now = uptimeMillis()
            if (now - lastHintAt >= 5_000L) {
                lastHintAt = now
                ui.phase(t(R.string.sc_waiting_join, target))
                appendLog("WIFI: [$source] 等待用户在系统 WLAN 加入 ${wifiForLog(ssid, "目标未知")}（当前 ${wifiForLog(live)}）…")
            }
            sleepFn(1_000)
        }
        if (!isCancelled()) {
            if (pendingEndpoint != null &&
                pendingEndpoint.tryContinueBeforeTimeout {
                    appendLog(">>> [$source] 手动入网超时前收到完整 0x4301——直接接替本轮连接")
                }
            ) {
                return
            }
            appendLog("WIFI: [$source] 手动加入 ${wifiForLog(ssid, "目标未知")} 等待超时——继续等待车机邀请或晚到端点")
        }
        onResult(false)
    }

    /** specifier 不可用时的手动兜底：轮询系统 WLAN 是否连上目标热点，而后绑定。 */
    internal fun waitForManualWifiThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        isCancelled: () -> Boolean,
        sleepFn: (Long) -> Unit = sleep,
        awaitLatchFn: (CountDownLatch, Long) -> Boolean = awaitLatch,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = uptimeMillis()
        while (uptimeMillis() < deadline && !isCancelled()) {
            val current = liveWifiSsid()
            if (current == wifi.ssid) {
                val network = verifiedNetworkFor(wifi.ssid)
                if (network == null) {
                    appendLog("WIFI: 已连在目标热点但拿不到对应 Network 句柄——请求用户确认")
                    confirmCurrentWifiThenContinue(wifi.ssid, "手动入网", null, isCancelled, sleepFn, awaitLatchFn, onBound)
                    return
                }
                onBound(bindVerifiedAndRegister(wifi.ssid, network, isCancelled, null))
                return
            }
            if (uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = uptimeMillis()
                ui.phase(t(R.string.sc_waiting_join, wifi.ssid))
                appendLog("WIFI: 等待用户在系统 WLAN 中加入车机热点（当前 ${wifiForLog(current, "未连接")}）…")
                openWlanSettings()
            }
            sleepFn(1_000)
        }
        if (!isCancelled()) {
            ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))
            appendLog(">>> 等待车机热点超时——请到 设置>WLAN 加入 \"${wifi.ssid}\" 后重新连接")
            ui.hint { t(R.string.sc_not_joined_hint, wifi.ssid) }
        }
        onBound(false)
    }

    fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        /** WiFi 等待用户操作的窗口（打开 WLAN 开关 / 手动加入热点的轮询上限）。 */
        internal const val WLAN_WAIT_TIMEOUT_MS = 120_000L

        /** `cmd wifi connect-network` 之后等系统真正关联上热点的时间窗。 */
        internal const val CAR_WIFI_JOIN_WAIT_MS = 25_000L

        /** `cmd wifi set-wifi-enabled enabled` 之后等 WLAN 真正开启的时间窗。 */
        internal const val WIFI_ENABLE_WAIT_MS = 10_000L

        /** 等用户确认「连接车机热点」系统弹窗的上限（应用专属网络）。 */
        internal const val SPECIFIER_JOIN_TIMEOUT_MS = 40_000L

        /** 端点已到但手机还没切到车机热点时的等待上限（手动入网兜底）。 */
        internal const val CAR_WIFI_BIND_WAIT_MS = 45_000L

        /** 用户确认「使用当前网络」期间换了 Wi-Fi 时的重新提示次数上限。 */
        const val CONFIRM_CURRENT_MAX_ATTEMPTS = 3
    }
}
