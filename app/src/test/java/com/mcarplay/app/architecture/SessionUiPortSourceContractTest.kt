package com.mcarplay.app.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionController 五通道 UI 写入的现状契约：`SessionUiPort` 的 `phase` / `hint` / `ssidChanged` /
 * `ask` / `dismiss`，由 `LegacySessionUiPort` 转发到 `phaseHint` / `uiHintSink` / `ssidSink` /
 * `dialogSink` / `dialogDismissSink`。断言「哪个函数、哪个分支、写什么内容、按什么顺序」，
 * 不用 allowlist 汇总数量，避免后续把 Wi-Fi 入网、端点解析或事件路由抽走时悄悄丢通道。
 *
 * 这只钉住源码接线与门控；界面在设备上的真实时序仍需台架/实车验证（源码快照不等于设备运行证据）。
 */
class SessionUiPortSourceContractTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val port: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/SessionUiPort.kt").readText()
    }

    /** 去注释/字符串后的指定函数体，避免日志文案或注释里的关键词污染断言。 */
    private fun code(name: String): String = KotlinSource.scan(producerBody(name)).code

    /** 从去注释/字符串后的代码里配平指定函数的参数表与函数体。 */
    private fun producerBody(name: String): String {
        val code = KotlinSource.scan(controller).code
        val marker = "fun $name("
        val start = if (name == "attributeAudioTeardown") code.lastIndexOf(marker) else code.indexOf(marker)
        check(start >= 0) { "找不到函数 $name" }
        var parens = 0
        var i = code.indexOf('(', start)
        while (i < code.length) {
            when (code[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            i++
        }
        check(parens == 0) { "$name 的参数表没有闭合" }
        val open = code.indexOf('{', i)
        check(open > i) { "找不到 $name 的函数体" }
        var depth = 0
        var j = open
        while (j < code.length) {
            when (code[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, j + 1)
                }
            }
            j++
        }
        error("$name 的函数体没有闭合")
    }

    private fun requireOrder(
        source: String,
        first: String,
        second: String,
        message: String,
    ) {
        val firstAt = source.indexOf(first)
        val secondAt = source.indexOf(second)
        assertTrue("$message（第一个未找到）", firstAt >= 0)
        assertTrue("$message（第二个未找到）", secondAt >= 0)
        assertTrue(message, firstAt < secondAt)
    }

    private fun requireContains(
        source: String,
        needle: String,
        message: String,
    ) {
        assertTrue(message, source.contains(needle))
    }

    @Test
    fun cancelWritesPhaseHintDirectly() {
        requireContains(code("cancelConnecting"), "ui.phase(t(R.string.sc_cancelled))", "取消连接必须写 phaseHint")
    }

    @Test
    fun senderLinkClosedWritesThePrecomputedTeardownHint() {
        val code = KotlinSource.scan(controller.substringAfter("fun onSenderLinkClosed(")).code
        requireContains(code, "val closedHint = t(R.string.sc_bt_link_closed_while_joining)", "拆链提示必须在退出前预存")
        requireContains(code, "ui.phase(closedHint)", "拆链路径必须写预存的 phaseHint")
    }

    @Test
    fun hotspotLostWritesPhaseHintAndUiHintInOrder() {
        val code = KotlinSource.scan(controller.substringAfter("connector.onNetworkLost = ")).code
        requireContains(code, "ui.phase(t(R.string.sc_hotspot_lost, ssid))", "热点丢失必须写 phaseHint")
        requireContains(code, "ui.hint { t(R.string.sc_hotspot_lost_hint, ssid) }", "热点丢失必须弹 uiHint")
        requireOrder(
            code,
            "ui.phase(t(R.string.sc_hotspot_lost",
            "ui.hint { t(R.string.sc_hotspot_lost_hint",
            "热点丢失先 phaseHint 后弹窗",
        )
    }

    @Test
    fun receiverTapWritesPhaseHintAndUiHintInOrder() {
        val code = KotlinSource.scan(controller.substringAfter("onAwaitingReceiverTap = { name ->")).code
        requireContains(code, "ui.phase(t(R.string.sc_tap_phone_on_receiver, name))", "等待车机端确认必须写 phaseHint")
        requireContains(code, "ui.hint { t(R.string.sc_tap_phone_on_receiver_hint, name) }", "等待车机端确认必须弹 uiHint")
        requireOrder(
            code,
            "ui.phase(t(R.string.sc_tap_phone_on_receiver",
            "ui.hint { t(R.string.sc_tap_phone_on_receiver_hint",
            "先 phaseHint 后弹窗",
        )
    }

    @Test
    fun disconnectClearsThePhaseHint() {
        val code = KotlinSource.scan(controller.substringAfter("fun disconnect(")).code
        requireContains(code, "ui.phase(null)", "disconnect 必须清 phaseHint")
    }

    @Test
    fun wifiCredentialsWritesSsidBeforePhaseHint() {
        val code = code("onWifiCredentials")
        requireContains(code, "ui.ssidChanged { wifi.ssid }", "0x5703 凭据必须回填 SSID")
        requireContains(code, "ui.phase(t(R.string.sc_got_credentials))", "0x5703 凭据必须写 phaseHint")
        requireOrder(code, "ui.ssidChanged { wifi.ssid }", "ui.phase(t(R.string.sc_got_credentials))", "先回填 SSID 再更新阶段提示")
    }

    @Test
    fun autoJoinBindFailureWritesTheSilentPathHints() {
        val autoJoin = code("autoJoinCarWifi")
        requireContains(autoJoin, "ui.phase(t(R.string.sc_bind_failed))", "静默绑定失败必须写 phaseHint")
        requireContains(code("joinCarWifiSilentlyLocked"), "ui.phase(t(R.string.sc_silent_join, wifi.ssid))", "静默入网发起时必须写 phaseHint")
    }

    @Test
    fun waitForCarSsidThrottlesTheSwitchingHint() {
        val code = code("waitForCarSsid")
        requireContains(code, "ui.phase(t(R.string.sc_switching, ssid))", "切网等待必须写 phaseHint")
        requireContains(code, "now - lastLogAt >= 3_000L", "切网提示必须按 3 秒节流")
    }

    @Test
    fun specifierJoinPinsTheBoundAndNoDataHints() {
        val code = code("joinCarWifiSpecifier")
        requireContains(code, "ui.phase(t(R.string.sc_bound_existing))", "specifier 现有连接近路必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_joining_no_data, wifi.ssid))", "specifier 发起前必须写 phaseHint")
        requireContains(code, "connector.joinCarPlayNetwork(", "specifier 必须真的发起入网请求")
        requireOrder(code, "ui.phase(t(R.string.sc_joining_no_data", "connector.joinCarPlayNetwork(", "先更新提示再发起网络请求")
    }

    @Test
    fun specifierResultWritesTheJoinedHintOnBothRoutes() {
        val code = KotlinSource.scan(producerBody("joinCarWifiSpecifier")).code
        val joinedCount = Regex("""ui\.phase\(t\(R\.string\.sc_joined\)\)""").findAll(code).count()
        assertTrue("specifier 成功与系统已连近路必须各写一次 sc_joined（当前共两处）", joinedCount == 2)
        requireContains(code, "if (bindExistingCarWifi(connector, wifi.ssid) != null)", "specifier 失败近路必须走统一绑定")
    }

    @Test
    fun wifiEnableWaitPinsTheDialogAndHints() {
        val code = code("waitForWifiEnabled")
        requireContains(code, "t(R.string.sc_wifi_off_title)", "WiFi 关闭必须弹确认框")
        requireContains(code, "t(R.string.sc_wifi_off_body)", "WiFi 关闭弹窗必须带说明文案")
        requireOrder(code, "openWlanSettings()", "ui.hint { t(R.string.sc_turn_on_wifi) }", "跳转 WLAN 设置后才弹打开提示")
        requireContains(code, "ui.phase(t(R.string.sc_waiting_wifi))", "开始等待 WiFi 打开必须写 phaseHint")
    }

    @Test
    fun specifierFailurePromptPinsTheDialogContent() {
        val code = code("promptSpecifierFailed")
        requireContains(code, "t(R.string.sc_join_failed_title)", "specifier 失败弹窗标题")
        requireContains(code, "t(R.string.sc_join_failed_body, wifi.ssid)", "specifier 失败弹窗正文")
        requireContains(code, "t(R.string.sc_retry)", "specifier 失败弹窗必须提供重试按钮")
        requireContains(code, "t(R.string.sc_open_wifi_settings)", "specifier 失败弹窗必须提供跳 WLAN 按钮")
    }

    @Test
    fun manualJoinPromptPinsTheCurrentNetworkDialogAndTimeout() {
        val code = code("manualJoinCarWifi")
        requireContains(code, "t(R.string.sc_use_current_title)", "手动兜底必须先确认当前网络")
        requireContains(code, "t(R.string.sc_use_current_body, wifi.ssid)", "手动兜底确认弹窗正文")
        requireContains(code, "ui.phase(t(R.string.sc_bound_current))", "用户确认当前网络绑定成功必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_connect_in_settings, wifi.ssid))", "复制密码后必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_join_aborted, wifi.ssid))", "手动兜底超时必须写 phaseHint")
        requireOrder(code, "copyHotspotPsk(wifi.passphrase)", "ui.phase(t(R.string.sc_connect_in_settings", "先复制密码再提示去设置")
    }

    @Test
    fun legacyJoinPromptPinsTheDialogAndTimeoutHints() {
        val code = code("legacyJoinCarWifi")
        requireContains(code, "t(R.string.sc_join_title)", "旧安全类型入网必须弹确认框")
        requireContains(code, "t(R.string.sc_join_body, wifi.ssid, wifi.ssid)", "旧安全类型入网弹窗正文")
        requireContains(code, "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))", "无 Shizuku 时必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_auto_joining, wifi.ssid))", "用户确认自动加入必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_auto_join_failed, wifi.ssid))", "Shizuku 切网失败必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_not_staying, wifi.ssid))", "切网超时必须写 phaseHint")
        requireContains(code, "ui.hint { t(R.string.sc_not_staying_hint, wifi.ssid) }", "切网超时必须弹 uiHint")
    }

    @Test
    fun retryPromptsReuseTheSameDialogCopy() {
        val legacy = code("legacyJoinCarWifi")
        val prompt = code("promptSpecifierFailed")
        requireContains(legacy, "t(R.string.sc_join_failed_retry)", "legacy 弹窗无人应答必须复用重试提示")
        requireContains(prompt, "t(R.string.sc_join_failed_retry)", "specifier 失败弹窗无人应答必须复用重试提示")
    }

    @Test
    fun pskCopyWritesTheUiHintAfterClipboard() {
        val code = KotlinSource.scan(controller.substringAfter("private fun copyHotspotPsk(")).code
        requireContains(code, "ui.hint { t(R.string.sc_psk_copied, passphrase.length) }", "密码复制成功后必须弹 uiHint")
        requireOrder(code, "cm.setPrimaryClip(", "ui.hint { t(R.string.sc_psk_copied", "先写剪贴板再弹提示")
    }

    @Test
    fun audioTeardownWritesTheAttributionHint() {
        val code = code("attributeAudioTeardown")
        requireContains(code, "ui.hint {", "音频拆链归因必须弹 uiHint")
        requireContains(code, "t(R.string.sc_audio_all_incompatible)", "音频拆链必须保留全部档位不兼容提示")
        requireContains(code, "t(R.string.sc_audio_profile_rejected)", "音频拆链必须保留单档位拒绝提示")
    }

    @Test
    fun mdnsResolveWritesTheResolveHint() {
        val code = KotlinSource.scan(controller.substringAfter("private fun resolveEndpointHost(")).code
        requireContains(code, "ui.phase(t(R.string.sc_mdns))", "端点需要 mDNS 解析时必须写 phaseHint")
    }

    @Test
    fun handoffEntrancePinsTheWaitingAndResolvingHints() {
        val code = KotlinSource.scan(controller.substringAfter("private fun onCarPlayEndpoint(")).code
        requireContains(code, "ui.phase(t(R.string.sc_waiting_4301))", "端点无 AP 信息必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_resolving_hotspot))", "端点携带 AP 信息必须写 phaseHint")
        requireContains(code, "if (endpoint.ssid != INVITED_WIFI_LABEL) ui.ssidChanged { endpoint.ssid }", "端点 SSID 必须排除占位标签后回填")
        requireContains(code, "ui.phase(t(R.string.sc_not_on_hotspot_endpoint, endpoint.ssid))", "交接网络不匹配必须写 phaseHint")
        requireContains(code, "ui.phase(t(R.string.sc_pairing))", "交接配对阶段必须写 phaseHint")
        requireOrder(code, "ui.phase(t(R.string.sc_waiting_4301))", "watchForPendingStartSession(endpoint, source)", "端点无 AP 信息先写提示再进入等待")
        requireOrder(
            code,
            "ui.phase(t(R.string.sc_resolving_hotspot))",
            "if (endpoint.ssid != INVITED_WIFI_LABEL) ui.ssidChanged { endpoint.ssid }",
            "先更新解析提示再回填 SSID",
        )
    }

    @Test
    fun sessionSetupWritesThePlainAndPairedHints() {
        val code = KotlinSource.scan(controller.substringAfter("ui.phase(t(R.string.sc_pairing))")).code
        requireContains(code, "ui.phase(t(R.string.sc_plain_session))", "明文会话必须写 phaseHint")
        requireContains(
            code,
            "ui.phase(if (pairedKeys != null) t(R.string.sc_paired_setup) else t(R.string.sc_plain_session))",
            "加密会话必须按配对密钥分档写 phaseHint",
        )
    }

    @Test
    fun recordAndSetupRejectionWriteBothChannels() {
        val code = KotlinSource.scan(controller.substringAfter("private fun runSessionLoop(")).code
        requireContains(code, "ui.phase(t(R.string.sc_record_unconfirmed))", "RECORD 未确认必须写 phaseHint")
        requireContains(code, "ui.hint { t(R.string.sc_record_unconfirmed) }", "RECORD 未确认必须弹 uiHint")
        requireContains(code, "ui.phase(t(R.string.sc_setup_rejected))", "SETUP 被拒必须写 phaseHint")
        requireContains(code, "ui.hint { t(R.string.sc_setup_rejected_hint) }", "SETUP 被拒必须弹 uiHint")
    }

    @Test
    fun waitingJoinHintsArePinnedWithThrottleAndTimeout() {
        val known = KotlinSource.scan(controller.substringAfter("private fun waitForKnownWifiThenContinue(")).code
        val manual = code("waitForManualWifiThenContinue")
        requireContains(known, "ui.phase(t(R.string.sc_waiting_join, target))", "已知 SSID 等待必须写 phaseHint")
        requireContains(known, "now - lastHintAt >= 5_000L", "已知 SSID 等待提示必须按 5 秒节流")
        requireContains(manual, "ui.phase(t(R.string.sc_waiting_join, wifi.ssid))", " specifier 手动等待必须写 phaseHint")
        requireContains(manual, "android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000", " specifier 手动等待提示必须按 5 秒节流")
        requireContains(manual, "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))", " specifier 手动等待超时必须写 phaseHint")
        requireContains(manual, "ui.hint { t(R.string.sc_not_joined_hint, wifi.ssid) }", " specifier 手动等待超时必须弹 uiHint")
    }

    @Test
    fun dialogChannelPinsTheFullRequestChain() {
        val code = KotlinSource.scan(controller.substringAfter("private fun askWifiJoinChoice(")).code
        requireContains(controller, "dialogSink = { dialogSink }", "适配器必须在每次弹窗时动态读取 dialogSink")
        requireContains(port, "val sink = dialogSink() ?: return false", "端口必须先读取 dialogSink 再决定是否展示")
        requireContains(port, "sink(spec, onChoice)", "端口必须把 DialogSpec 与回调原样交给 dialogSink")
        requireContains(code, "ui.ask(request) { result ->", "弹窗必须把回调交给端口 ask")
        requireContains(code, "wifiPromptBroker.finish(claim, result, cache = true)", "用户应答必须回写弹窗代理")
        requireContains(code, "wifiPromptBroker.finish(claim, null, cache = false)", "无宿主时必须取消询问且不缓存答案")
        requireContains(code, "val requestId = dialogRequestIds.incrementAndGet()", "每次弹窗必须分配自增 requestId")
        requireContains(code, "val request = spec.copy(requestId = requestId)", "DialogSpec 必须携带 requestId")
        requireContains(code, "android.os.Handler(android.os.Looper.getMainLooper()).post {", "弹窗必须在主线程展示")
        requireContains(code, "if (!answered.get()) runCatching { ui.dismiss(requestId) }", "提前退出必须按 requestId 撤框")
        requireOrder(
            code,
            "val requestId = dialogRequestIds.incrementAndGet()",
            "ui.dismiss(requestId)",
            "先分配 requestId 再撤框",
        )
    }

    @Test
    fun phaseHintIsDeclaredOnTheControllerOnly() {
        assertTrue("phaseHint 声明必须在 SessionController.kt", controller.contains("@Volatile\n    var phaseHint: String? = null"))
        val otherMainKotlinFiles = SourceTree.mainKotlinFiles.entries.filter { it.key != "SessionController.kt" }
        assertTrue("契约必须扫描到其它主源码文件", otherMainKotlinFiles.isNotEmpty())
        val duplicatedDeclarations = otherMainKotlinFiles.filter { it.value.readText().contains("var phaseHint") }
        assertTrue("phaseHint 声明只能出现在 SessionController.kt", duplicatedDeclarations.isEmpty())
    }

    @Test
    fun silentJoinDoesNotOpenTheJoinDialog() {
        val code = code("joinCarWifiSilentlyLocked")
        assertFalse("静默入网路径不应弹确认框（单场景负断言）", code.contains("askWifiJoinChoice"))
    }
}
