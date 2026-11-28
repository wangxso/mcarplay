package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五通道 UI 写入经 SessionUiPort 接线后的完整清单：
 * 生产点保持 45 phase + 10 hint + 2 SSID + 1 ask + 1 dismiss（零减少），
 * 另计 SessionController 装配 CarWifiJoiner 的窄 UI 桥接边（1 phase + 1 hint，惰性直传）。
 */
private object SourceUiPortInventory {
    const val CONTROLLER = "SessionController.kt"
    const val JOINER = "net/CarWifiJoiner.kt"

    data class UiWriteSite(
        val path: String,
        val function: String,
        val expression: String,
    )

    val PHASE_WRITES =
        listOf(
            UiWriteSite(CONTROLLER, "cancelConnecting", "ui.phase(t(R.string.sc_cancelled))"),
            UiWriteSite(CONTROLLER, "onSenderLinkClosed", "ui.phase(closedHint)"),
            UiWriteSite(CONTROLLER, "ensureSessionObjects", "ui.phase(t(R.string.sc_hotspot_lost, ssid))"),
            UiWriteSite(CONTROLLER, "ensureSessionObjects", "ui.phase(t(R.string.sc_tap_phone_on_receiver, name))"),
            UiWriteSite(CONTROLLER, "retryAfterWifiHandoff", "ui.phase(t(R.string.sc_bt_ready))"),
            UiWriteSite(CONTROLLER, "startConnection", "ui.phase(t(R.string.sc_bt_ready))"),
            UiWriteSite(CONTROLLER, "disconnect", "ui.phase(null)"),
            UiWriteSite(CONTROLLER, "onWifiCredentials", "ui.phase(t(R.string.sc_got_credentials))"),
            UiWriteSite(JOINER, "autoJoinCarWifi", "ui.phase(t(R.string.sc_bind_failed))"),
            UiWriteSite(JOINER, "joinCarWifiSilentlyLocked", "ui.phase(t(R.string.sc_silent_join, wifi.ssid))"),
            UiWriteSite(JOINER, "waitForCarSsid", "ui.phase(t(R.string.sc_switching, ssid))"),
            UiWriteSite(JOINER, "joinCarWifiSpecifier", "ui.phase(t(R.string.sc_bound_existing))"),
            UiWriteSite(JOINER, "joinCarWifiSpecifier", "ui.phase(t(R.string.sc_joining_no_data, wifi.ssid))"),
            UiWriteSite(JOINER, "joinCarWifiSpecifier", "ui.phase(t(R.string.sc_joined))"),
            UiWriteSite(JOINER, "joinCarWifiSpecifier", "ui.phase(t(R.string.sc_joined))"),
            UiWriteSite(JOINER, "waitForWifiEnabled", "ui.phase(t(R.string.sc_cancelled_wifi_needed))"),
            UiWriteSite(JOINER, "waitForWifiEnabled", "ui.phase(t(R.string.sc_wifi_off_needed))"),
            UiWriteSite(JOINER, "waitForWifiEnabled", "ui.phase(t(R.string.sc_waiting_wifi))"),
            UiWriteSite(JOINER, "waitForWifiEnabled", "ui.phase(t(R.string.sc_cancelled))"),
            UiWriteSite(JOINER, "waitForWifiEnabled", "ui.phase(t(R.string.sc_wifi_off))"),
            UiWriteSite(JOINER, "promptSpecifierFailed", "ui.phase(t(R.string.sc_phone_join_failed_retry))"),
            UiWriteSite(
                JOINER,
                "promptSpecifierFailed",
                "ui.phase(t(if (phoneMode) R.string.sc_phone_join_failed_retry else R.string.sc_join_failed_manual))",
            ),
            UiWriteSite(JOINER, "promptSpecifierFailed", "ui.phase(t(R.string.sc_join_failed_retry))"),
            UiWriteSite(JOINER, "manualJoinCarWifi", "ui.phase(t(R.string.sc_bound_current))"),
            UiWriteSite(JOINER, "manualJoinCarWifi", "ui.phase(t(R.string.sc_connect_in_settings, wifi.ssid))"),
            UiWriteSite(JOINER, "manualJoinCarWifi", "ui.phase(t(R.string.sc_join_aborted, wifi.ssid))"),
            UiWriteSite(JOINER, "legacyJoinCarWifi", "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))"),
            UiWriteSite(JOINER, "legacyJoinCarWifi", "ui.phase(t(R.string.sc_join_failed_retry))"),
            UiWriteSite(JOINER, "legacyJoinCarWifi", "ui.phase(t(R.string.sc_auto_joining, wifi.ssid))"),
            UiWriteSite(JOINER, "legacyJoinCarWifi", "ui.phase(t(R.string.sc_auto_join_failed, wifi.ssid))"),
            UiWriteSite(JOINER, "legacyJoinCarWifi", "ui.phase(t(R.string.sc_joining_may_revert, wifi.ssid))"),
            UiWriteSite(JOINER, "legacyJoinCarWifi", "ui.phase(t(R.string.sc_not_staying, wifi.ssid))"),
            UiWriteSite(CONTROLLER, "bindCarWifiAndRegister", "ui.phase(t(R.string.sc_joined))"),
            UiWriteSite(CONTROLLER, "bindCarWifiAndRegister", "ui.phase(t(R.string.sc_bind_channel_failed, ssid))"),
            UiWriteSite(CONTROLLER, "resolveEndpointHost", "ui.phase(t(R.string.sc_mdns))"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.phase(t(R.string.sc_waiting_4301))"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.phase(t(R.string.sc_resolving_hotspot))"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.phase(t(R.string.sc_not_on_hotspot_endpoint, endpoint.ssid))"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.phase(t(R.string.sc_pairing))"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.phase(t(R.string.sc_plain_session))"),
            UiWriteSite(
                CONTROLLER,
                "onCarPlayEndpoint",
                "ui.phase(if (pairedKeys != null) t(R.string.sc_paired_setup) else t(R.string.sc_plain_session))",
            ),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.phase(t(R.string.sc_record_unconfirmed))"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.phase(t(R.string.sc_setup_rejected))"),
            UiWriteSite(JOINER, "waitForKnownWifiThenContinue", "ui.phase(t(R.string.sc_waiting_join, target))"),
            UiWriteSite(JOINER, "waitForManualWifiThenContinue", "ui.phase(t(R.string.sc_waiting_join, wifi.ssid))"),
            UiWriteSite(JOINER, "waitForManualWifiThenContinue", "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))"),
        )

