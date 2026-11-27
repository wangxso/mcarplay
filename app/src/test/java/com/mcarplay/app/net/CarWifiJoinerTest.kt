package com.mcarplay.app.net

import com.mcarplay.app.R
import com.mcarplay.app.WifiNetworkToken
import com.mcarplay.app.architecture.KotlinSource
import com.mcarplay.app.architecture.SourceTree
import com.mcarplay.app.iap2.Iap2SenderSession.WifiConfiguration
import com.mcarplay.app.iap2.WifiHandoffAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * [CarWifiJoiner] 的确定性 JVM 测试矩阵（Phase 2c-2 设计方案 §6–§7：J02–J16 与 S01/S02 接线契约）。
 *
 * 全程使用可控单调 [FakeClock]（非零起步）、[FakeCarWifiLink]、[FakeShell]、[FakeUi] 与显式后台任务队列，
 * 绝不调用真实 `Thread.sleep` 或依赖 Android `Network` stub。
 */
class CarWifiJoinerTest {
    private data class WifiId(
        val id: String,
    ) {
        override fun toString(): String = id
    }

    private sealed interface Trace {
        data class TaskSubmitted(
            val name: String,
        ) : Trace

        data class Phase(
            val value: String?,
        ) : Trace

        data class HintRegistered(
            val id: Int,
        ) : Trace

        data class HintEvaluated(
            val value: String,
        ) : Trace

        data class Ask(
            val prompt: CarWifiDialogPrompt,
            val shownToken: WifiNetworkToken<WifiId>?,
            val answer: Boolean?,
            val cancelledAtEntry: Boolean = false,
        ) : Trace

        data class ShellExec(
            val command: String,
            val timeoutMs: Long,
            val exitCode: Int,
        ) : Trace

        data class Sleep(
            val millis: Long,
            val clockAfter: Long,
        ) : Trace

        data class JoinCarPlayNetwork(
            val ssid: String,
            val timeoutMillis: Long,
            val hasQuickFallback: Boolean,
            val hasDeepFallback: Boolean,
        ) : Trace

        data class BindVerifiedNetwork(
            val network: WifiId,
            val ssidHint: String?,
        ) : Trace

        data class BindCurrentWifi(
            val ssidHint: String?,
            val expectedSsid: String?,
        ) : Trace

        data class BindVerifiedAndRegister(
            val ssid: String,
            val network: WifiId?,
            val requiredCurrent: WifiId?,
            val lockHeld: Boolean,
        ) : Trace

        data class Reannounce(
            val ssid: String,
            val force: Boolean,
            val network: WifiId?,
        ) : Trace

        data object OpenWlanSettings : Trace

        data class CopyPassphrase(
            val passphrase: String,
        ) : Trace

        data class Log(
            val message: String,
        ) : Trace

        data class LateEndpoint(
            val phase: String,
        ) : Trace
    }

    private class FakeClock(
        initialMillis: Long = 100_000L,
    ) {
        var now: Long = initialMillis

        fun read(): Long = now

        fun advance(millis: Long): Long {
            now += millis
            return now
        }
    }

    private class FakeCarWifiLink(
        private val trace: MutableList<Trace>,
    ) : CarWifiLink<WifiId> {
        override var currentNetwork: WifiId? = null
        var activeWifiNetwork: WifiId? = null
        var apiSsid: String? = null
        var existingWifi: Boolean = false
        var uniqueWifiNetwork: WifiId? = null
        var systemStateBySsid: MutableMap<String, CarWifiLink.SystemWifiState> = mutableMapOf()
        var verifiedBySsid: MutableMap<String, WifiId?> = mutableMapOf()
        var bindVerifiedResult: Boolean = true
        var onBindCurrentWifi: ((String?, String?, (Boolean) -> Unit) -> Unit)? = null
        var onJoinCarPlayNetwork:
            (
                (
                    ssid: String,
                    passphrase: String,
                    securityType: Int,
                    timeoutMillis: Long,
                    isCancelled: () -> Boolean,
                    quickFallback: (() -> WifiId?)?,
                    deepFallback: (() -> WifiId?)?,
                    userChoicePending: ((Long) -> Boolean)?,
                    awaitLatch: (CountDownLatch, Long) -> Boolean,
                ) -> CarWifiLink.JoinResult<WifiId>
            )? = null
        var nextJoinResult: CarWifiLink.JoinResult<WifiId> = CarWifiLink.JoinResult.Unavailable
        var releaseCount: Int = 0

        override fun currentWifiNetwork(): WifiId? = currentNetwork ?: activeWifiNetwork

        override fun currentWifiSsid(): String? = apiSsid

        override fun hasExistingWifi(): Boolean = existingWifi

        override fun singleWifiNetwork(): WifiId? = uniqueWifiNetwork

        override fun systemActiveWifiState(ssid: String): CarWifiLink.SystemWifiState =
            systemStateBySsid[ssid] ?: CarWifiLink.SystemWifiState.NONE

        override fun verifiedWifiNetwork(ssid: String): WifiId? = verifiedBySsid[ssid]

        override fun joinCarPlayNetwork(
            ssid: String,
            passphrase: String,
            securityType: Int,
            timeoutMillis: Long,
            isCancelled: () -> Boolean,
            quickFallback: (() -> WifiId?)?,
            deepFallback: (() -> WifiId?)?,
            userChoicePending: ((Long) -> Boolean)?,
            awaitLatch: (CountDownLatch, Long) -> Boolean,
        ): CarWifiLink.JoinResult<WifiId> {
            trace +=
                Trace.JoinCarPlayNetwork(
                    ssid = ssid,
                    timeoutMillis = timeoutMillis,
                    hasQuickFallback = quickFallback != null,
                    hasDeepFallback = deepFallback != null,
                )
            val custom = onJoinCarPlayNetwork
            val result =
                if (custom != null) {
                    custom(
                        ssid,
                        passphrase,
                        securityType,
                        timeoutMillis,
                        isCancelled,
                        quickFallback,
                        deepFallback,
                        userChoicePending,
                        awaitLatch,
                    )
                } else {
                    nextJoinResult
                }
            if (result is CarWifiLink.JoinResult.Joined) {
                currentNetwork = result.network
            }
            return result
        }

        override fun bindCurrentWifi(
            ssidHint: String?,
            expectedSsid: String?,
            onResult: (Boolean) -> Unit,
        ) {
            trace += Trace.BindCurrentWifi(ssidHint, expectedSsid)
            val handler = onBindCurrentWifi
            if (handler != null) {
                handler(ssidHint, expectedSsid, onResult)
            } else {
                onResult(false)
            }
        }

        override fun bindVerifiedNetwork(
            network: WifiId,
            ssidHint: String?,
        ): Boolean {
            trace += Trace.BindVerifiedNetwork(network, ssidHint)
            if (bindVerifiedResult) {
                currentNetwork = network
            }
            return bindVerifiedResult
        }

        override fun release() {
            releaseCount++
            currentNetwork = null
        }
    }

    private class FakeShell(
        private val trace: MutableList<Trace>,
    ) : CarWifiShellPort {
        var statusEnabled: Boolean = true
        var statusSsid: String? = null
        var statusExitCode: Int = 0
        var listNetworksOutput: String = "Network Id      SSID      Security type\n"
        var listNetworksExitCode: Int = 0
        var setWifiEnabledExitCode: Int = 0
        var setWifiEnabledOutput: String = ""
        var enableWifiOnCommand: Boolean = true
        var connectNetworkExitCode: Int = 0
        var connectNetworkOutput: String = ""
        var connectUpdatesStatusSsidTo: String? = null
        var forgetExitCodeByNetId: MutableMap<String, Int> = mutableMapOf()
        var customHandler: ((String, Long) -> CarWifiShellResult?)? = null

        override fun exec(
            command: String,
            timeoutMs: Long,
        ): CarWifiShellResult {
            val result =
                customHandler?.invoke(command, timeoutMs) ?: when {
                    command == "cmd wifi status" -> {
                        val body =
                            if (!statusEnabled) {
                                "Wifi is disabled"
                            } else if (statusSsid != null) {
                                "Wifi is enabled\nWifi is connected to \"$statusSsid\""
                            } else {
                                "Wifi is enabled\nWifi is not connected"
                            }
                        CarWifiShellResult(statusExitCode, body)
                    }

                    command == "cmd wifi list-networks" ->
                        CarWifiShellResult(listNetworksExitCode, listNetworksOutput)

                    command == "cmd wifi set-wifi-enabled enabled" -> {
                        if (setWifiEnabledExitCode == 0 && enableWifiOnCommand) {
                            statusEnabled = true
                        }
                        CarWifiShellResult(setWifiEnabledExitCode, setWifiEnabledOutput)
                    }

                    command.startsWith("cmd wifi connect-network ") -> {
                        if (connectNetworkExitCode == 0 && connectUpdatesStatusSsidTo != null) {
                            statusSsid = connectUpdatesStatusSsidTo
                        }
                        CarWifiShellResult(connectNetworkExitCode, connectNetworkOutput)
                    }

                    command.startsWith("cmd wifi forget-network ") -> {
                        val target = command.removePrefix("cmd wifi forget-network ").trim()
                        val code = forgetExitCodeByNetId[target] ?: 0
                        CarWifiShellResult(code, "")
                    }

                    else -> error("未预期的 shell 命令: $command")
                }
            trace += Trace.ShellExec(command, timeoutMs, result.exitCode)
            return result
        }
    }

