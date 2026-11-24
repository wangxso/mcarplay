package com.mcarplay.app.net

import androidx.annotation.StringRes
import com.mcarplay.app.R
import com.mcarplay.app.iap2.Iap2SenderSession

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

/**
 * 手机流量优先模式下的系统 Wi-Fi 冲突检查决策（避免 `net/` 反向依赖 `settings/`）。
 */
internal enum class CarWifiPhoneModeDecision {
    PROCEED,
    CONFLICT,
    CONFIRM,
}

/**
 * 车机热点四条凭据入网路径（静默 shell、应用专属网络 specifier、旧安全类型 legacy、手动 WLAN 指引）
 * 与手机流量优先系统 Wi-Fi 安全检查（Phase 2c-2）。
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
    private val askChoice: (CarWifiDialogPrompt, T?, () -> Boolean) -> Boolean?,
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
    private val phoneModeDecision: (CarWifiInternetPreference, Boolean, Boolean, Boolean?) -> CarWifiPhoneModeDecision =
        { preference, activeTarget, activeIdentityUnknown, saved ->
            when {
                preference != CarWifiInternetPreference.PHONE -> CarWifiPhoneModeDecision.PROCEED
                activeTarget || saved == true -> CarWifiPhoneModeDecision.CONFLICT
                activeIdentityUnknown || saved == null -> CarWifiPhoneModeDecision.CONFIRM
                else -> CarWifiPhoneModeDecision.PROCEED
            }
        },
    private val bindVerifiedAndRegister: (String, N?, () -> Boolean, N?) -> Boolean,
    private val verifiedNetworkFor: (String) -> N?,
    private val confirmCurrent: (String, String, () -> Boolean, (Boolean) -> Unit) -> Unit,
    private val reannounce: (String, Boolean, N?) -> Unit,
    private val background: (String, () -> Unit) -> Unit,
    private val uptimeMillis: () -> Long,
    private val sleep: (Long) -> Unit,
    private val openWlanSettings: () -> Unit,
    private val copyHotspotPsk: (String) -> Unit,
    private val userChoicePending: (Long) -> Boolean = { false },
    private val log: (String) -> Unit,
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

    private var phoneModeBlockedSsid: String?
        get() = state.phoneModeBlockedSsid
        set(value) {
            state.phoneModeBlockedSsid = value
        }

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
    ): Boolean? = askChoice(spec, shownNetwork, isCancelled)

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
     *    热点不写入系统配置、不成为系统默认网络，其它应用继续用手机流量；首次加入会弹系统确认框。
     * 全失败时回退「系统 WLAN 手动加入」指引（密码可经「去连接」复制）。
     */
    fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        background("wifi-auto-join") {
            val internetNetwork = preferredInternetNetwork()
            val shellReady = shellWifiReady()
            appendLog(
                "NETWORK: 互联网优先=${if (internetNetwork == CarWifiInternetPreference.CAR) "车机网络" else "手机流量"}" +
                    "（${if (internetNetwork == CarWifiInternetPreference.CAR) "允许系统级使用车机热点" else "车机热点仅绑定本进程"}）",
            )
            // 1) 首选静默路径：Shizuku 命令行连网（0 弹窗）
            if (useSystemWifi(internetNetwork, shellReady)) {
                if (joinCarWifiSilently(wifi) is CarWifiLink.JoinResult.Joined) return@background
                if (connectCancelled) return@background
                // 热点已连上、只是 Network 通道没绑成：绝不能退回 specifier——那会重新
                // 关联热点并弹系统确认框（用户看到「连上了又弹窗」）。
                // 有核验句柄就只绑它；拿不到句柄时转用户确认，绝不发起通配绑定。
                if (liveWifiSsid() == wifi.ssid) {
                    var bound = false
                    bindVerifiedCarWifi(wifi.ssid, "静默入网") { bound = it }
                    if (bound) return@background
                    ui.phase(t(R.string.sc_bind_failed))
                    appendLog(
                        "WIFI: 已连在 \"${wifi.ssid}\" 但未能绑定唯一的 Network 句柄——本轮中止" +
                            "（不退回会弹窗的 specifier，也不发起通配绑定）",
                    )
                    return@background
                }
            }
            // 2) 回退：应用专属网络（不影响 4G/5G、不会保存为系统网络）
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@background
            if (connectCancelled) return@background
            if (
                internetNetwork == CarWifiInternetPreference.PHONE &&
                !ensurePhoneModeSystemWifiSafe(wifi, shellReady)
            ) {
                return@background
            }
            if (internetNetwork == CarWifiInternetPreference.PHONE && shellReady) {
                appendLog("NETWORK: 手机流量优先——跳过系统级存储与 connect-network 兜底")
            }
            val result =
                joinCarWifiSpecifier(
                    wifi,
                    allowExistingSystemWifi = allowExistingSystemWifi(internetNetwork),
                    allowSystemFallback = allowSystemFallback(internetNetwork),
                )
            when (result) {
                is CarWifiLink.JoinResult.Joined -> Unit

                CarWifiLink.JoinResult.Unsupported -> {
                    if (internetNetwork == CarWifiInternetPreference.CAR) {
                        appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                        legacyJoinCarWifi(wifi, shellReady)
                    } else {
                        appendLog("WIFI: 手机流量优先——该热点安全类型无法使用应用专属网络，请到 WLAN 手动加入")
                        promptSpecifierFailed(wifi, shellReady)
                    }
                }

                CarWifiLink.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady)
            }
        }
    }

    /** 端点先于 0x5703 到达（或上一轮换网失败）时的入网入口，遵循「互联网网络」偏好。 */
    fun joinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): CarWifiLink.JoinResult<N> {
        val internetNetwork = preferredInternetNetwork()
        if (!useSystemWifi(internetNetwork, shellReady)) {
            if (!ensurePhoneModeSystemWifiSafe(wifi, shellReady)) {
                return CarWifiLink.JoinResult.Unavailable
            }
            return joinCarWifiSpecifier(
                wifi,
                allowExistingSystemWifi = allowExistingSystemWifi(internetNetwork),
                allowSystemFallback = allowSystemFallback(internetNetwork),
            )
        }
        val silent = joinCarWifiSilently(wifi)
        if (silent is CarWifiLink.JoinResult.Joined) return silent
        // 热点已连上但通道没绑成：不再走会弹窗的 specifier（见 autoJoinCarWifi 同款判断）；
        // 只接受核验过的 Network 句柄，拿不到时请用户确认当前网络。
        if (!connectCancelled && liveWifiSsid() == wifi.ssid) {
            var bound = false
            bindVerifiedCarWifi(wifi.ssid, "静默入网") { bound = it }
            val boundNetwork = wifiConnector?.currentNetwork
            return if (bound && boundNetwork != null) CarWifiLink.JoinResult.Joined(boundNetwork) else silent
        }
        return joinCarWifiSpecifier(wifi)
    }

    private fun preferredInternetNetwork(): CarWifiInternetPreference = readPreference()

    /**
     * Phone mode promises that the car hotspot stays private to this app. A saved or
     * globally connected matching SSID breaks that promise, so the join must stop
     * instead of silently continuing over the system-wide route.
     */
    private fun phoneModeSystemWifiDecision(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): CarWifiPhoneModeDecision {
        val network = preferredInternetNetwork()
        if (network != CarWifiInternetPreference.PHONE) return CarWifiPhoneModeDecision.PROCEED
        val systemWifi = wifiConnector?.systemActiveWifiState(wifi.ssid)
        val saved = if (shellReady) savedNetworkIds(wifi.ssid)?.isNotEmpty() else null
        return phoneModeDecision(
            network,
            systemWifi == CarWifiLink.SystemWifiState.TARGET,
            systemWifi == CarWifiLink.SystemWifiState.UNKNOWN,
            saved,
        )
    }

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
    fun joinCarWifiSilently(wifi: Iap2SenderSession.WifiConfiguration): CarWifiLink.JoinResult<N> {
        // 与 specifier 兜底路径共用同一把锁：两条路径都会 release + 重绑进程网络。
        // 持锁实现见 [joinCarWifiSilentlyLocked]。
        return synchronized(wifiBindLock) { joinCarWifiSilentlyLocked(wifi) }
    }

    /** [joinCarWifiSilently] 的持锁实现（调用方必须持有 [wifiBindLock]）。 */
    fun joinCarWifiSilentlyLocked(wifi: Iap2SenderSession.WifiConfiguration): CarWifiLink.JoinResult<N> {
        val connector = wifiConnector ?: return CarWifiLink.JoinResult.Unavailable
        if (connectCancelled) return CarWifiLink.JoinResult.Unavailable
        val existing = connector.currentNetwork
        if (boundCarSsid == wifi.ssid && existing != null) {
            appendLog("WIFI: 车机热点已就绪且网络通道已绑定——跳过重复加入")
            return CarWifiLink.JoinResult.Joined(existing)
        }
        if (!ensureWifiEnabledSilently()) return CarWifiLink.JoinResult.Unavailable
        if (connectCancelled) return CarWifiLink.JoinResult.Unavailable
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
            if (!waitForCarSsid(wifi.ssid, CAR_WIFI_JOIN_WAIT_MS)) {
                return CarWifiLink.JoinResult.Unavailable
            }
        }
        if (connectCancelled) return CarWifiLink.JoinResult.Unavailable
        // SSID 已核对，但必须拿到同一张 Wi-Fi 的 Network 句柄再绑：通配 requestNetwork
        // 在两张 Wi-Fi 并存时可能先返回另一张（句柄交给用户确认路径去取）。
        val verified = verifiedNetworkFor(wifi.ssid)
        if (verified == null) {
            appendLog("WIFI: 已连在车机热点但拿不到核验过的 Network 句柄——转用户确认，不发起通配绑定")
            return CarWifiLink.JoinResult.Unavailable
        }
        val bound = bindVerifiedAndRegister(wifi.ssid, verified, { connectCancelled }, null)
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
        onResult: (Boolean) -> Unit,
    ) {
        val verified = verifiedNetworkFor(ssid)
        if (verified != null) {
            onResult(bindVerifiedAndRegister(ssid, verified, isCancelled, null))
            return
        }
        appendLog("WIFI: [$source] 已核对 SSID 但拿不到唯一的 Network 句柄——请求用户确认当前网络")
        confirmCurrent(ssid, source, isCancelled, onResult)
    }

    /** WLAN 关着时经 Shizuku 直接打开（`cmd wifi set-wifi-enabled`），返回是否已开启。 */
    fun ensureWifiEnabledSilently(): Boolean {
        if (!shellWifiDisabled()) return true
        appendLog("WIFI: WLAN 未开启——经 Shizuku 打开（cmd wifi set-wifi-enabled enabled，免手动）")
        val result = shell.exec("cmd wifi set-wifi-enabled enabled", 8_000L)
        if (!result.ok) {
            appendLog("WIFI: 打开 WLAN 失败（exit=${result.exitCode} ${result.output.take(60)}）")
            return false
        }
        val deadline = uptimeMillis() + WIFI_ENABLE_WAIT_MS
        while (uptimeMillis() < deadline && !connectCancelled) {
            if (!shellWifiDisabled()) return true
            sleep(500)
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
    ): Boolean {
        val deadline = uptimeMillis() + timeoutMs
        var lastLogAt = 0L
        var current: String? = null
        while (uptimeMillis() < deadline) {
            if (connectCancelled) return false
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
            sleep(500)
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
    ): CarWifiLink.JoinResult<N> {
        val connector = wifiConnector ?: return CarWifiLink.JoinResult.Unavailable
        if (allowExistingSystemWifi && liveWifiMatches(wifi.ssid) == true) {
            appendLog("WIFI: 系统已连接同一局域网 \"${wifi.ssid}\"——直接绑定现有 Wi-Fi，跳过加入请求")
            val existing = bindExistingCarWifi(connector, wifi.ssid)
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
                        isCancelled = { connectCancelled },
                        quickFallback = if (allowSystemFallback) ({ specifierQuickFallback(wifi) }) else null,
                        deepFallback = if (allowSystemFallback) ({ specifierDeepFallback(wifi) }) else null,
                        userChoicePending = userChoicePending,
                    )
                }
            }
        if (result is CarWifiLink.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            ui.phase(t(R.string.sc_joined))
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            reannounce(wifi.ssid, true, connector.currentNetwork)
            appendLog(">>> WiFi 已加入车机热点（应用专属网络），ctrl 广播已重新注册，等待车机 ctrl 邀请")
        } else if (allowExistingSystemWifi && !connectCancelled) {
            appendLog("WIFI: 应用专属网络加入未成功（${result.javaClass.simpleName}）")
            // 兜底：系统里保存过该热点时，系统可能已自动全局连上它——specifier 挂起
            // 的形态下这条近路反而最稳（2026-10-22 真机：用户选「保留系统网络」+
            // Android 16 specifier 永久 pending）。校验当前 SSID 后直接绑定现有连接。
            val currentSsid = liveWifiSsid()
            if (currentSsid == wifi.ssid) {
                appendLog("WIFI: 系统当前已连着 \"$currentSsid\"——直接绑定现有连接（保存网络自动连上的近路）")
                // bindCurrentWifi 异步回调：短等绑定完成（回调很快，系统已在网）
                if (bindExistingCarWifi(connector, wifi.ssid) != null) {
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
    fun specifierQuickFallback(wifi: Iap2SenderSession.WifiConfiguration): N? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        val current = liveWifiSsid()
        if (current != wifi.ssid) return null
        appendLog("WIFI: 系统已连着 \"$current\"——直接绑定（快速兜底）")
        val network = bindExistingCarWifi(connector, wifi.ssid)
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
    fun specifierDeepFallback(wifi: Iap2SenderSession.WifiConfiguration): N? {
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
            if (connectCancelled) return null
            sleep(300)
            if (liveWifiSsid() == wifi.ssid) {
                val network = bindExistingCarWifi(connector, wifi.ssid)
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
            !connectCancelled
        ) {
            sleep(100)
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
    fun waitForWifiEnabled(isCancelled: () -> Boolean = { connectCancelled }): Boolean {
        val open =
            askWifiJoinChoice(
                CarWifiDialogPrompt(
                    title = t(R.string.sc_wifi_off_title),
                    message = t(R.string.sc_wifi_off_body),
                    positive = t(R.string.sc_open_wifi_settings),
                    negative = t(R.string.sc_cancel),
                ),
                isCancelled = isCancelled,
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
            sleep(2_000)
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
    ) {
        if (isCancelled()) return
        if (preferredInternetNetwork() == CarWifiInternetPreference.PHONE && phoneModeBlockedSsid == wifi.ssid) {
            phoneModeBlockedSsid = null
            return
        }
        val choice =
            askWifiJoinChoice(
                CarWifiDialogPrompt(
                    title = t(R.string.sc_join_failed_title),
                    message =
                        t(R.string.sc_join_failed_body, wifi.ssid),
                    positive = t(R.string.sc_retry),
                    negative = t(R.string.sc_open_wifi_settings),
                ),
                isCancelled = isCancelled,
            )
        if (isCancelled()) return
        when (choice) {
            true -> {
                when (joinCarWifi(wifi, shellReady)) {
                    is CarWifiLink.JoinResult.Joined -> Unit
                    CarWifiLink.JoinResult.Unsupported ->
                        if (preferredInternetNetwork() == CarWifiInternetPreference.CAR) {
                            legacyJoinCarWifi(wifi, shellReady, isCancelled)
                        } else {
                            appendLog("NETWORK: 手机流量优先——无法使用应用专属网络，转手动 WLAN 指引")
                            manualJoinCarWifi(wifi, isCancelled)
                        }
                    CarWifiLink.JoinResult.Unavailable -> {
                        if (preferredInternetNetwork() == CarWifiInternetPreference.PHONE && phoneModeBlockedSsid == wifi.ssid) {
                            phoneModeBlockedSsid = null
                            return
                        }
                        ui.phase(t(R.string.sc_join_failed_manual))
                        appendLog("WIFI: 重试仍未加入 \"${wifi.ssid}\"——请到系统 WLAN 手动连接")
                    }
                }
            }

            false -> manualJoinCarWifi(wifi, isCancelled)

            null -> {
                ui.phase(t(R.string.sc_join_failed_retry))
                appendLog("WIFI: 弹窗无人应答——自动加入中止")
            }
        }
    }

    fun ensurePhoneModeSystemWifiSafe(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        isCancelled: () -> Boolean = { connectCancelled },
    ): Boolean {
        if (isCancelled()) return false
        when (phoneModeSystemWifiDecision(wifi, shellReady)) {
            CarWifiPhoneModeDecision.PROCEED -> {
                phoneModeBlockedSsid = null
                return true
            }

            CarWifiPhoneModeDecision.CONFLICT -> {
                val choice =
                    askWifiJoinChoice(
                        CarWifiDialogPrompt(
                            title = t(R.string.sc_phone_mode_wifi_title),
                            message = t(R.string.sc_phone_mode_wifi_body, wifi.ssid),
                            positive = t(R.string.sc_open_wifi_settings),
                            negative = t(R.string.sc_cancel),
                        ),
                        isCancelled = isCancelled,
                    )
                if (isCancelled()) return false
                if (choice == true) openWlanSettings()
            }

            CarWifiPhoneModeDecision.CONFIRM -> {
                val choice =
                    askWifiJoinChoice(
                        CarWifiDialogPrompt(
                            title = t(R.string.sc_phone_mode_wifi_unknown_title),
                            message = t(R.string.sc_phone_mode_wifi_unknown_body, wifi.ssid),
                            positive = t(R.string.sc_phone_mode_wifi_confirm),
                            negative = t(R.string.sc_open_wifi_settings),
                        ),
                        isCancelled = isCancelled,
                    )
                if (isCancelled()) return false
                if (choice == true) {
                    appendLog("NETWORK: 系统 WLAN 连接或保存状态不可确认；用户确认无冲突，继续手机流量优先")
                    phoneModeBlockedSsid = null
                    return true
                }
                if (choice == false) openWlanSettings()
            }
        }
        phoneModeBlockedSsid = wifi.ssid
        ui.phase(t(R.string.sc_phone_mode_wifi_blocked, wifi.ssid))
        ui.hint { t(R.string.sc_phone_mode_wifi_blocked_hint, wifi.ssid) }
        appendLog("NETWORK: 手机流量优先需要先清除系统 WLAN 热点 \"${wifi.ssid}\"——本轮已停止")
        return false
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    fun manualJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        isCancelled: () -> Boolean = { connectCancelled },
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
                        bindExistingCarWifi(connector, wifi.ssid, verifySsid = false)
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
            sleep(5_000)
            if (isCancelled()) return
            if (liveWifiSsid() == wifi.ssid) {
                bindVerifiedCarWifi(wifi.ssid, "手动入网", isCancelled) { }
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
            bindVerifiedCarWifi(wifi.ssid, "旧安全类型入网", isCancelled) { }
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
            )
        if (isCancelled()) return
        if (choice == false) {
            manualJoinCarWifi(wifi, isCancelled)
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
                bindVerifiedCarWifi(wifi.ssid, "旧安全类型入网", isCancelled) { }
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindVerifiedCarWifi(wifi.ssid, "旧安全类型入网", isCancelled) { }
                return
            }
            sleep(800)
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
    }
}
