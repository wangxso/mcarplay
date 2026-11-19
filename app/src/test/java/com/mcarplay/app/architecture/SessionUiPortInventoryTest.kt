package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SessionController 五通道 UI 写入经 SessionUiPort 接线后的完整清单：44 phase + 9 hint + 2 SSID + 1 ask + 1 dismiss。 */
private object SourceUiPortInventory {
    val PHASE_WRITES =
        listOf(
            "cancelConnecting" to "ui.phase(t(R.string.sc_cancelled))",
            "onSenderLinkClosed" to "ui.phase(closedHint)",
            "ensureSessionObjects" to "ui.phase(t(R.string.sc_hotspot_lost, ssid))",
            "ensureSessionObjects" to "ui.phase(t(R.string.sc_tap_phone_on_receiver, name))",
            "startConnection" to "ui.phase(t(R.string.sc_bt_ready))",
            "disconnect" to "ui.phase(null)",
            "onWifiCredentials" to "ui.phase(t(R.string.sc_got_credentials))",
            "autoJoinCarWifi" to "ui.phase(t(R.string.sc_bind_failed))",
            "joinCarWifiSilentlyLocked" to "ui.phase(t(R.string.sc_silent_join, wifi.ssid))",
            "waitForCarSsid" to "ui.phase(t(R.string.sc_switching, ssid))",
            "joinCarWifiSpecifier" to "ui.phase(t(R.string.sc_bound_existing))",
            "joinCarWifiSpecifier" to "ui.phase(t(R.string.sc_joining_no_data, wifi.ssid))",
            "joinCarWifiSpecifier" to "ui.phase(t(R.string.sc_joined))",
            "joinCarWifiSpecifier" to "ui.phase(t(R.string.sc_joined))",
            "waitForWifiEnabled" to "ui.phase(t(R.string.sc_cancelled_wifi_needed))",
            "waitForWifiEnabled" to "ui.phase(t(R.string.sc_wifi_off_needed))",
            "waitForWifiEnabled" to "ui.phase(t(R.string.sc_waiting_wifi))",
            "waitForWifiEnabled" to "ui.phase(t(R.string.sc_cancelled))",
            "waitForWifiEnabled" to "ui.phase(t(R.string.sc_wifi_off))",
            "promptSpecifierFailed" to "ui.phase(t(R.string.sc_join_failed_manual))",
            "promptSpecifierFailed" to "ui.phase(t(R.string.sc_join_failed_retry))",
            "manualJoinCarWifi" to "ui.phase(t(R.string.sc_bound_current))",
            "manualJoinCarWifi" to "ui.phase(t(R.string.sc_connect_in_settings, wifi.ssid))",
            "manualJoinCarWifi" to "ui.phase(t(R.string.sc_join_aborted, wifi.ssid))",
            "legacyJoinCarWifi" to "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))",
            "legacyJoinCarWifi" to "ui.phase(t(R.string.sc_join_failed_retry))",
            "legacyJoinCarWifi" to "ui.phase(t(R.string.sc_auto_joining, wifi.ssid))",
            "legacyJoinCarWifi" to "ui.phase(t(R.string.sc_auto_join_failed, wifi.ssid))",
            "legacyJoinCarWifi" to "ui.phase(t(R.string.sc_joining_may_revert, wifi.ssid))",
            "legacyJoinCarWifi" to "ui.phase(t(R.string.sc_not_staying, wifi.ssid))",
            "bindCarWifiAndRegister" to "ui.phase(t(R.string.sc_joined))",
            "bindCarWifiAndRegister" to "ui.phase(t(R.string.sc_bind_channel_failed, ssid))",
            "resolveEndpointHost" to "ui.phase(t(R.string.sc_mdns))",
            "onCarPlayEndpoint" to "ui.phase(t(R.string.sc_waiting_4301))",
            "onCarPlayEndpoint" to "ui.phase(t(R.string.sc_resolving_hotspot))",
            "onCarPlayEndpoint" to "ui.phase(t(R.string.sc_not_on_hotspot_endpoint, endpoint.ssid))",
            "onCarPlayEndpoint" to "ui.phase(t(R.string.sc_pairing))",
            "onCarPlayEndpoint" to "ui.phase(t(R.string.sc_plain_session))",
            "onCarPlayEndpoint" to "ui.phase(if (pairedKeys != null) t(R.string.sc_paired_setup) else t(R.string.sc_plain_session))",
            "onCarPlayEndpoint" to "ui.phase(t(R.string.sc_record_unconfirmed))",
            "onCarPlayEndpoint" to "ui.phase(t(R.string.sc_setup_rejected))",
            "waitForKnownWifiThenContinue" to "ui.phase(t(R.string.sc_waiting_join, target))",
            "waitForManualWifiThenContinue" to "ui.phase(t(R.string.sc_waiting_join, wifi.ssid))",
            "waitForManualWifiThenContinue" to "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))",
        )