    private class Harness(
        initialClockMillis: Long = 100_000L,
        private val maxSleeps: Int = 200,
    ) {
        val clock = FakeClock(initialClockMillis)
        val trace = ArrayList<Trace>()
        val logs = ArrayList<String>()
        val phases = ArrayList<String?>()
        val lazyHints = ArrayList<() -> String>()
        val evaluatedHints = ArrayList<String>()
        var evaluateHintsOnArrival: Boolean = true

        val state = CarWifiState()
        val lock = Any()
        var linkAvailable: Boolean = true
        val link = FakeCarWifiLink(trace)
        val shell = FakeShell(trace)

        var globalCancelled: Boolean = false
        var shellReady: Boolean = true
        var preference: CarWifiInternetPreference = CarWifiInternetPreference.CAR
        val preferenceReads = ArrayList<CarWifiInternetPreference>()
        var onReadPreference: (() -> CarWifiInternetPreference)? = null

        val dialogAnswers = ArrayDeque<Boolean?>()
        var onAskChoice: ((CarWifiDialogPrompt, WifiNetworkToken<WifiId>?, () -> Boolean) -> Boolean?)? = null

        var verifiedNetworkBySsid: MutableMap<String, WifiId?> = mutableMapOf()
        var bindVerifiedAndRegisterResult: Boolean = true
        var onBindVerifiedAndRegister: ((String, WifiId?, () -> Boolean, WifiId?) -> Boolean)? = null

        val pendingBackgroundTasks = ArrayDeque<Pair<String, () -> Unit>>()
        var runBackgroundImmediately: Boolean = true
        var sleepCount: Int = 0
            private set
        var onSleep: ((Long, Int) -> Unit)? = null
        var userChoicePendingResult: Boolean = false

        val ui =
            object : CarWifiUiPort {
                override fun phase(hint: String?) {
                    phases += hint
                    trace += Trace.Phase(hint)
                }

                override fun hint(message: () -> String) {
                    lazyHints += message
                    trace += Trace.HintRegistered(lazyHints.size)
                    if (evaluateHintsOnArrival) {
                        val value = message()
                        evaluatedHints += value
                        trace += Trace.HintEvaluated(value)
                    }
                }
            }

        val joiner =
            CarWifiJoiner<WifiId, WifiNetworkToken<WifiId>>(
                link = { if (linkAvailable) link else null },
                state = state,
                bindingLock = lock,
                globalCancelled = { globalCancelled },
                ui = ui,
                text = ::formatRes,
                askChoice = { prompt, shownToken, isCancelled, _ ->
                    val cancelledNow = isCancelled()
                    val answer =
                        if (cancelledNow) {
                            null
                        } else {
                            onAskChoice?.invoke(prompt, shownToken, isCancelled)
                                ?: if (dialogAnswers.isNotEmpty()) dialogAnswers.removeFirst() else null
                        }
                    trace += Trace.Ask(prompt, shownToken, answer, cancelledAtEntry = cancelledNow)
                    answer
                },
                captureToken = {
                    val currentLink = if (linkAvailable) link else null
                    val liveSsid =
                        currentLink?.currentWifiSsid()?.takeIf { it.isNotBlank() }
                            ?: if (shell.statusExitCode == 0 && shell.statusEnabled) {
                                shell.statusSsid?.takeIf { it.isNotBlank() }
                            } else {
                                null
                            }
                    WifiNetworkToken(currentLink?.currentWifiNetwork(), liveSsid).takeIf { it.hasIdentity }
                },
                matchesToken = { token, currentNetwork, currentSsid ->
                    token.matches(currentNetwork, currentSsid)
                },
                shellReady = { shellReady },
                shell = shell,
                readPreference = {
                    val current = onReadPreference?.invoke() ?: preference
                    preferenceReads += current
                    current
                },
                bindVerifiedAndRegister = { ssid, verifiedNetwork, isCancelled, requiredCurrent ->
                    val holdsLock = Thread.holdsLock(lock)
                    trace += Trace.BindVerifiedAndRegister(ssid, verifiedNetwork, requiredCurrent, holdsLock)
                    val custom = onBindVerifiedAndRegister
                    if (custom != null) {
                        custom(ssid, verifiedNetwork, isCancelled, requiredCurrent)
                    } else if (!isCancelled() && bindVerifiedAndRegisterResult && verifiedNetwork != null) {
                        link.currentNetwork = verifiedNetwork
                        state.boundCarSsid = ssid
                        ui.phase(formatRes(R.string.sc_joined, emptyArray()))
                        trace += Trace.Reannounce(ssid, true, link.currentNetwork)
                        true
                    } else {
                        false
                    }
                },
                verifiedNetworkFor = { ssid ->
                    if (verifiedNetworkBySsid.containsKey(ssid)) {
                        verifiedNetworkBySsid[ssid]
                    } else {
                        link.verifiedWifiNetwork(ssid)
                    }
                },
                reannounce = { ssid, force, network ->
                    trace += Trace.Reannounce(ssid, force, network)
                },
                background = { name, block ->
                    trace += Trace.TaskSubmitted(name)
                    if (runBackgroundImmediately) {
                        block()
                    } else {
                        pendingBackgroundTasks.addLast(name to block)
                    }
                },
                uptimeMillis = { clock.read() },
                sleep = { millis ->
                    sleepCount++
                    check(sleepCount <= maxSleeps) { "超过最大假睡眠次数 $maxSleeps，疑似死循环" }
                    val after = clock.advance(millis)
                    trace += Trace.Sleep(millis, after)
                    onSleep?.invoke(millis, sleepCount)
                },
                openWlanSettings = {
                    trace += Trace.OpenWlanSettings
                },
                copyHotspotPsk = { passphrase ->
                    trace += Trace.CopyPassphrase(passphrase)
                },
                userChoicePending = { userChoicePendingResult },
                log = { message ->
                    logs += message
                    trace += Trace.Log(message)
                },
                invitedWifiLabel = "(车机所在 WLAN)",
            )

        fun formatRes(
            id: Int,
            args: Array<out Any>,
        ): String {
            val name =
                when (id) {
                    R.string.sc_bind_failed -> "sc_bind_failed"
                    R.string.sc_silent_join -> "sc_silent_join"
                    R.string.sc_switching -> "sc_switching"
                    R.string.sc_bound_existing -> "sc_bound_existing"
                    R.string.sc_joining_no_data -> "sc_joining_no_data"
                    R.string.sc_joined -> "sc_joined"
                    R.string.sc_saved_title -> "sc_saved_title"
                    R.string.sc_saved_body -> "sc_saved_body"
                    R.string.sc_saved_forget -> "sc_saved_forget"
                    R.string.sc_saved_keep -> "sc_saved_keep"
                    R.string.sc_wifi_off_title -> "sc_wifi_off_title"
                    R.string.sc_wifi_off_body -> "sc_wifi_off_body"
                    R.string.sc_open_wifi_settings -> "sc_open_wifi_settings"
                    R.string.sc_cancel -> "sc_cancel"
                    R.string.sc_turn_on_wifi -> "sc_turn_on_wifi"
                    R.string.sc_cancelled_wifi_needed -> "sc_cancelled_wifi_needed"
                    R.string.sc_wifi_off_needed -> "sc_wifi_off_needed"
                    R.string.sc_waiting_wifi -> "sc_waiting_wifi"
                    R.string.sc_cancelled -> "sc_cancelled"
                    R.string.sc_wifi_off -> "sc_wifi_off"
                    R.string.sc_join_failed_title -> "sc_join_failed_title"
                    R.string.sc_join_failed_body -> "sc_join_failed_body"
                    R.string.sc_retry -> "sc_retry"
                    R.string.sc_join_failed_manual -> "sc_join_failed_manual"
                    R.string.sc_join_failed_retry -> "sc_join_failed_retry"
                    R.string.sc_phone_mode_wifi_title -> "sc_phone_mode_wifi_title"
                    R.string.sc_phone_mode_wifi_body -> "sc_phone_mode_wifi_body"
                    R.string.sc_phone_mode_wifi_unknown_title -> "sc_phone_mode_wifi_unknown_title"
                    R.string.sc_phone_mode_wifi_unknown_body -> "sc_phone_mode_wifi_unknown_body"
                    R.string.sc_phone_mode_wifi_confirm -> "sc_phone_mode_wifi_confirm"
                    R.string.sc_phone_mode_wifi_blocked -> "sc_phone_mode_wifi_blocked"
                    R.string.sc_phone_mode_wifi_blocked_hint -> "sc_phone_mode_wifi_blocked_hint"
                    R.string.sc_use_current_title -> "sc_use_current_title"
                    R.string.sc_use_current_body -> "sc_use_current_body"
                    R.string.sc_use_current -> "sc_use_current"
                    R.string.sc_bound_current -> "sc_bound_current"
                    R.string.sc_connect_in_settings -> "sc_connect_in_settings"
                    R.string.sc_join_aborted -> "sc_join_aborted"
                    R.string.sc_not_on_hotspot -> "sc_not_on_hotspot"
                    R.string.sc_join_title -> "sc_join_title"
                    R.string.sc_join_body -> "sc_join_body"
                    R.string.sc_join_continue -> "sc_join_continue"
                    R.string.sc_auto_joining -> "sc_auto_joining"
                    R.string.sc_auto_join_failed -> "sc_auto_join_failed"
                    R.string.sc_joining_may_revert -> "sc_joining_may_revert"
                    R.string.sc_not_staying -> "sc_not_staying"
                    R.string.sc_not_staying_hint -> "sc_not_staying_hint"
                    else -> "res_$id"
                }
            return if (args.isEmpty()) name else "$name(${args.joinToString(",")})"
        }
    }

    private fun wifiConfig(
        ssid: String = "CarHotspot-5G",
        passphrase: String = "12345678",
        channel: Int = 36,
        securityType: Int = 2,
    ): WifiConfiguration =
        WifiConfiguration(
            ssid = ssid,
            passphrase = passphrase,
            channel = channel,
            securityType = securityType,
        )

    /** 端点交接分派的测试驱动：真实 CarWifiHandoffRequest，记录取消/续体/晚到端点消费。 */
    private class HandoffHarness(
        val harness: Harness,
    ) {
        val results = ArrayList<Boolean>()
        val releases = ArrayList<Unit>()
        val clears = ArrayList<Unit>()
        var targetCurrent: Boolean = true
        var scopeActive: Boolean = true
        var cachedRetryCancelled: Boolean = false
        var handoffCancelled: Boolean = false
        var freshInvite: Boolean = false
        var beginOfStep: (() -> Unit)? = null

        fun request(
            source: CarWifiHandoffSource,
            ssid: String,
            accessoryWifi: WifiConfiguration? = null,
            cachedWifi: WifiConfiguration? = null,
            verifiedCurrentNetwork: WifiId? = null,
            requireCurrentBinding: Boolean = false,
            pendingEndpoint: CarWifiPendingEndpointPort? = null,
            inviteNetwork: () -> WifiId? = { null },
        ): CarWifiHandoffRequest<WifiId> =
            CarWifiHandoffRequest(
                sourceLabel = "endpoint",
                source = source,
                ssid = ssid,
                accessoryWifi = accessoryWifi,
                cachedWifi = cachedWifi,
                verifiedCurrentNetwork = verifiedCurrentNetwork,
                requireCurrentBinding = requireCurrentBinding,
                pendingEndpoint = pendingEndpoint,
                targetCurrent = { targetCurrent },
                scopeActive = { scopeActive },
                cachedRetryCancelled = { cachedRetryCancelled },
                handoffCancelled = { handoffCancelled },
                freshInvite = { freshInvite },
                continueWithNetwork = { results += it },
                releaseHandoff = { releases += Unit },
                clearCachedCredentials = { clears += Unit },
                inviteNetwork = inviteNetwork,
            )

        fun dispatch(request: CarWifiHandoffRequest<WifiId>) {
            beginOfStep?.invoke()
            harness.joiner.dispatchHandoff(request)
        }
    }

    private fun Harness.handoff(): HandoffHarness = HandoffHarness(this)

    private fun Harness.backgroundNames(): List<String> = trace.filterIsInstance<Trace.TaskSubmitted>().map { it.name }

    @Test
    fun j02_preferenceReadDynamicallyAtEachEntryAndPolicyControlsSystemPaths() {
        val harness = Harness()
        val wifi = wifiConfig()
        harness.preference = CarWifiInternetPreference.PHONE
        harness.shellReady = true
        harness.shell.listNetworksOutput = "Network Id      SSID      Security type\n"
        harness.link.systemStateBySsid[wifi.ssid] = CarWifiLink.SystemWifiState.NONE
        harness.link.nextJoinResult = CarWifiLink.JoinResult.Joined(WifiId("net-phone"))

        harness.joiner.autoJoinCarWifi(wifi)

        // autoJoin 开头读一次 + ensurePhoneModeSystemWifiSafe 内读一次
        assertEquals(
            listOf(CarWifiInternetPreference.PHONE, CarWifiInternetPreference.PHONE),
            harness.preferenceReads,
        )
        assertTrue(
            harness.logs.contains("NETWORK: 互联网优先=手机流量（车机热点仅绑定本进程）"),
        )
        assertTrue(
            harness.logs.contains("NETWORK: 手机流量优先——跳过系统级存储与 connect-network 兜底"),
        )
        val joinCall = harness.trace.filterIsInstance<Trace.JoinCarPlayNetwork>().single()
        assertEquals(40_000L, joinCall.timeoutMillis)
        assertFalse("PHONE 模式必须禁用 quickFallback", joinCall.hasQuickFallback)
        assertFalse("PHONE 模式必须禁用 deepFallback", joinCall.hasDeepFallback)
        assertFalse(
            "PHONE 模式不得走静默 connect-network",
            harness.trace.filterIsInstance<Trace.ShellExec>().any { it.command.startsWith("cmd wifi connect-network") },
        )
    }