    val HINT_WRITES =
        listOf(
            UiWriteSite(CONTROLLER, "ensureSessionObjects", "ui.hint { t(R.string.sc_hotspot_lost_hint, ssid) }"),
            UiWriteSite(CONTROLLER, "ensureSessionObjects", "ui.hint { t(R.string.sc_tap_phone_on_receiver_hint, name) }"),
            UiWriteSite(JOINER, "waitForWifiEnabled", "ui.hint { t(R.string.sc_turn_on_wifi) }"),
            UiWriteSite(JOINER, "legacyJoinCarWifi", "ui.hint { t(R.string.sc_not_staying_hint, wifi.ssid) }"),
            UiWriteSite(CONTROLLER, "copyHotspotPsk", "ui.hint { t(R.string.sc_psk_copied, passphrase.length) }"),
            UiWriteSite(
                CONTROLLER,
                "audioTeardownWiring",
                "ui.hint { if (allDisabled) { t(R.string.sc_audio_all_incompatible) } " +
                    "else { t(R.string.sc_audio_profile_rejected) } }",
            ),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.hint { t(R.string.sc_record_unconfirmed) }"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "ui.hint { t(R.string.sc_setup_rejected_hint) }"),
            UiWriteSite(JOINER, "waitForManualWifiThenContinue", "ui.hint { t(R.string.sc_not_joined_hint, wifi.ssid) }"),
        )

    val SSID_WRITES =
        listOf(
            UiWriteSite(CONTROLLER, "onWifiCredentials", "ui.ssidChanged { wifi.ssid }"),
            UiWriteSite(CONTROLLER, "onCarPlayEndpoint", "if (endpoint.ssid != INVITED_WIFI_LABEL) ui.ssidChanged { endpoint.ssid }"),
        )

    val BRIDGE_PHASE_WRITES =
        listOf(
            UiWriteSite(CONTROLLER, "carWifiJoinerWiring", "override fun phase(hint: String?) { ui.phase(hint) }"),
        )

    val BRIDGE_HINT_WRITES =
        listOf(
            UiWriteSite(CONTROLLER, "carWifiJoinerWiring", "override fun hint(message: () -> String) { ui.hint(message) }"),
        )
}

class SessionUiPortInventoryTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue(SourceUiPortInventory.CONTROLLER).readText()
    }

    private val strippedController: String by lazy { KotlinSource.scan(controller).code }

    private val joiner: String by lazy {
        SourceTree.mainKotlinFiles.getValue(SourceUiPortInventory.JOINER).readText()
    }

    private val strippedJoiner: String by lazy { KotlinSource.scan(joiner).code }

    private fun strippedSource(path: String): String {
        val file = checkNotNull(SourceTree.mainKotlinFiles[path]) { "找不到源码文件 $path" }
        return KotlinSource.scan(file.readText()).code
    }

    /** 音频拆链归因接线已迁入 [AudioTeardown] 构造块；单独提取以保留完整源码契约。 */
    private fun audioTeardownWiring(): String =
        extractConstructorWiring(
            source = strippedController,
            propertyMarker = "private val audioTeardown =",
            constructorMarker = "AudioTeardown(",
            label = "AudioTeardown",
        )

    /** CarWifiJoiner 的窄 UI/文案/弹窗适配器构造块（Phase 2c-2）。 */
    private fun carWifiJoinerWiring(): String =
        extractConstructorWiring(
            source = strippedController,
            propertyMarker = "private val carWifiJoiner =",
            constructorMarker = "CarWifiJoiner<",
            label = "CarWifiJoiner",
        )

    private fun extractConstructorWiring(
        source: String,
        propertyMarker: String,
        constructorMarker: String,
        label: String,
    ): String {
        val start = source.indexOf(propertyMarker)
        check(start >= 0) { "找不到 $label 构造块" }
        val constructorAt = source.indexOf(constructorMarker, start)
        check(constructorAt >= 0) { "找不到 $label 构造调用" }
        var parens = 0
        var index = source.indexOf('(', constructorAt)
        check(index >= 0) { "找不到 $label 构造参数左括号" }
        while (index < source.length) {
            when (source[index]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        error("$label 构造块没有闭合")
    }

    private fun sourceFor(
        path: String,
        function: String,
    ): String =
        when {
            path == SourceUiPortInventory.CONTROLLER && function == "audioTeardownWiring" -> audioTeardownWiring()
            path == SourceUiPortInventory.CONTROLLER && function == "carWifiJoinerWiring" -> carWifiJoinerWiring()
            else -> body(path, function)
        }

    /** 去注释/字符串后的指定文件与函数体（缺失或未闭合立即失败）。 */
    private fun body(
        path: String,
        name: String,
    ): String {
        val stripped = strippedSource(path)
        val marker = "fun $name("
        val start = if (name == "attributeAudioTeardown") stripped.lastIndexOf(marker) else stripped.indexOf(marker)
        check(start >= 0) { "在 $path 中找不到函数 $name" }
        var parens = 0
        var index = stripped.indexOf('(', start)
        while (index < stripped.length) {
            when (stripped[index]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            index++
        }
        check(parens == 0) { "$path#$name 的参数表没有闭合" }
        val open = stripped.indexOf('{', index)
        check(open > index) { "找不到 $path#$name 的函数体" }
        var depth = 0
        var cursor = open
        while (cursor < stripped.length) {
            when (stripped[cursor]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(open, cursor + 1)
                }
            }
            cursor++
        }
        error("$path#$name 的函数体没有闭合")
    }

    private fun normalizeWhitespace(value: String): String = value.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun requireIn(
        path: String,
        function: String,
        expression: String,
    ) {
        val code = sourceFor(path, function)
        if (!normalizeWhitespace(code).contains(normalizeWhitespace(expression))) {
            throw AssertionError("$path#$function 必须保留 $expression\n函数体=\n$code")
        }
    }

    private fun requireOrder(
        path: String,
        function: String,
        first: String,
        second: String,
    ) {
        val code = normalizeWhitespace(body(path, function))
        val firstNorm = normalizeWhitespace(first)
        val secondNorm = normalizeWhitespace(second)
        val firstAt = code.indexOf(firstNorm)
        val secondAt = code.indexOf(secondNorm)
        assertTrue("$path#$function 缺少 $first", firstAt >= 0)
        assertTrue("$path#$function 缺少 $second", secondAt >= 0)
        assertTrue("$path#$function 中 $first 必须先于 $second", firstAt < secondAt)
    }

    private fun requireExactOccurrences(
        source: String,
        expression: String,
        expectedCount: Int,
        label: String,
    ) {
        val normalizedSource = normalizeWhitespace(source)
        val normalizedExpression = normalizeWhitespace(expression)
        val actualCount = Regex(Regex.escape(normalizedExpression)).findAll(normalizedSource).count()
        val first = normalizedSource.indexOf(normalizedExpression)
        assertTrue("$label 缺少 $expression", first >= 0)
        assertEquals(
            "$label 的 $expression 必须出现 $expectedCount 次",
            expectedCount,
            actualCount,
        )
    }

    private fun requireAbsent(
        source: String,
        expression: String,
        label: String,
    ) {
        val normalizedSource = normalizeWhitespace(source)
        val normalizedExpression = normalizeWhitespace(expression)
        assertEquals(
            "$label 不得出现绕过端口的 $expression",
            0,
            Regex(Regex.escape(normalizedExpression)).findAll(normalizedSource).count(),
        )
    }

    @Test
    fun inventoryCoversExactlyTheFiveChannelCallSites() {
        SourceUiPortInventory.PHASE_WRITES.forEach { site ->
            requireIn(site.path, site.function, site.expression)
        }
        SourceUiPortInventory.HINT_WRITES.forEach { site ->
            requireIn(site.path, site.function, site.expression)
        }
        SourceUiPortInventory.SSID_WRITES.forEach { site ->
            requireIn(site.path, site.function, site.expression)
        }
        SourceUiPortInventory.BRIDGE_PHASE_WRITES.forEach { site ->
            requireIn(site.path, site.function, site.expression)
        }
        SourceUiPortInventory.BRIDGE_HINT_WRITES.forEach { site ->
            requireIn(site.path, site.function, site.expression)
        }
        requireIn(SourceUiPortInventory.CONTROLLER, "askWifiJoinChoice", "ui.ask(request) { result ->")
        requireIn(
            SourceUiPortInventory.CONTROLLER,
            "askWifiJoinChoice",
            "if (!answered.get()) runCatching { ui.dismiss(requestId) }",
        )
        requireOrder(
            SourceUiPortInventory.CONTROLLER,
            "onWifiCredentials",
            "ui.ssidChanged { wifi.ssid }",
            "ui.phase(t(R.string.sc_got_credentials))",
        )
        requireOrder(
            SourceUiPortInventory.CONTROLLER,
            "ensureSessionObjects",
            "ui.phase(t(R.string.sc_hotspot_lost, ssid))",
            "ui.hint { t(R.string.sc_hotspot_lost_hint, ssid) }",
        )
        requireOrder(
            SourceUiPortInventory.CONTROLLER,
            "ensureSessionObjects",
            "ui.phase(t(R.string.sc_tap_phone_on_receiver, name))",
            "ui.hint { t(R.string.sc_tap_phone_on_receiver_hint, name) }",
        )
        requireOrder(
            SourceUiPortInventory.JOINER,
            "legacyJoinCarWifi",
            "ui.phase(t(R.string.sc_not_staying, wifi.ssid))",
            "ui.hint { t(R.string.sc_not_staying_hint, wifi.ssid) }",
        )
        requireOrder(
            SourceUiPortInventory.CONTROLLER,
            "onCarPlayEndpoint",
            "ui.phase(t(R.string.sc_record_unconfirmed))",
            "ui.hint { t(R.string.sc_record_unconfirmed) }",
        )
        requireOrder(
            SourceUiPortInventory.CONTROLLER,
            "onCarPlayEndpoint",
            "ui.phase(t(R.string.sc_setup_rejected))",
            "ui.hint { t(R.string.sc_setup_rejected_hint) }",
        )
        requireOrder(
            SourceUiPortInventory.JOINER,
            "waitForManualWifiThenContinue",
            "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))",
            "ui.hint { t(R.string.sc_not_joined_hint, wifi.ssid) }",
        )
    }

    @Test
    fun channelWritesDoNotEscapeTheInventory() {
        SourceUiPortInventory.PHASE_WRITES.forEach { site ->
            val expectedCount =
                SourceUiPortInventory.PHASE_WRITES.count {
                    it.path == site.path && it.function == site.function && it.expression == site.expression
                }
            requireExactOccurrences(sourceFor(site.path, site.function), site.expression, expectedCount, "${site.path}#${site.function}")
        }
        SourceUiPortInventory.HINT_WRITES.forEach { site ->
            val expectedCount =
                SourceUiPortInventory.HINT_WRITES.count {
                    it.path == site.path && it.function == site.function && it.expression == site.expression
                }
            requireExactOccurrences(sourceFor(site.path, site.function), site.expression, expectedCount, "${site.path}#${site.function}")
        }
        SourceUiPortInventory.SSID_WRITES.forEach { site ->
            val expectedCount =
                SourceUiPortInventory.SSID_WRITES.count {
                    it.path == site.path && it.function == site.function && it.expression == site.expression
                }
            requireExactOccurrences(sourceFor(site.path, site.function), site.expression, expectedCount, "${site.path}#${site.function}")
        }
        SourceUiPortInventory.BRIDGE_PHASE_WRITES.forEach { site ->
            requireExactOccurrences(sourceFor(site.path, site.function), site.expression, 1, "${site.path}#${site.function}")
        }
        SourceUiPortInventory.BRIDGE_HINT_WRITES.forEach { site ->
            requireExactOccurrences(sourceFor(site.path, site.function), site.expression, 1, "${site.path}#${site.function}")
        }

        val joinCarWifiSpecifierJoinedCount =
            SourceUiPortInventory.PHASE_WRITES
                .filter {
                    it.path == SourceUiPortInventory.JOINER &&
                        it.function == "joinCarWifiSpecifier" &&
                        it.expression == "ui.phase(t(R.string.sc_joined))"
                }.count()
        assertEquals(2, joinCarWifiSpecifierJoinedCount)

        requireExactOccurrences(
            strippedController,
            "if (!answered.get()) runCatching { ui.dismiss(requestId) }",
            1,
            "SessionController",
        )

        val normalizedController = normalizeWhitespace(strippedController)
        val normalizedJoiner = normalizeWhitespace(strippedJoiner)
        val normalizedBridge = normalizeWhitespace(carWifiJoinerWiring())

        // 桥接块只允许各有一条 phase / hint 转发，且 hint 必须惰性直传 message 引用，不能提前求值 message()
        assertEquals(1, Regex("""\bui\.phase\(""").findAll(normalizedBridge).count())
        assertEquals(1, Regex("""\bui\.hint\(""").findAll(normalizedBridge).count())
        assertEquals(0, Regex("""\bui\.hint\s*\{""").findAll(normalizedBridge).count())
        assertFalse("桥接 hint 不得提前求值 message()", normalizedBridge.contains("message()"))

        val controllerPhaseTotal = Regex("""\bui\.phase\(""").findAll(normalizedController).count()
        val controllerBridgePhase = SourceUiPortInventory.BRIDGE_PHASE_WRITES.size
        val controllerProductionPhase = controllerPhaseTotal - controllerBridgePhase
        val joinerProductionPhase = Regex("""\bui\.phase\(""").findAll(normalizedJoiner).count()

        val controllerProductionHint = Regex("""\bui\.hint\s*\{""").findAll(normalizedController).count()
        val controllerBridgeHint = Regex("""\bui\.hint\(""").findAll(normalizedController).count()
        val joinerProductionHint = Regex("""\bui\.hint\s*\{""").findAll(normalizedJoiner).count()
        val joinerParenHint = Regex("""\bui\.hint\(""").findAll(normalizedJoiner).count()

        val controllerSsidCount = Regex("""\bui\.ssidChanged\b""").findAll(normalizedController).count()
        val joinerSsidCount = Regex("""\bui\.ssidChanged\b""").findAll(normalizedJoiner).count()
        val controllerAskCount = Regex("""\bui\.ask\(""").findAll(normalizedController).count()
        val joinerAskCount = Regex("""\bui\.ask\(""").findAll(normalizedJoiner).count()
        val controllerDismissCount = Regex("""\bui\.dismiss\(""").findAll(normalizedController).count()
        val joinerDismissCount = Regex("""\bui\.dismiss\(""").findAll(normalizedJoiner).count()

        assertEquals(19, controllerProductionPhase)
        assertEquals(27, joinerProductionPhase)
        assertEquals(1, controllerBridgePhase)
        assertEquals(46, SourceUiPortInventory.PHASE_WRITES.size)
        assertEquals(46, controllerProductionPhase + joinerProductionPhase)

        assertEquals(6, controllerProductionHint)
        assertEquals(3, joinerProductionHint)
        assertEquals(1, controllerBridgeHint)
        assertEquals(0, joinerParenHint)
        assertEquals(9, SourceUiPortInventory.HINT_WRITES.size)
        assertEquals(9, controllerProductionHint + joinerProductionHint)

        assertEquals(2, controllerSsidCount)
        assertEquals(0, joinerSsidCount)
        assertEquals(1, controllerAskCount)
        assertEquals(0, joinerAskCount)
        assertEquals(1, controllerDismissCount)
        assertEquals(0, joinerDismissCount)

        // 全树扫描：其它主源码文件不得出现任何未登记的 ui.<channel> 调用
        val registeredFiles = setOf(SourceUiPortInventory.CONTROLLER, SourceUiPortInventory.JOINER)
        SourceTree.mainKotlinFiles.keys
            .filterNot { it in registeredFiles }
            .forEach { path ->
                val code = normalizeWhitespace(strippedSource(path))
                assertEquals("$path 不得包含未登记的 ui.phase", 0, Regex("""\bui\.phase\b""").findAll(code).count())
                assertEquals("$path 不得包含未登记的 ui.hint", 0, Regex("""\bui\.hint\b""").findAll(code).count())
                assertEquals("$path 不得包含未登记的 ui.ssidChanged", 0, Regex("""\bui\.ssidChanged\b""").findAll(code).count())
                assertEquals("$path 不得包含未登记的 ui.ask", 0, Regex("""\bui\.ask\b""").findAll(code).count())
                assertEquals("$path 不得包含未登记的 ui.dismiss", 0, Regex("""\bui\.dismiss\b""").findAll(code).count())
            }
    }

    @Test
    fun channelWritesDoNotBypassThePort() {
        val phaseAssignments = Regex("""\bphaseHint\s*=""").findAll(normalizeWhitespace(strippedController)).count()
        assertEquals("phaseHint 只允许由 LegacySessionUiPort 的 setPhase 写入", 1, phaseAssignments)
        requireExactOccurrences(strippedController, "setPhase = { phaseHint = it }", 1, "SessionController")
        assertEquals(
            "CarWifiJoiner 不得直接触碰 phaseHint",
            0,
            Regex("""\bphaseHint\b""").findAll(normalizeWhitespace(strippedJoiner)).count(),
        )
        listOf(
            "SessionController" to strippedController,
            "CarWifiJoiner" to strippedJoiner,
        ).forEach { (label, source) ->
            requireAbsent(source, "uiHintSink?.invoke(", label)
            requireAbsent(source, "ssidSink?.invoke(", label)
            requireAbsent(source, "dialogSink?.invoke(", label)
            requireAbsent(source, "dialogDismissSink?.invoke(", label)
            requireAbsent(source, "val sink = dialogSink", label)
        }
    }

    @Test
    fun inventoryCoversAllDeclaredChannels() {
        assertTrue("必须经 LegacySessionUiPort 接线", strippedController.contains("LegacySessionUiPort("))
        assertTrue("必须保留 uiHintSink 动态读取接线", strippedController.contains("hintSink = { uiHintSink }"))
        assertTrue("必须保留 ssidSink 动态读取接线", strippedController.contains("ssidSink = { ssidSink }"))
        assertTrue("必须保留 dialogSink 动态读取接线", strippedController.contains("dialogSink = { dialogSink }"))
        assertTrue("必须保留 dialogDismissSink 动态读取接线", strippedController.contains("dismissSink = { dialogDismissSink }"))

        val bridge = normalizeWhitespace(carWifiJoinerWiring())
        assertTrue("CarWifiJoiner 必须桥接 ui.phase(hint)", bridge.contains("override fun phase(hint: String?) { ui.phase(hint) }"))
        assertTrue(
            "CarWifiJoiner 必须惰性桥接 ui.hint(message)",
            bridge.contains("override fun hint(message: () -> String) { ui.hint(message) }"),
        )
        assertTrue("CarWifiJoiner 必须桥接本地化 text = { id, args -> t(id, *args) }", bridge.contains("text = { id, args -> t(id, *args) }"))
        assertTrue("CarWifiJoiner 必须桥接 askChoice 到 askWifiJoinChoice", bridge.contains("askWifiJoinChoice("))
    }
}