    val HINT_WRITES =
        listOf(
            "ensureSessionObjects" to "ui.hint { t(R.string.sc_hotspot_lost_hint, ssid) }",
            "ensureSessionObjects" to "ui.hint { t(R.string.sc_tap_phone_on_receiver_hint, name) }",
            "waitForWifiEnabled" to "ui.hint { t(R.string.sc_turn_on_wifi) }",
            "legacyJoinCarWifi" to "ui.hint { t(R.string.sc_not_staying_hint, wifi.ssid) }",
            "copyHotspotPsk" to "ui.hint { t(R.string.sc_psk_copied, passphrase.length) }",
            "audioTeardownWiring" to
                "ui.hint { if (allDisabled) { t(R.string.sc_audio_all_incompatible) } " +
                "else { t(R.string.sc_audio_profile_rejected) } }",
            "onCarPlayEndpoint" to "ui.hint { t(R.string.sc_record_unconfirmed) }",
            "onCarPlayEndpoint" to "ui.hint { t(R.string.sc_setup_rejected_hint) }",
            "waitForManualWifiThenContinue" to "ui.hint { t(R.string.sc_not_joined_hint, wifi.ssid) }",
        )

    val SSID_WRITES =
        listOf(
            "onWifiCredentials" to "ui.ssidChanged { wifi.ssid }",
            "onCarPlayEndpoint" to "if (endpoint.ssid != INVITED_WIFI_LABEL) ui.ssidChanged { endpoint.ssid }",
        )
}

class SessionUiPortInventoryTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val stripped: String by lazy { KotlinSource.scan(controller).code }

    /** 音频拆链归因接线已迁入 [AudioTeardown] 构造块；单独提取以保留完整源码契约。 */
    private fun audioTeardownWiring(): String {
        val start = stripped.indexOf("private val audioTeardown =")
        check(start >= 0) { "找不到 AudioTeardown 构造块" }
        val constructorAt = stripped.indexOf("AudioTeardown(", start)
        check(constructorAt >= 0) { "找不到 AudioTeardown 构造调用" }
        var parens = 0
        var index = stripped.indexOf('(', constructorAt)
        while (index < stripped.length) {
            when (stripped[index]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) return stripped.substring(start, index + 1)
                }
            }
            index++
        }
        error("AudioTeardown 构造块没有闭合")
    }

    private fun sourceFor(function: String): String = if (function == "audioTeardownWiring") audioTeardownWiring() else body(function)

    /** 去注释/字符串后的指定函数体。 */
    private fun body(name: String): String {
        val marker = "fun $name("
        val start = if (name == "attributeAudioTeardown") stripped.lastIndexOf(marker) else stripped.indexOf(marker)
        check(start >= 0) { "找不到函数 $name" }
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
        check(parens == 0) { "$name 的参数表没有闭合" }
        val open = stripped.indexOf('{', index)
        check(open > index) { "找不到 $name 的函数体" }
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
        error("$name 的函数体没有闭合")
    }

    private fun normalizeWhitespace(value: String): String = value.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun requireIn(
        function: String,
        expression: String,
    ) {
        val code = sourceFor(function)
        if (!normalizeWhitespace(code).contains(normalizeWhitespace(expression))) {
            throw AssertionError("$function 必须保留 $expression\n函数体=\n" + reportFunctionBody(function))
        }
    }

    private fun reportFunctionBody(functionName: String): String = body(functionName)

    private fun requireOrder(
        function: String,
        first: String,
        second: String,
    ) {
        val code = normalizeWhitespace(body(function))
        val first = normalizeWhitespace(first)
        val second = normalizeWhitespace(second)
        val firstAt = code.indexOf(first)
        val secondAt = code.indexOf(second)
        assertTrue("$function 缺少 $first", firstAt >= 0)
        assertTrue("$function 缺少 $second", secondAt >= 0)
        assertTrue("$function 中 $first 必须先于 $second", firstAt < secondAt)
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

    private fun reportBody(functionName: String): String = body(functionName)

    @Test
    fun inventoryCoversExactlyTheFiveChannelCallSites() {
        SourceUiPortInventory.PHASE_WRITES.forEach { (function, expression) ->
            requireIn(function, expression)
        }
        SourceUiPortInventory.HINT_WRITES.forEach { (function, expression) ->
            requireIn(function, expression)
        }
        SourceUiPortInventory.SSID_WRITES.forEach { (function, expression) ->
            requireIn(function, expression)
        }
        requireIn("askWifiJoinChoice", "ui.ask(request) { result ->")
        requireIn("askWifiJoinChoice", "if (!answered.get()) runCatching { ui.dismiss(requestId) }")
        requireOrder(
            "onWifiCredentials",
            "ui.ssidChanged { wifi.ssid }",
            "ui.phase(t(R.string.sc_got_credentials))",
        )
        requireOrder(
            "ensureSessionObjects",
            "ui.phase(t(R.string.sc_hotspot_lost, ssid))",
            "ui.hint { t(R.string.sc_hotspot_lost_hint, ssid) }",
        )
        requireOrder(
            "ensureSessionObjects",
            "ui.phase(t(R.string.sc_tap_phone_on_receiver, name))",
            "ui.hint { t(R.string.sc_tap_phone_on_receiver_hint, name) }",
        )
        requireOrder(
            "legacyJoinCarWifi",
            "ui.phase(t(R.string.sc_not_staying, wifi.ssid))",
            "ui.hint { t(R.string.sc_not_staying_hint, wifi.ssid) }",
        )
        requireOrder(
            "onCarPlayEndpoint",
            "ui.phase(t(R.string.sc_record_unconfirmed))",
            "ui.hint { t(R.string.sc_record_unconfirmed) }",
        )
        requireOrder(
            "onCarPlayEndpoint",
            "ui.phase(t(R.string.sc_setup_rejected))",
            "ui.hint { t(R.string.sc_setup_rejected_hint) }",
        )
        requireOrder(
            "waitForManualWifiThenContinue",
            "ui.phase(t(R.string.sc_not_on_hotspot, wifi.ssid))",
            "ui.hint { t(R.string.sc_not_joined_hint, wifi.ssid) }",
        )
    }

    @Test
    fun channelWritesDoNotEscapeTheInventory() {
        SourceUiPortInventory.PHASE_WRITES.forEach { (function, expression) ->
            val expectedCount = SourceUiPortInventory.PHASE_WRITES.count { it.first == function && it.second == expression }
            requireExactOccurrences(sourceFor(function), expression, expectedCount, function)
        }
        SourceUiPortInventory.HINT_WRITES.forEach { (function, expression) ->
            val expectedCount = SourceUiPortInventory.HINT_WRITES.count { it.first == function && it.second == expression }
            requireExactOccurrences(sourceFor(function), expression, expectedCount, function)
        }
        SourceUiPortInventory.SSID_WRITES.forEach { (function, expression) ->
            val expectedCount = SourceUiPortInventory.SSID_WRITES.count { it.first == function && it.second == expression }
            requireExactOccurrences(sourceFor(function), expression, expectedCount, function)
        }
        val joinCarWifiSpecifierJoinedCount =
            SourceUiPortInventory.PHASE_WRITES
                .filter { it.first == "joinCarWifiSpecifier" && it.second == "ui.phase(t(R.string.sc_joined))" }
                .count()
        assertEquals(2, joinCarWifiSpecifierJoinedCount)
        requireExactOccurrences(
            stripped,
            "if (!answered.get()) runCatching { ui.dismiss(requestId) }",
            1,
            "SessionController",
        )
        val normalizedStripped = normalizeWhitespace(stripped)
        val phaseCount = Regex("""\bui\.phase\(""").findAll(normalizedStripped).count()
        val hintCount = Regex("""\bui\.hint\s*\{""").findAll(normalizedStripped).count()
        val ssidCount = Regex("""\bui\.ssidChanged\s*\{""").findAll(normalizedStripped).count()
        val askCount = Regex("""\bui\.ask\(""").findAll(normalizedStripped).count()
        val dismissCount = Regex("""\bui\.dismiss\(""").findAll(normalizedStripped).count()
        assertEquals(44, phaseCount)
        assertEquals(9, hintCount)
        assertEquals(2, ssidCount)
        assertEquals(1, askCount)
        assertEquals(1, dismissCount)
    }

    @Test
    fun channelWritesDoNotBypassThePort() {
        val phaseAssignments = Regex("""\bphaseHint\s*=""").findAll(normalizeWhitespace(stripped)).count()
        assertEquals("phaseHint 只允许由 LegacySessionUiPort 的 setPhase 写入", 1, phaseAssignments)
        requireExactOccurrences(stripped, "setPhase = { phaseHint = it }", 1, "SessionController")
        requireAbsent(stripped, "uiHintSink?.invoke(", "SessionController")
        requireAbsent(stripped, "ssidSink?.invoke(", "SessionController")
        requireAbsent(stripped, "dialogSink?.invoke(", "SessionController")
        requireAbsent(stripped, "dialogDismissSink?.invoke(", "SessionController")
        requireAbsent(stripped, "val sink = dialogSink", "SessionController")
    }

    @Test
    fun inventoryCoversAllDeclaredChannels() {
        assertTrue("必须经 LegacySessionUiPort 接线", stripped.contains("LegacySessionUiPort("))
        assertTrue("必须保留 uiHintSink 动态读取接线", stripped.contains("hintSink = { uiHintSink }"))
        assertTrue("必须保留 ssidSink 动态读取接线", stripped.contains("ssidSink = { ssidSink }"))
        assertTrue("必须保留 dialogSink 动态读取接线", stripped.contains("dialogSink = { dialogSink }"))
        assertTrue("必须保留 dialogDismissSink 动态读取接线", stripped.contains("dismissSink = { dialogDismissSink }"))
    }
}