    @Test
    fun j03_silentJoinShortcutsAndFullSuccessSequence() {
        // 1) 已绑定同 SSID 且 currentNetwork 非空：直接返回 Joined，不 release、不重复公告、不弹窗
        val harnessBound = Harness()
        val wifi = wifiConfig()
        val netA = WifiId("net-A")
        harnessBound.state.boundCarSsid = wifi.ssid
        harnessBound.link.currentNetwork = netA
        val resBound = harnessBound.joiner.joinCarWifiSilently(wifi)
        assertEquals(CarWifiLink.JoinResult.Joined(netA), resBound)
        assertEquals(listOf("WIFI: 车机热点已就绪且网络通道已绑定——跳过重复加入"), harnessBound.logs)
        assertEquals(0, harnessBound.link.releaseCount)
        assertTrue(harnessBound.trace.filterIsInstance<Trace.Ask>().isEmpty())
        assertTrue(harnessBound.trace.filterIsInstance<Trace.ShellExec>().isEmpty())

        // 2) 手机已连在目标 SSID 但尚未绑定进程通道：跳过 connect-network，直接核验并绑定
        val harnessAlreadyOnSsid = Harness()
        harnessAlreadyOnSsid.link.apiSsid = wifi.ssid
        harnessAlreadyOnSsid.verifiedNetworkBySsid[wifi.ssid] = netA
        val resAlreadyOnSsid = harnessAlreadyOnSsid.joiner.joinCarWifiSilently(wifi)
        assertEquals(CarWifiLink.JoinResult.Joined(netA), resAlreadyOnSsid)
        assertTrue(harnessAlreadyOnSsid.logs.contains("WIFI: 手机已连在车机热点 \"${wifi.ssid}\"——直接建立网络通道并绑定"))
        assertFalse(
            harnessAlreadyOnSsid.trace.filterIsInstance<Trace.ShellExec>().any { it.command.startsWith("cmd wifi connect-network") },
        )
        val bindCall1 = harnessAlreadyOnSsid.trace.filterIsInstance<Trace.BindVerifiedAndRegister>().single()
        assertTrue("joinCarWifiSilently 必须持传入的同一把 bindingLock 调用绑定", bindCall1.lockHeld)

        // 3) 完整静默路径：WLAN 关闭 -> 静默开启（轮询 500ms 后开启）-> connect-network -> 轮询 SSID（3s 节流）-> 核验并绑定
        val harnessFull = Harness(initialClockMillis = 50_000L)
        harnessFull.shell.statusEnabled = false
        harnessFull.shell.enableWifiOnCommand = false
        harnessFull.shell.statusSsid = "Office-WiFi"
        harnessFull.verifiedNetworkBySsid[wifi.ssid] = netA
        harnessFull.onSleep = { millis, count ->
            // 第 1 次 500ms sleep 后 WLAN 开启；随后 connect-network 后第 7 次 500ms sleep（+3500ms）切到目标 SSID
            if (millis == 500L && count == 1) {
                harnessFull.shell.statusEnabled = true
            }
            if (millis == 500L && count == 8) {
                harnessFull.shell.statusSsid = wifi.ssid
            }
        }
        val resFull = harnessFull.joiner.joinCarWifiSilently(wifi)
        assertEquals(CarWifiLink.JoinResult.Joined(netA), resFull)
        assertEquals(wifi.ssid, harnessFull.state.boundCarSsid)
        assertTrue(harnessFull.trace.filterIsInstance<Trace.Ask>().isEmpty())
        assertEquals(
            listOf(
                "WIFI: WLAN 未开启——经 Shizuku 打开（cmd wifi set-wifi-enabled enabled，免手动）",
                ">>> 静默加入车机热点 \"${wifi.ssid}\"（cmd wifi connect-network，0 弹窗；当前 WiFi（SSID 已读取））",
                "WIFI: 等待切换到车机热点（当前 WiFi（SSID 已读取））…",
                "WIFI: 等待切换到车机热点（当前 WiFi（SSID 已读取））…",
                "WIFI: 已切到车机热点 \"${wifi.ssid}\"",
            ),
            harnessFull.logs,
        )
        assertEquals(
            listOf(
                "sc_silent_join(${wifi.ssid})",
                "sc_switching(${wifi.ssid})",
                "sc_switching(${wifi.ssid})",
                "sc_joined",
            ),
            harnessFull.phases,
        )
    }

    @Test
    fun j04_silentJoinFailuresAndAssociatedSsidDoesNotFallBackToSpecifier() {
        val wifi = wifiConfig()

        // 1) 打开 WLAN 命令失败（输出截断 60 字符）
        val hEnableFail = Harness()
        hEnableFail.shell.statusEnabled = false
        hEnableFail.shell.setWifiEnabledExitCode = 7
        hEnableFail.shell.setWifiEnabledOutput = "E".repeat(80)
        assertEquals(CarWifiLink.JoinResult.Unavailable, hEnableFail.joiner.joinCarWifiSilently(wifi))
        assertTrue(hEnableFail.logs.contains("WIFI: 打开 WLAN 失败（exit=7 ${"E".repeat(60)}）"))

        // 2) 打开 WLAN 10s 超时（20 * 500ms = 10_000ms）
        val hEnableTimeout = Harness()
        hEnableTimeout.shell.statusEnabled = false
        hEnableTimeout.shell.enableWifiOnCommand = false
        assertEquals(CarWifiLink.JoinResult.Unavailable, hEnableTimeout.joiner.joinCarWifiSilently(wifi))
        assertEquals(20, hEnableTimeout.sleepCount)
        assertTrue(hEnableTimeout.logs.contains("WIFI: 等待 WLAN 开启超时（10s）"))

        // 3) connect-network 失败（输出截断 80 字符）
        val hConnectFail = Harness()
        hConnectFail.shell.connectNetworkExitCode = 3
        hConnectFail.shell.connectNetworkOutput = "X".repeat(100)
        assertEquals(CarWifiLink.JoinResult.Unavailable, hConnectFail.joiner.joinCarWifiSilently(wifi))
        assertTrue(hConnectFail.logs.contains("WIFI: connect-network 失败（exit=3 ${"X".repeat(80)}）"))

        // 4) waitForCarSsid 25s 超时（50 * 500ms = 25_000ms）
        val hSsidTimeout = Harness()
        hSsidTimeout.shell.statusSsid = null
        assertEquals(CarWifiLink.JoinResult.Unavailable, hSsidTimeout.joiner.joinCarWifiSilently(wifi))
        assertEquals(50, hSsidTimeout.sleepCount)
        assertTrue(hSsidTimeout.logs.last() == "WIFI: 等待切换到车机热点超时（当前 WiFi（未连接））")

        // 5) 静默已连上目标 SSID，但拿不到 verifiedNetwork：autoJoinCarWifi 走 confirmCurrent，失败则写 sc_bind_failed 并中止，绝不转 specifier
        val hAssociatedNoHandle = Harness()
        hAssociatedNoHandle.shell.connectUpdatesStatusSsidTo = wifi.ssid
        hAssociatedNoHandle.verifiedNetworkBySsid[wifi.ssid] = null
        hAssociatedNoHandle.dialogAnswers.addLast(null)

        hAssociatedNoHandle.joiner.autoJoinCarWifi(wifi)

        assertEquals(1, hAssociatedNoHandle.trace.filterIsInstance<Trace.Ask>().size)
        assertEquals(
            "sc_use_current_title",
            hAssociatedNoHandle.trace
                .filterIsInstance<Trace.Ask>()
                .single()
                .prompt.title,
        )
        assertTrue(
            "已连上目标 SSID 时绝不能退回会弹窗的 specifier",
            hAssociatedNoHandle.trace.filterIsInstance<Trace.JoinCarPlayNetwork>().isEmpty(),
        )
        assertEquals("sc_bind_failed", hAssociatedNoHandle.phases.last())
        assertTrue(
            hAssociatedNoHandle.logs.contains(
                "WIFI: 已连在 \"${wifi.ssid}\" 但未能绑定唯一的 Network 句柄——本轮中止（不退回会弹窗的 specifier，也不发起通配绑定）",
            ),
        )
    }

    @Test
    fun j05_specifierFourOutcomesExistingWifiShortcutsAndExactSimpleNameLog() {
        val wifi = wifiConfig()
        val netExisting = WifiId("net-existing")
        val netSpec = WifiId("net-spec")

        // 1) 请求前系统已连接同一局域网且绑定成功 -> 直接返回 Joined，不调 joinCarPlayNetwork
        val hPreHit = Harness()
        hPreHit.link.apiSsid = wifi.ssid
        hPreHit.verifiedNetworkBySsid[wifi.ssid] = netExisting
        val resPreHit = hPreHit.joiner.joinCarWifiSpecifier(wifi)
        assertEquals(CarWifiLink.JoinResult.Joined(netExisting), resPreHit)
        assertEquals(wifi.ssid, hPreHit.state.boundCarSsid)
        assertEquals(listOf("sc_bound_existing"), hPreHit.phases)
        assertTrue(hPreHit.trace.filterIsInstance<Trace.JoinCarPlayNetwork>().isEmpty())
        assertEquals(
            listOf(
                "WIFI: 系统已连接同一局域网 \"${wifi.ssid}\"——直接绑定现有 Wi-Fi，跳过加入请求",
                ">>> 现有 Wi-Fi 已绑定，ctrl 广播已重新注册，等待车机邀请",
            ),
            hPreHit.logs,
        )

        // 2) 请求前系统已连接同一局域网但现有绑定失败 -> 记录日志并继续发起 specifier 请求，成功后写 sc_joined
        val hPreFailThenJoined = Harness()
        hPreFailThenJoined.link.apiSsid = wifi.ssid
        hPreFailThenJoined.verifiedNetworkBySsid[wifi.ssid] = netExisting
        hPreFailThenJoined.link.bindVerifiedResult = false
        hPreFailThenJoined.link.nextJoinResult = CarWifiLink.JoinResult.Joined(netSpec)
        val resPreFail = hPreFailThenJoined.joiner.joinCarWifiSpecifier(wifi)
        assertEquals(CarWifiLink.JoinResult.Joined(netSpec), resPreFail)
        assertEquals(wifi.ssid, hPreFailThenJoined.state.boundCarSsid)
        assertEquals(listOf("sc_joining_no_data(${wifi.ssid})", "sc_joined"), hPreFailThenJoined.phases)
        assertTrue(hPreFailThenJoined.logs.contains("WIFI: 检测到同一局域网，但现有 Wi-Fi 网络通道绑定失败——继续尝试专用网络"))
        assertTrue(hPreFailThenJoined.logs.contains(">>> WiFi 已加入车机热点（应用专属网络），ctrl 广播已重新注册，等待车机 ctrl 邀请"))

        // 3) 锁内已有同 SSID 的应用网络：跳过 joinCarPlayNetwork，仍保留 Joined 后置 phase/reannounce/log
        val hInLock = Harness()
        hInLock.state.boundCarSsid = wifi.ssid
        hInLock.link.currentNetwork = netSpec
        val resInLock = hInLock.joiner.joinCarWifiSpecifier(wifi, allowExistingSystemWifi = false)
        assertEquals(CarWifiLink.JoinResult.Joined(netSpec), resInLock)
        assertTrue(hInLock.trace.filterIsInstance<Trace.JoinCarPlayNetwork>().isEmpty())
        assertTrue(hInLock.logs.contains("WIFI: 应用专属网络已在位——跳过重复加入"))
        assertEquals(listOf("sc_joining_no_data(${wifi.ssid})", "sc_joined"), hInLock.phases)

        // 4) specifier 返回 Unavailable（及 Unsupported），后置系统已连接近路命中（保留 ${result.javaClass.simpleName}）
        for (failedResult in listOf(CarWifiLink.JoinResult.Unavailable, CarWifiLink.JoinResult.Unsupported)) {
            val hPostHit = Harness()
            hPostHit.link.onJoinCarPlayNetwork = { _, _, _, _, _, _, _, _, _ ->
                // 请求期间系统自动连上了已保存的热点
                hPostHit.link.apiSsid = wifi.ssid
                hPostHit.verifiedNetworkBySsid[wifi.ssid] = netExisting
                failedResult
            }
            val resPostHit = hPostHit.joiner.joinCarWifiSpecifier(wifi)
            assertEquals(CarWifiLink.JoinResult.Joined(netExisting), resPostHit)
            assertTrue(
                hPostHit.logs.contains("WIFI: 应用专属网络加入未成功（${failedResult.javaClass.simpleName}）"),
            )
            assertTrue(
                hPostHit.logs.contains("WIFI: 系统当前已连着 \"${wifi.ssid}\"——直接绑定现有连接（保存网络自动连上的近路）"),
            )
            assertTrue(
                hPostHit.logs.contains(">>> WiFi 已绑定现有 \"${wifi.ssid}\" 连接，ctrl 广播已重新注册"),
            )
            assertEquals(listOf("sc_joining_no_data(${wifi.ssid})", "sc_joined"), hPostHit.phases)
        }
    }

    @Test
    fun j06_specifierQuickAndDeepFallbackCallbacks() {
        val wifi = wifiConfig(ssid = "CarHotspot-O'Reilly", passphrase = "pa'ss")
        val netQuick = WifiId("net-quick")
        val netDeep = WifiId("net-deep")

        // 1) quickFallback 命中：不执行 connect-network，直接绑定并跳过 deepFallback
        val hQuick = Harness()
        hQuick.verifiedNetworkBySsid[wifi.ssid] = netQuick
        hQuick.link.onJoinCarPlayNetwork = { _, _, _, _, _, quick, deep, _, _ ->
            hQuick.link.apiSsid = wifi.ssid
            val q = quick?.invoke()
            if (q != null) {
                CarWifiLink.JoinResult.Joined(q)
            } else {
                val d = deep?.invoke()
                if (d != null) CarWifiLink.JoinResult.Joined(d) else CarWifiLink.JoinResult.Unavailable
            }
        }
        val resQuick = hQuick.joiner.joinCarWifiSpecifier(wifi, allowExistingSystemWifi = false)
        assertEquals(CarWifiLink.JoinResult.Joined(netQuick), resQuick)
        assertEquals(wifi.ssid, hQuick.state.boundCarSsid)
        assertTrue(hQuick.logs.contains("WIFI: 系统已连着 \"${wifi.ssid}\"——直接绑定（快速兜底）"))
        assertFalse(
            hQuick.trace.filterIsInstance<Trace.ShellExec>().any { it.command.startsWith("cmd wifi connect-network") },
        )

        // 2) quickFallback 未命中 -> deepFallback 命中（含单引号 POSIX shell 转义 + 300ms 轮询）
        val hDeep = Harness()
        hDeep.verifiedNetworkBySsid[wifi.ssid] = netDeep
        hDeep.onSleep = { millis, count ->
            if (millis == 300L && count == 2) {
                hDeep.shell.statusSsid = wifi.ssid
            }
        }
        hDeep.link.onJoinCarPlayNetwork = { _, _, _, _, _, quick, deep, _, _ ->
            assertNull(quick?.invoke())
            val d = deep?.invoke()
            if (d != null) CarWifiLink.JoinResult.Joined(d) else CarWifiLink.JoinResult.Unavailable
        }
        val resDeep = hDeep.joiner.joinCarWifiSpecifier(wifi, allowExistingSystemWifi = false)
        assertEquals(CarWifiLink.JoinResult.Joined(netDeep), resDeep)
        assertEquals(wifi.ssid, hDeep.state.boundCarSsid)
        assertEquals(wifi.ssid, hDeep.state.shellFallbackCleanupSsid)
        val connectCmd =
            hDeep.trace
                .filterIsInstance<Trace.ShellExec>()
                .first { it.command.startsWith("cmd wifi connect-network") }
        assertEquals("cmd wifi connect-network 'CarHotspot-O'\\''Reilly' wpa2 'pa'\\''ss'", connectCmd.command)
        assertEquals(10_000L, connectCmd.timeoutMs)
        assertTrue(hDeep.logs.contains("WIFI: connect-network 已连上 \"${wifi.ssid}\""))
    }

    @Test
    fun j08_legacyJoinAllFourExitsAnd20x800msPolling() {
        val wifi = wifiConfig(securityType = 1)
        val netLegacy = WifiId("net-legacy")

        // 1) 无 Shizuku：写 sc_not_on_hotspot 与日志并立即返回
        val hNoShell = Harness()
        hNoShell.joiner.legacyJoinCarWifi(wifi, shellReady = false)
        assertEquals(listOf("sc_not_on_hotspot(${wifi.ssid})"), hNoShell.phases)
        assertEquals(
            listOf(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且 Shizuku 未就绪（无法自动加入）——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            ),
            hNoShell.logs,
        )

        // 2) 已在目标热点：直接核验绑定，不弹窗
        val hAlready = Harness()
        hAlready.link.apiSsid = wifi.ssid
        hAlready.verifiedNetworkBySsid[wifi.ssid] = netLegacy
        hAlready.joiner.legacyJoinCarWifi(wifi, shellReady = true)
        assertTrue(hAlready.trace.filterIsInstance<Trace.Ask>().isEmpty())
        assertTrue(hAlready.logs.contains("WIFI: 手机已在车机热点 \"${wifi.ssid}\"——直接绑定"))
        assertEquals(wifi.ssid, hAlready.state.boundCarSsid)

        // 3) 弹窗无人应答（null）：写 sc_join_failed_retry 与中止日志
        val hNull = Harness()
        hNull.dialogAnswers.addLast(null)
        hNull.joiner.legacyJoinCarWifi(wifi, shellReady = true)
        assertEquals(listOf("sc_join_failed_retry"), hNull.phases)
        assertTrue(hNull.logs.contains("WIFI: 弹窗无人应答——自动加入中止"))

        // 4) 弹窗选 true -> connect-network 失败（空输出退回 exit=N）
        val hCmdFail = Harness()
        hCmdFail.dialogAnswers.addLast(true)
        hCmdFail.shell.connectNetworkExitCode = 9
        hCmdFail.shell.connectNetworkOutput = ""
        hCmdFail.joiner.legacyJoinCarWifi(wifi, shellReady = true)
        assertEquals(listOf("sc_auto_joining(${wifi.ssid})", "sc_auto_join_failed(${wifi.ssid})"), hCmdFail.phases)
        assertTrue(
            hCmdFail.logs.contains(
                "WIFI: connect-network 失败（exit=9）——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            ),
        )

        // 5) 弹窗选 true -> connect-network 成功 -> 20 * 800ms 超时 -> 先 phase 后惰性 hint 再日志
        val hTimeout = Harness()
        hTimeout.evaluateHintsOnArrival = false
        hTimeout.dialogAnswers.addLast(true)
        hTimeout.shell.statusSsid = "Home-WiFi"
        hTimeout.joiner.legacyJoinCarWifi(wifi, shellReady = true)
        assertEquals(20, hTimeout.sleepCount)
        assertEquals(
            listOf(
                "sc_auto_joining(${wifi.ssid})",
                "sc_joining_may_revert(${wifi.ssid})",
                "sc_not_staying(${wifi.ssid})",
            ),
            hTimeout.phases,
        )
        // 验证 hint 在注册时未提前求值，调用时才求值
        assertTrue(hTimeout.evaluatedHints.isEmpty())
        assertEquals(1, hTimeout.lazyHints.size)
        assertEquals("sc_not_staying_hint(${wifi.ssid})", hTimeout.lazyHints.single().invoke())
        assertTrue(
            hTimeout.logs.last().contains(">>> WiFi 自动加入超时（可能被系统切回原 WiFi）"),
        )
    }

    @Test
    fun j09_manualJoinTokenConfirmationAnd120sPolling() {
        val wifi = wifiConfig()
        val netA = WifiId("net-A")
        val netB = WifiId("net-B")

        // 1) 当前有 Wi-Fi 但 SSID 不可读：用户确认同一局域网且令牌未变 -> 持锁绑定 current，不复制密码不跳设置
        val hConfirmOk = Harness()
        hConfirmOk.link.existingWifi = true
        hConfirmOk.link.apiSsid = null
        hConfirmOk.shell.statusSsid = null
        hConfirmOk.link.activeWifiNetwork = netA
        hConfirmOk.dialogAnswers.addLast(true)
        hConfirmOk.joiner.manualJoinCarWifi(wifi)
        assertEquals(wifi.ssid, hConfirmOk.state.boundCarSsid)
        assertEquals(netA, hConfirmOk.link.currentNetwork)
        assertEquals(listOf("sc_bound_current"), hConfirmOk.phases)
        assertTrue(hConfirmOk.logs.contains("WIFI: 用户确认同一局域网，已绑定当前 Wi-Fi（net-A）"))
        assertTrue(hConfirmOk.trace.filterIsInstance<Trace.CopyPassphrase>().isEmpty())

        // 2) 用户确认期间当前网络从 A 换成了 B -> 拒绝套用确认，不绑定 B，直接返回
        val hTokenChanged = Harness()
        hTokenChanged.link.existingWifi = true
        hTokenChanged.link.apiSsid = null
        hTokenChanged.shell.statusSsid = null
        hTokenChanged.link.activeWifiNetwork = netA
        hTokenChanged.onAskChoice = { _, _, _ ->
            hTokenChanged.link.activeWifiNetwork = netB
            true
        }
        hTokenChanged.joiner.manualJoinCarWifi(wifi)
        assertNull(hTokenChanged.state.boundCarSsid)
        assertNull(hTokenChanged.link.currentNetwork)
        assertTrue(hTokenChanged.logs.contains("WIFI: 用户确认期间当前网络已变化——不绑定新的 current"))
        assertTrue(hTokenChanged.trace.filterIsInstance<Trace.CopyPassphrase>().isEmpty())

        // 3) 设置路线：复制密码 -> 打开设置 -> sc_connect_in_settings -> 第 3 次 5000ms 轮询命中并核验绑定
        val hSettingsHit = Harness()
        hSettingsHit.verifiedNetworkBySsid[wifi.ssid] = netA
        hSettingsHit.onSleep = { millis, count ->
            assertEquals(5_000L, millis)
            if (count == 3) {
                hSettingsHit.shell.statusSsid = wifi.ssid
            }
        }
        hSettingsHit.joiner.manualJoinCarWifi(wifi)
        assertEquals(3, hSettingsHit.sleepCount)
        assertEquals(wifi.ssid, hSettingsHit.state.boundCarSsid)
        assertEquals(
            listOf(
                Trace.CopyPassphrase(wifi.passphrase),
                Trace.OpenWlanSettings,
                Trace.Phase("sc_connect_in_settings(${wifi.ssid})"),
            ),
            hSettingsHit.trace.take(3),
        )

        // 4) 设置路线 120s 超时（24 * 5000ms = 120_000ms）：写 sc_join_aborted 与超时日志
        val hSettingsTimeout = Harness()
        hSettingsTimeout.joiner.manualJoinCarWifi(wifi)
        assertEquals(24, hSettingsTimeout.sleepCount)
        assertEquals("sc_join_aborted(${wifi.ssid})", hSettingsTimeout.phases.last())
        assertEquals("WIFI: 手动连接等待超时——自动加入中止", hSettingsTimeout.logs.last())
    }

    @Test
    fun j10_phoneModeSystemWifiCartesianProductAndNullConnector() {
        val wifi = wifiConfig()
        val systemStates =
            listOf(
                CarWifiLink.SystemWifiState.NONE,
                CarWifiLink.SystemWifiState.TARGET,
                CarWifiLink.SystemWifiState.OTHER,
                CarWifiLink.SystemWifiState.UNKNOWN,
            )
        // saved: false (empty list), true (non-empty list), null (list-networks failed)
        val savedConfigs = listOf<Boolean?>(false, true, null)

        for (sysState in systemStates) {
            for (saved in savedConfigs) {
                val harness = Harness()
                harness.preference = CarWifiInternetPreference.PHONE
                harness.shellReady = true
                harness.link.systemStateBySsid[wifi.ssid] = sysState
                when (saved) {
                    false -> {
                        harness.shell.listNetworksExitCode = 0
                        harness.shell.listNetworksOutput = "Network Id      SSID      Security type\n"
                    }
                    true -> {
                        harness.shell.listNetworksExitCode = 0
                        harness.shell.listNetworksOutput =
                            "Network Id      SSID      Security type\n" +
                            "12              ${wifi.ssid}   wpa2-psk\n"
                    }
                    null -> {
                        harness.shell.listNetworksExitCode = 1
                    }
                }
                // 默认给弹窗返回 false，观察触发了哪个弹窗（或直接 PROCEED）
                harness.dialogAnswers.addLast(false)
                val safe = harness.joiner.ensurePhoneModeSystemWifiSafe(wifi, shellReady = true)
                val asks = harness.trace.filterIsInstance<Trace.Ask>()

                val expectedConflict = sysState == CarWifiLink.SystemWifiState.TARGET || saved == true
                val expectedConfirm = !expectedConflict && (sysState == CarWifiLink.SystemWifiState.UNKNOWN || saved == null)

                when {
                    expectedConflict -> {
                        assertFalse("TARGET 或 saved=true 必须阻断 ($sysState, saved=$saved)", safe)
                        assertEquals("sc_phone_mode_wifi_title", asks.single().prompt.title)
                        assertEquals(wifi.ssid, harness.state.phoneModeBlockedSsid)
                    }
                    expectedConfirm -> {
                        assertFalse("UNKNOWN 或 saved=null 选 false 必须阻断 ($sysState, saved=$saved)", safe)
                        assertEquals("sc_phone_mode_wifi_unknown_title", asks.single().prompt.title)
                        assertEquals(wifi.ssid, harness.state.phoneModeBlockedSsid)
                    }
                    else -> {
                        assertTrue("NONE/OTHER 且 saved=false 必须直接放行 ($sysState, saved=$saved)", safe)
                        assertTrue(asks.isEmpty())
                        assertNull(harness.state.phoneModeBlockedSsid)
                    }
                }
            }
        }

        // CAR 偏好下无论系统状态如何均直接 PROCEED，不查 savedNetworkIds、不弹窗
        val hCar = Harness()
        hCar.preference = CarWifiInternetPreference.CAR
        hCar.link.systemStateBySsid[wifi.ssid] = CarWifiLink.SystemWifiState.TARGET
        hCar.shell.listNetworksOutput = "Network Id      SSID      Security type\n1   ${wifi.ssid}   wpa2-psk\n"
        assertTrue(hCar.joiner.ensurePhoneModeSystemWifiSafe(wifi, shellReady = true))
        assertTrue(hCar.trace.filterIsInstance<Trace.Ask>().isEmpty())
        assertTrue(hCar.trace.filterIsInstance<Trace.ShellExec>().isEmpty())

        // wifiConnector 为 null 且 saved=false：现状 systemWifi == null 不等价 UNKNOWN，按 PROCEED 处理
        val hNullConnector = Harness()
        hNullConnector.preference = CarWifiInternetPreference.PHONE
        hNullConnector.linkAvailable = false
        hNullConnector.shell.listNetworksOutput = "Network Id      SSID      Security type\n"
        assertTrue(hNullConnector.joiner.ensurePhoneModeSystemWifiSafe(wifi, shellReady = true))
        assertTrue(hNullConnector.trace.filterIsInstance<Trace.Ask>().isEmpty())
    }

    @Test
    fun j11_phoneModeDialogsBlockedSsidConsumptionAndPromptSpecifierFailed() {
        val wifi = wifiConfig()

        // 1) CONFLICT 选 true：打开 WLAN 设置，但仍返回 false 并记录 phoneModeBlockedSsid、phase、hint、日志
        val hConflictTrue = Harness()
        hConflictTrue.preference = CarWifiInternetPreference.PHONE
        hConflictTrue.link.systemStateBySsid[wifi.ssid] = CarWifiLink.SystemWifiState.TARGET
        hConflictTrue.dialogAnswers.addLast(true)
        assertFalse(hConflictTrue.joiner.ensurePhoneModeSystemWifiSafe(wifi, shellReady = false))
        assertEquals(1, hConflictTrue.trace.filterIsInstance<Trace.OpenWlanSettings>().size)
        assertEquals(wifi.ssid, hConflictTrue.state.phoneModeBlockedSsid)
        assertEquals(listOf("sc_phone_mode_wifi_blocked(${wifi.ssid})"), hConflictTrue.phases)
        assertEquals(listOf("sc_phone_mode_wifi_blocked_hint(${wifi.ssid})"), hConflictTrue.evaluatedHints)
        assertTrue(
            hConflictTrue.logs.contains("NETWORK: 手机流量优先需要先清除系统 WLAN 热点 \"${wifi.ssid}\"——本轮已停止"),
        )

        // 2) CONFIRM 选 true：记录确认日志、清 phoneModeBlockedSsid 并放行
        val hConfirmTrue = Harness()
        hConfirmTrue.preference = CarWifiInternetPreference.PHONE
        hConfirmTrue.state.phoneModeBlockedSsid = wifi.ssid
        hConfirmTrue.link.systemStateBySsid[wifi.ssid] = CarWifiLink.SystemWifiState.NONE
        hConfirmTrue.dialogAnswers.addLast(true)
        assertTrue(hConfirmTrue.joiner.ensurePhoneModeSystemWifiSafe(wifi, shellReady = false))
        assertNull(hConfirmTrue.state.phoneModeBlockedSsid)
        assertTrue(
            hConfirmTrue.logs.contains("NETWORK: 系统 WLAN 连接或保存状态不可确认；用户确认无冲突，继续手机流量优先"),
        )

        // 3) 同 SSID 阻断标记单次消费：promptSpecifierFailed 入口消费后不弹第二次失败框；不同 SSID 不误消费
        val hConsume = Harness()
        hConsume.preference = CarWifiInternetPreference.PHONE
        hConsume.state.phoneModeBlockedSsid = "Other-SSID"
        hConsume.dialogAnswers.addLast(null)
        hConsume.joiner.promptSpecifierFailed(wifi, shellReady = true)
        assertEquals("不同 SSID 的阻断标记不得被误消费", "Other-SSID", hConsume.state.phoneModeBlockedSsid)
        assertEquals(1, hConsume.trace.filterIsInstance<Trace.Ask>().size)

        hConsume.trace.clear()
        hConsume.state.phoneModeBlockedSsid = wifi.ssid
        hConsume.joiner.promptSpecifierFailed(wifi, shellReady = true)
        assertNull("同 SSID 阻断标记应被消费清 null", hConsume.state.phoneModeBlockedSsid)
        assertTrue("消费阻断标记后不得再弹通用失败框", hConsume.trace.filterIsInstance<Trace.Ask>().isEmpty())

        // 4) promptSpecifierFailed 点重试时：若重试在 PHONE 模式触发阻断，消费标记后不再写 sc_join_failed_manual
        val hRetryBlocked = Harness()
        hRetryBlocked.preference = CarWifiInternetPreference.PHONE
        hRetryBlocked.link.systemStateBySsid[wifi.ssid] = CarWifiLink.SystemWifiState.TARGET
        // 第 1 个框：失败重试框选 true（重试）；第 2 个框：joinCarWifi 内的 PHONE CONFLICT 框选 false
        hRetryBlocked.dialogAnswers.addLast(true)
        hRetryBlocked.dialogAnswers.addLast(false)
        hRetryBlocked.joiner.promptSpecifierFailed(wifi, shellReady = false)
        assertNull(hRetryBlocked.state.phoneModeBlockedSsid)
        assertFalse(
            "重试因 PHONE 冲突阻断后不得覆盖成 sc_join_failed_manual",
            hRetryBlocked.phases.contains("sc_join_failed_manual"),
        )
        assertEquals("sc_phone_mode_wifi_blocked(${wifi.ssid})", hRetryBlocked.phases.last())

        // 5) promptSpecifierFailed 点重试返回 Unsupported：PHONE 模式只转手动指引，不走 legacy
        val hRetryUnsupportedPhone = Harness()
        hRetryUnsupportedPhone.preference = CarWifiInternetPreference.PHONE
        hRetryUnsupportedPhone.link.systemStateBySsid[wifi.ssid] = CarWifiLink.SystemWifiState.NONE
        hRetryUnsupportedPhone.link.nextJoinResult = CarWifiLink.JoinResult.Unsupported
        hRetryUnsupportedPhone.dialogAnswers.addLast(true)
        // manualJoin 轮询第 1 次 sleep 后取消以结束测试
        var cancelManual = false
        hRetryUnsupportedPhone.onSleep = { _, _ -> cancelManual = true }
        hRetryUnsupportedPhone.joiner.promptSpecifierFailed(wifi, shellReady = true, isCancelled = { cancelManual })
        assertTrue(
            hRetryUnsupportedPhone.logs.contains("NETWORK: 手机流量优先——无法使用应用专属网络，转手动 WLAN 指引"),
        )
        assertEquals(1, hRetryUnsupportedPhone.trace.filterIsInstance<Trace.CopyPassphrase>().size)
    }

    @Test
    fun j12_specifierDeepFallbackSavedNetworkThreeStateRegistration() {
        val wifi = wifiConfig()

        // 1) 此前未保存（空列表）+ connect-network 成功 -> 立即登记 shellFallbackCleanupSsid，即使随后超时/取消也保留登记
        val hNewSaved = Harness()
        hNewSaved.shell.listNetworksOutput = "Network Id      SSID      Security type\n"
        assertNull(hNewSaved.joiner.specifierDeepFallback(wifi))
        assertEquals(30, hNewSaved.sleepCount)
        assertEquals(wifi.ssid, hNewSaved.state.shellFallbackCleanupSsid)
        assertTrue(hNewSaved.logs.contains("WIFI: connect-network 已执行（新存系统网络，已登记会话末自动清除）"))
        assertTrue(hNewSaved.logs.contains("WIFI: connect-network 后 9s 未切到车机热点——按失败返回（系统网络已登记，会话末清除）"))

        // 2) 此前已保存（非空列表）+ connect-network 成功 -> 不登记清理
        val hPreExisting = Harness()
        hPreExisting.shell.listNetworksOutput =
            "Network Id      SSID      Security type\n" +
            "5               ${wifi.ssid}   wpa2-psk\n"
        hPreExisting.globalCancelled = true
        assertNull(hPreExisting.joiner.specifierDeepFallback(wifi))
        assertNull(hPreExisting.state.shellFallbackCleanupSsid)
        assertTrue(hPreExisting.logs.contains("WIFI: \"${wifi.ssid}\" 此前已保存在系统（用户选过保留）——不作会话末清除"))

        // 3) 保存状态读取失败（null）+ connect-network 成功 -> 为防误删不登记清理
        val hUnknownSaved = Harness()
        hUnknownSaved.shell.listNetworksExitCode = 2
        hUnknownSaved.globalCancelled = true
        assertNull(hUnknownSaved.joiner.specifierDeepFallback(wifi))
        assertNull(hUnknownSaved.state.shellFallbackCleanupSsid)
        assertTrue(hUnknownSaved.logs.contains("WIFI: 无法读取系统已保存网络（exit=2）——跳过检查"))
        assertTrue(hUnknownSaved.logs.contains("WIFI: 无法读取 \"${wifi.ssid}\" 的系统保存状态——为防误删，不作会话末清除"))
    }

    @Test
    fun j13_promptForgetSavedNetworkIfAnyDirectAndUncalledInProduction() {
        val ssid = "SavedCar-AP"
        val harness = Harness()
        harness.shell.listNetworksOutput =
            "Network Id      SSID      Security type\n" +
            "3               $ssid          wpa2-psk\n" +
            "8               $ssid          wpa3-sae\n"
        harness.dialogAnswers.addLast(true)

        harness.joiner.promptForgetSavedNetworkIfAny(ssid)

        val forgetCmds =
            harness.trace
                .filterIsInstance<Trace.ShellExec>()
                .filter { it.command.startsWith("cmd wifi forget-network") }
                .map { it.command }
        assertEquals(listOf("cmd wifi forget-network 3", "cmd wifi forget-network 8"), forgetCmds)
        assertTrue(harness.logs.contains("WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=3 ok=true）"))
        assertTrue(harness.logs.contains("WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=8 ok=true）"))

        // 同一进程同一 SSID 第二次调用：单次去重，不再查询或弹窗
        harness.trace.clear()
        harness.joiner.promptForgetSavedNetworkIfAny(ssid)
        assertTrue(harness.trace.isEmpty())

        // S 契约：生产主源码中 promptForgetSavedNetworkIfAny 只有定义，没有任何调用点
        val totalOccurrences =
            SourceTree.mainKotlinFiles.values.sumOf { file ->
                Regex("""\bpromptForgetSavedNetworkIfAny\s*\(""").findAll(KotlinSource.scan(file.readText()).code).count()
            }
        assertEquals("promptForgetSavedNetworkIfAny 必须保持未接入热路径（仅 1 处定义）", 1, totalOccurrences)
    }

    @Test
    fun j14_waitForWifiEnabledThreeDialogChoicesAndCumulativePolling() {
        // 1) 用户选 false
        val hFalse = Harness()
        hFalse.dialogAnswers.addLast(false)
        assertFalse(hFalse.joiner.waitForWifiEnabled())
        assertEquals(listOf("sc_cancelled_wifi_needed"), hFalse.phases)
        assertEquals(listOf("WIFI: 用户取消——自动加入中止（WiFi 未开启）"), hFalse.logs)

        // 2) 弹窗无人应答 null
        val hNull = Harness()
        hNull.dialogAnswers.addLast(null)
        assertFalse(hNull.joiner.waitForWifiEnabled())
        assertEquals(listOf("sc_wifi_off_needed"), hNull.phases)
        assertEquals(listOf("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）"), hNull.logs)

        // 3) 用户选 true，60 * 2000ms = 120_000ms 累计等待超时
        val hTimeout = Harness()
        hTimeout.shell.statusEnabled = false
        hTimeout.dialogAnswers.addLast(true)
        assertFalse(hTimeout.joiner.waitForWifiEnabled())
        assertEquals(60, hTimeout.sleepCount)
        assertEquals(listOf("sc_waiting_wifi", "sc_wifi_off"), hTimeout.phases)
        assertEquals(listOf("sc_turn_on_wifi"), hTimeout.evaluatedHints)
        assertEquals("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接", hTimeout.logs.last())

        // 4) 用户选 true，第 2 次 2000ms 轮询时打开了 WLAN
        val hEnabled = Harness()
        hEnabled.shell.statusEnabled = false
        hEnabled.dialogAnswers.addLast(true)
        hEnabled.onSleep = { _, count ->
            if (count == 2) hEnabled.shell.statusEnabled = true
        }
        assertTrue(hEnabled.joiner.waitForWifiEnabled())
        assertEquals(2, hEnabled.sleepCount)
        assertEquals(listOf("sc_waiting_wifi"), hEnabled.phases)
    }

    @Test
    fun j15_bindExistingCarWifiVerifiedVsCurrentAnd3000msWaitLoop() {
        val ssid = "CarHotspot"
        val net1 = WifiId("net-1")

        // 1) verifySsid = false 且 currentWifiNetwork 为 null：立即返回 null，不发通配 bindCurrentWifi
        val hNoCurrent = Harness()
        assertNull(hNoCurrent.joiner.bindExistingCarWifi(hNoCurrent.link, ssid, verifySsid = false))
        assertTrue(hNoCurrent.trace.filterIsInstance<Trace.BindCurrentWifi>().isEmpty())

        // 2) verifySsid = true 且无 verifiedNetwork：调用 bindCurrentWifi 并最多等待 3000ms（30 * 100ms），最终返回 currentNetwork
        val hPollTimeout = Harness()
        assertNull(hPollTimeout.joiner.bindExistingCarWifi(hPollTimeout.link, ssid, verifySsid = true))
        assertEquals(30, hPollTimeout.sleepCount)
        assertEquals(
            Trace.BindCurrentWifi(ssidHint = ssid, expectedSsid = ssid),
            hPollTimeout.trace.filterIsInstance<Trace.BindCurrentWifi>().single(),
        )

        // 3) bindCurrentWifi 异步在第 3 次 100ms 轮询后完成并设置 currentNetwork
        val hPollHit = Harness()
        var callback: ((Boolean) -> Unit)? = null
        hPollHit.link.onBindCurrentWifi = { _, _, onResult ->
            callback = onResult
        }
        hPollHit.onSleep = { millis, count ->
            assertEquals(100L, millis)
            if (count == 3) {
                hPollHit.link.currentNetwork = net1
                callback?.invoke(true)
            }
        }
        assertSame(net1, hPollHit.joiner.bindExistingCarWifi(hPollHit.link, ssid, verifySsid = true))
        assertEquals(3, hPollHit.sleepCount)
    }

    @Test
    fun j07_liveSsidShellStatusQuoteAndLogHelpers() {
        val harness = Harness()

        // 1) API SSID 优先于 shell SSID；API 为空/空白时才回退到 shell status
        harness.link.apiSsid = "Api-CarHotspot"
        harness.shell.statusSsid = "Shell-CarHotspot"
        assertEquals("Api-CarHotspot", harness.joiner.liveWifiSsid())
        assertEquals(0, harness.trace.filterIsInstance<Trace.ShellExec>().size)

        harness.link.apiSsid = "   "
        assertEquals("Shell-CarHotspot", harness.joiner.liveWifiSsid())
        assertEquals(1, harness.trace.filterIsInstance<Trace.ShellExec>().size)

        // 2) shellWifiStatus 失败时回退为 (enabled=true, ssid=null)
        harness.link.apiSsid = null
        harness.shell.statusExitCode = 1
        assertEquals(true to null, harness.joiner.shellWifiStatus())
        assertFalse(harness.joiner.shellWifiDisabled())
        assertNull(harness.joiner.liveWifiSsid())
        assertNull(harness.joiner.liveWifiMatches("Shell-CarHotspot"))

        // 3) shellWifiStatus 正常解析 disabled 与 connected to "..."
        harness.shell.statusExitCode = 0
        harness.shell.statusEnabled = false
        assertTrue(harness.joiner.shellWifiDisabled())
        harness.shell.statusEnabled = true
        harness.shell.statusSsid = "CarPlay-AP"
        assertEquals(true, harness.joiner.liveWifiMatches("\"CarPlay-AP\""))
        assertEquals(false, harness.joiner.liveWifiMatches("Other-AP"))

        // 4) wifiForLog 不泄露原始 SSID；shellQuote 单引号转义
        assertEquals("WiFi（SSID 已读取）", harness.joiner.wifiForLog("Secret\"SSID"))
        assertEquals("WiFi（SSID 不可读）", harness.joiner.wifiForLog(null))
        assertEquals("WiFi（未连接）", harness.joiner.wifiForLog("  ", "未连接"))
        assertEquals("'a'\\''b c'", harness.joiner.shellQuote("a'b c"))
    }

    @Test
    fun s01_s02_sourceWiringAndNoVehicleWordsOrForbiddenDependencies() {
        val controllerCode = SourceTree.scan("SessionController.kt").code
        val joinerCode = SourceTree.scan("net/CarWifiJoiner.kt").code

        assertEquals(
            "SessionController 必须只构造一次长生命周期 CarWifiJoiner",
            1,
            Regex("""\bCarWifiJoiner<Network,\s*WifiNetworkToken<Network>>\(""").findAll(controllerCode).count(),
        )

        // 8 个同名薄转发保留在 SessionController
        val expectedForwarders =
            listOf(
                "carWifiJoiner.autoJoinCarWifi(",
                "carWifiJoiner.joinCarWifi(wifi, shellReady)",
                "carWifiJoiner.bindExistingCarWifi(connector, ssid, verifySsid)",
                "carWifiJoiner.promptSpecifierFailed(wifi, shellReady, isCancelled)",
                "carWifiJoiner.liveWifiSsid()",
                "carWifiJoiner.wifiForLog(ssid, unknown)",
                "carWifiJoiner.liveWifiMatches(ssid)",
                "carWifiJoiner.shellQuote(s)",
            )
        expectedForwarders.forEach { call ->
            assertTrue("SessionController 必须包含转发调用 $call", controllerCode.contains(call))
        }

        // 15 个内部实现函数已迁入 CarWifiJoiner，SessionController 不保留算法副本
        val movedInternalFunctions =
            listOf(
                "joinCarWifiSilently",
                "joinCarWifiSilentlyLocked",
                "ensureWifiEnabledSilently",
                "waitForCarSsid",
                "joinCarWifiSpecifier",
                "specifierQuickFallback",
                "specifierDeepFallback",
                "promptForgetSavedNetworkIfAny",
                "savedNetworkIds",
                "waitForWifiEnabled",
                "ensurePhoneModeSystemWifiSafe",
                "manualJoinCarWifi",
                "legacyJoinCarWifi",
                "shellWifiStatus",
                "shellWifiDisabled",
            )
        movedInternalFunctions.forEach { fn ->
            assertFalse("SessionController 不得残留 $fn 的重复定义", controllerCode.contains("fun $fn("))
            assertTrue("CarWifiJoiner 必须定义 $fn", joinerCode.contains("fun $fn("))
        }

        // 2c-3 端点网络分派函数已迁入 CarWifiJoiner；上层绑定/弹窗/清理仍留在 SessionController
        val movedEndpointFunctions =
            listOf(
                "joinAccessoryWifi",
                "bindHandoffWifi",
                "confirmCurrentWifiThenContinue",
                "joinKnownWifiManually",
                "waitForKnownWifiThenContinue",
                "waitForManualWifiThenContinue",
            )
        movedEndpointFunctions.forEach { fn ->
            assertFalse("SessionController 不得残留 $fn 的重复定义", controllerCode.contains("fun $fn("))
            assertTrue("CarWifiJoiner 必须定义 $fn", joinerCode.contains("fun $fn("))
        }

        val retainedInController =
            listOf(
                "onWifiCredentials",
                "verifiedWifiNetworkFor",
                "bindCarWifiAndRegister",
                "cleanupShellFallbackNetwork",
                "askWifiJoinChoice",
                "openWlanSettings",
                "copyHotspotPsk",
            )
        retainedInController.forEach { fn ->
            assertTrue("SessionController 必须保留 $fn", controllerCode.contains("fun $fn("))
        }

        // CarWifiJoiner 禁止直接依赖上层或 Android 静态阻塞设施
        val forbiddenInJoiner =
            listOf(
                "SessionController",
                "SessionUiPort",
                "AttemptScope",
                "InternetNetwork",
                "InternetNetworkPrefs",
                "InternetNetworkPolicy",
                "PhoneModeSystemWifiDecision",
                "ShizukuGate",
                "SystemClock",
                "Thread.sleep",
            )
        forbiddenInJoiner.forEach { token ->
            assertFalse(
                "CarWifiJoiner 不得直接引用 $token",
                Regex("""\b${Regex.escape(token)}\b""").containsMatchIn(joinerCode),
            )
        }

        val vehicleWords =
            setOf(
                "passat",
                "audi",
                "honda",
                "cadillac",
                "diplay",
                "winplay",
                "macplay",
                "chery",
                "arrizo",
                "mib3",
                "id7",
                "vw",
                "cinemo",
            )
        val hits = KotlinSource.identifierWords(joinerCode) intersect vehicleWords
        assertTrue("CarWifiJoiner 标识符不得含车型词：$hits", hits.isEmpty())
    }

    @Test
    fun j16_cancellationLayeringCallerPredicateVsGlobalConnectCancelled() {
        val wifi = wifiConfig()
        val harness = Harness()
        harness.globalCancelled = false
        var callerCancelled = true

        // caller isCancelled=true 而 globalCancelled=false 时：
        // legacy / promptSpecifierFailed / ensurePhoneMode 在 askChoice 前即短路返回；
        // waitForWifiEnabled 把 caller isCancelled 传给 askChoice（broker 入口立即判过期返回 null），随后在 when(open) 前直接返回 false；
        // manualJoin 在首次 sleep 前立即返回，全程 0 次 sleep。
        harness.joiner.legacyJoinCarWifi(wifi, shellReady = true, isCancelled = { callerCancelled })
        harness.joiner.promptSpecifierFailed(wifi, shellReady = true, isCancelled = { callerCancelled })
        assertFalse(harness.joiner.ensurePhoneModeSystemWifiSafe(wifi, shellReady = true, isCancelled = { callerCancelled }))
        assertTrue(harness.trace.filterIsInstance<Trace.Ask>().isEmpty())

        assertFalse(harness.joiner.waitForWifiEnabled(isCancelled = { callerCancelled }))
        val wifiOffAsk = harness.trace.filterIsInstance<Trace.Ask>().single()
        assertTrue("waitForWifiEnabled 必须把 caller isCancelled 透传给 askChoice", wifiOffAsk.cancelledAtEntry)
        assertFalse("取消时不得落入 null 分支写 sc_wifi_off_needed", harness.phases.contains("sc_wifi_off_needed"))

        val asksBeforeManual = harness.trace.count { it is Trace.Ask }
        harness.joiner.manualJoinCarWifi(wifi, isCancelled = { callerCancelled })
        assertEquals(0, harness.sleepCount)

        // manualJoin 在第 1 次 sleep 期间 caller 失效（globalCancelled 仍为 false）：睡后立即返回，不触发 bindVerifiedCarWifi
        callerCancelled = false
        harness.shell.statusSsid = wifi.ssid
        harness.onSleep = { _, _ -> callerCancelled = true }
        harness.joiner.manualJoinCarWifi(wifi, isCancelled = { callerCancelled })
        assertEquals(1, harness.sleepCount)
        assertTrue(harness.trace.filterIsInstance<Trace.BindVerifiedAndRegister>().isEmpty())
        assertEquals("manualJoin 不得新增确认弹窗", asksBeforeManual, harness.trace.count { it is Trace.Ask })
    }

    @Test
    fun j18_endpointDispatchCoversAllEightActionsAndPreservesWaitReleases() {
        val wifi = wifiConfig()
        val net = WifiId("net-endpoint")

        // IGNORE：非当前轮次直接忽略并释放交接，不发起任何网络动作
        val hIgnore = Harness()
        val ignore = hIgnore.handoff().apply { targetCurrent = false }
        ignore.dispatch(ignore.request(CarWifiHandoffSource.ACCESSORY, wifi.ssid, accessoryWifi = wifi))
        assertEquals(1, ignore.releases.size)
        assertTrue(ignore.results.isEmpty())
        assertTrue(hIgnore.logs.contains("WIFI: 旧轮次/非目标端点的网络动作已忽略"))

        // WAIT：没有可验证归属时保持等待并释放交接
        val hWait = Harness()
        val wait = hWait.handoff()
        wait.dispatch(wait.request(CarWifiHandoffSource.NONE, wifi.ssid))
        assertEquals(1, wait.releases.size)
        assertTrue(wait.results.isEmpty())
        assertTrue(hWait.logs.contains("WIFI: 没有可验证的网络归属——保持等待，不绑定任意 Wi-Fi"))

        // JOIN_ACCESSORY -> 复用 joinAccessoryWifi 的后台名与续体
        val hJoinAccessory = Harness()
        hJoinAccessory.dialogAnswers.addLast(null)
        hJoinAccessory.link.nextJoinResult = CarWifiLink.JoinResult.Joined(net)
        val joinAccessory = hJoinAccessory.handoff()
        joinAccessory.dispatch(joinAccessory.request(CarWifiHandoffSource.ACCESSORY, wifi.ssid, accessoryWifi = wifi))
        assertEquals(listOf("wifi-join-on-endpoint"), hJoinAccessory.backgroundNames())
        assertEquals(listOf(true), joinAccessory.results)

        // JOIN_CACHED -> 只对 KNOWN_SSID 的缓存走缓存加入
        val hJoinCached = Harness()
        hJoinCached.link.nextJoinResult = CarWifiLink.JoinResult.Joined(net)
        val joinCached = hJoinCached.handoff()
        joinCached.dispatch(
            joinCached.request(
                CarWifiHandoffSource.KNOWN_SSID,
                wifi.ssid,
                cachedWifi = wifi,
            ),
        )
        assertEquals(listOf("wifi-join-on-endpoint"), hJoinCached.backgroundNames())
        assertEquals(listOf(true), joinCached.results)
        assertTrue(hJoinCached.logs.any { it.startsWith("WIFI: 交接决策=JOIN_CACHED 来源=KNOWN_SSID") })

        // ACCESSORY/CURRENT_NETWORK 即使误带缓存也不得进入 JOIN_CACHED
        val hAccessoryWithCache = Harness()
        hAccessoryWithCache.link.nextJoinResult = CarWifiLink.JoinResult.Joined(net)
        val accessoryWithCache = hAccessoryWithCache.handoff()
        accessoryWithCache.dispatch(
            accessoryWithCache.request(
                CarWifiHandoffSource.ACCESSORY,
                wifi.ssid,
                accessoryWifi = wifi,
                cachedWifi = wifi,
            ),
        )
        assertTrue(
            "缓存只对 KNOWN_SSID 生效",
            hAccessoryWithCache.logs.none { it.contains("交接决策=JOIN_CACHED") },
        )

        // BIND_KNOWN -> 绑定已核验的网络，后台名保持 wifi-bind-handoff
        val hBindKnown = Harness()
        hBindKnown.link.apiSsid = wifi.ssid
        hBindKnown.verifiedNetworkBySsid[wifi.ssid] = net
        val bindKnown = hBindKnown.handoff()
        bindKnown.dispatch(bindKnown.request(CarWifiHandoffSource.KNOWN_SSID, wifi.ssid))
        assertEquals(listOf("wifi-bind-handoff"), hBindKnown.backgroundNames())
        assertEquals(listOf(true), bindKnown.results)
        assertEquals(wifi.ssid, hBindKnown.state.boundCarSsid)

        // BIND_CURRENT -> 使用上层提供的已验证句柄，并保留 requiredCurrent
        val hBindCurrent = Harness()
        hBindCurrent.link.currentNetwork = net
        val bindCurrent = hBindCurrent.handoff().apply { freshInvite = true }
        bindCurrent.dispatch(
            bindCurrent.request(
                CarWifiHandoffSource.CURRENT_NETWORK,
                wifi.ssid,
                verifiedCurrentNetwork = net,
                requireCurrentBinding = true,
            ),
        )
        assertEquals(listOf("wifi-bind-handoff"), hBindCurrent.backgroundNames())
        assertEquals(listOf(true), bindCurrent.results)
        assertTrue(
            hBindCurrent.trace
                .filterIsInstance<Trace.BindVerifiedAndRegister>()
                .single()
                .requiredCurrent == net,
        )
        assertTrue(
            "invite callback must not be needed when networkToBind is present",
            hBindCurrent.trace.filterIsInstance<Trace.BindVerifiedAndRegister>().size == 1,
        )

        // ASK_CONFIRM_CURRENT -> 后台确认流程；选 false 时不绑定，转入已知网等待
        val hAskConfirm = Harness()
        hAskConfirm.link.currentNetwork = net
        hAskConfirm.link.existingWifi = true
        hAskConfirm.link.apiSsid = null
        hAskConfirm.link.uniqueWifiNetwork = null
        hAskConfirm.dialogAnswers.addLast(false)
        val askConfirm =
            hAskConfirm.handoff().apply {
                freshInvite = false
            }
        askConfirm.dispatch(askConfirm.request(CarWifiHandoffSource.KNOWN_SSID, wifi.ssid))
        assertEquals(listOf("wifi-confirm-current"), hAskConfirm.backgroundNames())
        assertEquals(listOf(false), askConfirm.results)
        assertTrue(hAskConfirm.trace.filterIsInstance<Trace.OpenWlanSettings>().isNotEmpty())

        // ASK_JOIN_KNOWN -> 已知 SSID 手动加入流程，后台名 wifi-manual-known
        val hAskKnown = Harness()
        hAskKnown.dialogAnswers.addLast(null)
        hAskKnown.link.apiSsid = "Office-WiFi"
        val askKnown = hAskKnown.handoff()
        askKnown.dispatch(askKnown.request(CarWifiHandoffSource.KNOWN_SSID, wifi.ssid))
        assertEquals(listOf("wifi-manual-known"), hAskKnown.backgroundNames())
        assertEquals(listOf(false), askKnown.results)
    }

    @Test
    fun j18_endpointCacheFailureRealtimeRetryClearsOnceAndDoesNotRecurseIntoJoinCached() {
        val wifi = wifiConfig()
        val h = Harness()
        h.link.apiSsid = "Office-WiFi"
        h.dialogAnswers.addLast(null)
        h.link.nextJoinResult = CarWifiLink.JoinResult.Unavailable
        val handoff = h.handoff()

        handoff.dispatch(
            handoff.request(
                CarWifiHandoffSource.KNOWN_SSID,
                wifi.ssid,
                cachedWifi = wifi,
            ),
        )

        assertEquals("缓存失败且 scope active 才清一次缓存", 1, handoff.clears.size)
        assertEquals(listOf(false), handoff.results)
        assertTrue(h.logs.contains("WIFI: 缓存热点凭据入网失败——已清除并回退=${WifiHandoffAction.ASK_JOIN_KNOWN}"))
        assertEquals("重判不得再次进入 JOIN_CACHED", 1, h.backgroundNames().count { it == "wifi-join-on-endpoint" })
    }

    @Test
    fun j19_confirmCurrentTokenChangesThreeTimesAndReasksWithoutBindingWrongNetwork() {
        val wifi = wifiConfig()
        val netA = WifiId("net-A")
        val netB = WifiId("net-B")
        val netC = WifiId("net-C")
        val h = Harness()
        h.link.currentNetwork = netA
        h.link.apiSsid = null
        var step = 0
        h.onAskChoice = { _, _, _ ->
            step++
            h.link.currentNetwork =
                when (step) {
                    1 -> netB
                    2 -> netC
                    else -> netA
                }
            true
        }
        h.link.existingWifi = true
        h.link.uniqueWifiNetwork = null
        h.shell.statusSsid = wifi.ssid
        val handoff = h.handoff()
        handoff.targetCurrent = true
        handoff.scopeActive = true

        handoff.dispatch(handoff.request(CarWifiHandoffSource.KNOWN_SSID, wifi.ssid))

        assertEquals("连续换网会在至多三次尝试内重新提示", 3, step)
        assertEquals("三次令牌都对不上时不得绑定任意网络", listOf(false), handoff.results)
        assertTrue(
            h.logs.count { it.contains("用户确认期间当前网络已变化——重新提示") } >= 2,
        )
        assertTrue(h.trace.filterIsInstance<Trace.BindVerifiedAndRegister>().isEmpty())
    }

    @Test
    fun j20_knownWifiWaitEightSecondUnknownConfirmationAnd120SecondTimeout() {
        val wifi = wifiConfig()
        val h = Harness()
        h.shell.statusSsid = null
        h.link.apiSsid = null
        h.dialogAnswers.addLast(null)
        val handoff = h.handoff()
        handoff.dispatch(handoff.request(CarWifiHandoffSource.KNOWN_SSID, wifi.ssid))

        assertEquals("8 秒未知确认必须先发生一次", 1, h.trace.filterIsInstance<Trace.Ask>().size)
        assertEquals("确认无人应答后立即失败，不继续硬等", listOf(false), handoff.results)
        assertTrue(h.sleepCount == 0)

        val hTimeout = Harness()
        hTimeout.shell.statusSsid = null
        hTimeout.link.apiSsid = null
        hTimeout.dialogAnswers.addLast(false)
        val knownTimeout = hTimeout.handoff()
        knownTimeout.dispatch(knownTimeout.request(CarWifiHandoffSource.KNOWN_SSID, wifi.ssid))
        assertEquals(120, hTimeout.sleepCount)
        assertEquals(listOf(false), knownTimeout.results)
        assertTrue(hTimeout.logs.last().contains("等待超时——继续等待车机邀请或晚到端点"))
        assertTrue(
            "未知网络先确认，选 false 后继续等待并再次给确认机会",
            hTimeout.trace.filterIsInstance<Trace.Ask>().size >= 1,
        )
    }

    @Test
    fun j20_lateEndpointDuringKnownWaitReplacesAndSuppressesOldBinding() {
        val wifi = wifiConfig()
        val h = Harness()
        h.link.apiSsid = "Office-WiFi"
        h.shell.statusSsid = "Office-WiFi"
        var duringCalls = 0
        var timeoutCalls = 0
        val pending =
            object : CarWifiPendingEndpointPort {
                override fun tryContinueDuringWait(onClaimed: () -> Unit): Boolean {
                    duringCalls++
                    return false
                }

                override fun tryContinueBeforeTimeout(onClaimed: () -> Unit): Boolean {
                    timeoutCalls++
                    onClaimed()
                    return true
                }
            }
        var completed = false
        h.joiner.waitForKnownWifiThenContinue(wifi.ssid, "endpoint", pending, { false }) { completed = true }

        assertTrue("等待期间必须逐轮检查晚到端点", duringCalls > 0)
        assertEquals("超时前最后检查晚到端点一次", 1, timeoutCalls)
        assertFalse("晚到端点接替后不得再回调旧等待完成", completed)
        assertTrue(
            "晚到端点接替后旧等待不得继续绑定",
            h.trace.filterIsInstance<Trace.BindVerifiedAndRegister>().isEmpty(),
        )
        assertEquals(
            "晚到端点接替日志必须只输出一次",
            1,
            h.logs.count { it.contains("手动入网超时前收到完整 0x4301——直接接替本轮连接") },
        )
    }

    @Test
    fun j20_manualWaitUses45SecondsAndStrictlyGreaterThanFiveSecondBoundary() {
        val wifi = wifiConfig()
        val h = Harness()
        h.link.apiSsid = "Other-WiFi"
        h.link.existingWifi = true
        h.shell.statusSsid = "Other-WiFi"
        var bound = true
        h.joiner.waitForManualWifiThenContinue(wifi, { false }) { bound = it }

        assertEquals(45, h.sleepCount)
        assertFalse(bound)
        assertTrue(h.logs.any { it.contains("等待用户在系统 WLAN 中加入车机热点") })
        assertTrue(h.logs.last().contains("等待车机热点超时"))
    }

    @Test
    fun j20_staleCallerCannotBindOldNetworkAfterScopeInvalidation() {
        val wifi = wifiConfig()
        val h = Harness()
        h.link.apiSsid = wifi.ssid
        h.verifiedNetworkBySsid[wifi.ssid] = WifiId("net-old")
        h.link.existingWifi = true
        h.link.uniqueWifiNetwork = null
        h.link.currentNetwork = null
        h.state.boundCarSsid = null
        val handoff = h.handoff()
        h.dialogAnswers.addLast(true)
        handoff.beginOfStep = { handoff.handoffCancelled = true }

        handoff.dispatch(
            handoff.request(
                CarWifiHandoffSource.ACCESSORY,
                wifi.ssid,
                accessoryWifi = wifi,
            ),
        )

        assertTrue("旧 caller 失效后不得绑定旧端点网络", h.trace.filterIsInstance<Trace.BindVerifiedAndRegister>().isEmpty())
        assertEquals("旧 caller 失效必须显式回报 false", listOf(false), handoff.results)
    }

    @Test
    fun p3_productionAdapterWiringIsPinnedToInternetNetworkPolicy() {
        val bodies = carWifiJoinerAdapterBodies()
        val useSystem = bodies.getValue("useSystemWifi")
        assertEquals(
            "系统 Wi-Fi 首选项必须透传原名 Shizuku 就绪判定",
            1,
            Regex("""InternetNetworkPolicy\.useSystemWifi\(preference\.toInternetNetwork\(\), shizukuReady\)""")
                .findAll(useSystem)
                .count(),
        )
        listOf(
            "allowExistingSystemWifi" to "InternetNetworkPolicy.allowExistingSystemWifi(preference.toInternetNetwork())",
            "allowSystemFallback" to "InternetNetworkPolicy.allowSystemFallback(preference.toInternetNetwork())",
        ).forEach { (adapter, expected) ->
            assertEquals(
                "$adapter 必须精确映射到 InternetNetworkPolicy",
                1,
                Regex(Regex.escape(expected)).findAll(bodies.getValue(adapter)).count(),
            )
        }
        val phoneMode = bodies.getValue("phoneModeDecision")
        listOf(
            "phoneModeSystemWifiDecision(",
            "network = preference.toInternetNetwork(),",
            "activeTarget = activeTarget,",
            "activeIdentityUnknown = activeIdentityUnknown,",
            "saved = saved,",
            ").toCarWifiDecision()",
        ).forEach { fragment ->
            assertTrue("PHONE 策略适配器缺少 $fragment：$phoneMode", phoneMode.contains(fragment))
        }
        assertFalse(
            "不得绕过 InternetNetworkPolicy 直接按 PHONE/CAR 手写四路决策",
            useSystem.contains("preference == CarWifiInternetPreference.CAR"),
        )
    }

    private fun carWifiJoinerAdapterBodies(): Map<String, String> {
        val code = SourceTree.scan("SessionController.kt").code
        val property = code.indexOf("private val carWifiJoiner =")
        check(property >= 0) { "找不到 CarWifiJoiner 生产装配" }
        val constructor = code.indexOf("CarWifiJoiner<", property)
        check(constructor > property) { "找不到 CarWifiJoiner 构造调用" }
        var depth = 0
        var index = code.indexOf('(', constructor)
        check(index > constructor) { "找不到 CarWifiJoiner 参数表" }
        var end = -1
        while (index < code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end = index + 1
                        break
                    }
                }
            }
            index++
        }
        check(end > 0) { "CarWifiJoiner 生产装配没有闭合" }
        val wiring = code.substring(property, end)
        val adapters =
            listOf(
                "useSystemWifi",
                "allowExistingSystemWifi",
                "allowSystemFallback",
                "phoneModeDecision",
                "bindVerifiedAndRegister",
            )
        val starts = adapters.map { adapter -> adapter to wiring.indexOf("$adapter = {") }
        starts.forEach { (adapter, at) -> check(at >= 0) { "找不到 $adapter 生产适配器" } }
        check(starts.zipWithNext().all { (left, right) -> left.second < right.second }) {
            "生产适配器顺序不符合接线契约"
        }
        return starts
            .mapIndexed { i, (adapter, from) ->
                val to = starts.getOrNull(i + 1)?.second ?: wiring.length
                adapter to wiring.substring(from, to)
            }.toMap()
    }

    @Test
    fun p3_deadControllerBindVerifiedCarWifiIsRemovedAndJoinerHasSingleImplementation() {
        val controllerCode = SourceTree.scan("SessionController.kt").code
        val joinerCode = SourceTree.scan("net/CarWifiJoiner.kt").code
        assertFalse("SessionController 不得残留无调用点的 bindVerifiedCarWifi", controllerCode.contains("fun bindVerifiedCarWifi("))
        assertEquals(
            "joiner 必须只有一份 bindVerifiedCarWifi 实现",
            1,
            Regex("""fun bindVerifiedCarWifi\(""").findAll(joinerCode).count(),
        )
    }
}
