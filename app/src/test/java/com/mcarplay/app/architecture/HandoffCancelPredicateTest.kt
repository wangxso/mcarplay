package com.mcarplay.app.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归（#65 独立审查的三个 P2）：Wi-Fi 入网兜底路径必须**真正使用**传入的取消判据。
 *
 * 独立审查在 head `782e2b7` 上指出三处「参数收下了却没用」：
 * 1. `legacyJoinCarWifi` 接收了 `isCancelled` 却从未引用——弹窗走默认判据、前置检查与
 *    轮询只看全局 `connectCancelled`、手动兜底丢弃参数、绑定也没传；
 * 2. `manualJoinCarWifi` 打开 WLAN 设置后的 120 秒轮询只看全局标志，交接所有权失效时
 *    旧任务可能在新一轮里绑定旧端点的热点；
 * 3. `promptSpecifierFailed` 同样只查全局标志。
 *
 * 危害是实际存在的：交接被另一条链路接手后 `connectCancelled` 仍为 false，旧任务因此
 * 继续跑完 90 秒弹窗或 120 秒轮询，并在新一轮里重绑定/重注册旧热点的 ctrl 广播。
 *
 * Phase 2c-2 将四条凭据入网路径迁入 `net/CarWifiJoiner.kt` 后，本契约同时守住
 * `CarWifiJoiner` 内的取消分层与 `SessionController` 上层转发接线。
 */
class HandoffCancelPredicateTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val joiner: String by lazy {
        SourceTree.mainKotlinFiles.getValue("net/CarWifiJoiner.kt").readText()
    }

    private val scopeSource: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/AttemptScope.kt").readText()
    }

    /** 取 `net/CarWifiJoiner.kt` 中 [name] 的函数体：先配平参数表的圆括号，再配平函数体的花括号。 */
    private fun body(name: String): String = extractBody(joiner, "fun $name(")

    private fun extractBody(
        source: String,
        marker: String,
    ): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "找不到函数 $marker" }
        // 参数表可能跨行，默认参数里还含 `{ connectCancelled }`：只按圆括号配平。
        var parens = 0
        var i = source.indexOf('(', start)
        while (i < source.length) {
            when (source[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            i++
        }
        check(parens == 0) { "$marker 的参数表没有闭合" }
        val open = source.indexOf('{', i)
        check(open > i) { "找不到 $marker 的函数体" }
        var depth = 0
        var j = open
        while (j < source.length) {
            when (source[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, j + 1)
                }
            }
            j++
        }
        error("$marker 的函数体没有闭合")
    }

    /**
     * 判据只能用在检查处：裸 `connectCancelled` 会让调用方传入的更严格判据失效。
     * 注释里提到它不算违规（先去注释再判断），默认参数里的 `{ connectCancelled }` 允许保留。
     */
    private fun assertNoBareGlobalCheck(
        name: String,
        source: String,
    ) {
        val code = KotlinSource.scan(source).code
        val bare = Regex("""(?<![A-Za-z0-9_.])connectCancelled(?![A-Za-z0-9_])""")
        val hits = bare.findAll(code).count()
        val defaults = Regex("""\{\s*connectCancelled\s*\}""").findAll(code).count()
        assertFalse(
            "$name 里仍有 ${hits - defaults} 处裸 connectCancelled：应改用 isCancelled()，否则传入的交接判据会被忽略",
            hits > defaults,
        )
        // 函数体不含参数表，因此默认参数里的 `{ connectCancelled }` 不计入；体内应为 0。
        assertTrue("$name 的检查应全部走 isCancelled()", defaults == 0)
    }

    @Test
    fun legacyJoinUsesTheCallersPredicate() {
        val source = body("legacyJoinCarWifi")
        assertNoBareGlobalCheck("legacyJoinCarWifi", source)
        assertTrue("弹窗必须收到 isCancelled", source.contains("isCancelled = isCancelled"))
        assertTrue("手动兜底必须透传判据", source.contains("manualJoinCarWifi(wifi, isCancelled, sleepFn, awaitLatchFn)"))
        assertTrue(
            "绑定必须传判据，否则交接失效后仍会绑定旧热点",
            Regex("""bindVerifiedCarWifi\([^)]*isCancelled""").findAll(source).count() >= 3,
        )
    }

    @Test
    fun manualJoinUsesTheCallersPredicateThroughoutTheWait() {
        val source = body("manualJoinCarWifi")
        assertNoBareGlobalCheck("manualJoinCarWifi", source)
        assertTrue("120 秒轮询必须查 isCancelled()", source.contains("if (isCancelled()) return"))
        assertTrue(
            "最终绑定必须传判据",
            Regex("""bindVerifiedCarWifi\([^)]*isCancelled""").containsMatchIn(source),
        )
    }

    @Test
    fun specifierFailurePromptUsesTheCallersPredicate() {
        assertNoBareGlobalCheck("promptSpecifierFailed", body("promptSpecifierFailed"))
        val controllerWrapper = extractBody(controller, "private fun promptSpecifierFailed(")
        assertTrue(
            "SessionController.promptSpecifierFailed 转发时必须透传 isCancelled",
            controllerWrapper.contains("carWifiJoiner.promptSpecifierFailed(wifi, shellReady, isCancelled)"),
        )
    }

    /** 同类缺陷：WiFi 关闭时的等待循环也必须跟随调用方判据。 */
    @Test
    fun wifiEnableWaitUsesTheCallersPredicate() {
        assertNoBareGlobalCheck("waitForWifiEnabled", body("waitForWifiEnabled"))
    }

    /** 判据必须真的被引用，而不是收下就扔——这是三个 P2 的共同形态。 */
    @Test
    fun everyHandoffHelperReferencesItsPredicate() {
        listOf(
            "legacyJoinCarWifi",
            "manualJoinCarWifi",
            "promptSpecifierFailed",
            "waitForWifiEnabled",
        ).forEach { name ->
            val source = body(name)
            assertTrue("$name 根本没有引用 isCancelled", source.contains("isCancelled"))
        }
    }

    /** PR #106 合并后：autoJoinCarWifi 接收调用方的取消/弹窗谓词与结果回调，并用 wifiBindLock 串行静默与 specifier。 */
    @Test
    fun autoJoinCarWifiUsesCallersPredicatesAndResult() {
        val signatureStart = joiner.indexOf("fun autoJoinCarWifi(")
        val signatureEnd = joiner.indexOf(") {", signatureStart)
        val signature = joiner.substring(signatureStart, signatureEnd)
        assertTrue("autoJoinCarWifi 必须接收 cancelled 参数", signature.contains("cancelled: () -> Boolean"))
        assertTrue("autoJoinCarWifi 必须接收 canPrompt 参数", signature.contains("canPrompt: () -> Boolean"))
        assertTrue("autoJoinCarWifi 必须接收 onResult 参数", signature.contains("onResult: (Boolean) -> Unit"))
        val source = body("autoJoinCarWifi")
        val code = KotlinSource.scan(source).code
        assertTrue("autoJoinCarWifi 静默绑定必须传 cancelled", code.contains("joinCarWifiSilentlyLocked(wifi, isCancelled = cancelled)"))
        assertTrue("autoJoinCarWifi 等待 WiFi 开启必须传 cancelled", code.contains("waitForWifiEnabled(isCancelled = cancelled)"))
        assertTrue("autoJoinCarWifi 旧安全类型回退必须传 cancelled", code.contains("legacyJoinCarWifi(wifi, shellReady, isCancelled = cancelled)"))
        assertTrue(
            "autoJoinCarWifi 弹窗失败必须受 canPrompt 约束",
            code.contains("promptSpecifierFailed(wifi, shellReady, isCancelled = { !canPrompt() })"),
        )
        val joinerCode = KotlinSource.scan(joiner).code
        assertTrue(
            "CarWifiJoiner.connectCancelled 必须动态转发注入的 globalCancelled()",
            joinerCode.contains("private val connectCancelled: Boolean\n        get() = globalCancelled()"),
        )
        val controllerCode = KotlinSource.scan(controller).code
        assertTrue(
            "SessionController 必须把 globalCancelled 接到 connectCancelled",
            controllerCode.contains("globalCancelled = { connectCancelled }"),
        )
    }

    /** Phase 3b：快速兜底必须在发起绑定前查询调用方判据。 */
    @Test
    fun specifierQuickFallbackUsesTheCallersPredicate() {
        val source = body("specifierQuickFallback")
        assertTrue(
            "specifierQuickFallback 必须接收 isCancelled",
            joiner.contains(
                "fun specifierQuickFallback(\n" +
                    "        wifi: Iap2SenderSession.WifiConfiguration,\n" +
                    "        sleepFn: (Long) -> Unit = sleep,\n" +
                    "        isCancelled: () -> Boolean = { connectCancelled },\n" +
                    "    ): N? {",
            ),
        )
        assertNoBareGlobalCheck("specifierQuickFallback", source)
        assertTrue("specifierQuickFallback 绑定前必须查 isCancelled()", source.contains("if (isCancelled()) return null"))
        assertTrue(
            "specifierQuickFallback 绑定必须传判据",
            source.contains("bindExistingCarWifi(connector, wifi.ssid, sleepFn = sleepFn, isCancelled = isCancelled)"),
        )
    }

    /** Phase 3b：深度兜底轮询也必须跟随调用方判据，不再只看全局标志。 */
    @Test
    fun specifierDeepFallbackUsesTheCallersPredicate() {
        val source = body("specifierDeepFallback")
        assertTrue(
            "specifierDeepFallback 必须接收 isCancelled",
            joiner.contains(
                "fun specifierDeepFallback(\n" +
                    "        wifi: Iap2SenderSession.WifiConfiguration,\n" +
                    "        sleepFn: (Long) -> Unit = sleep,\n" +
                    "        isCancelled: () -> Boolean = { connectCancelled },\n" +
                    "    ): N? {",
            ),
        )
        assertNoBareGlobalCheck("specifierDeepFallback", source)
        assertTrue("深度兜底轮询必须查 isCancelled()", source.contains("if (isCancelled()) return null"))
        assertTrue(
            "深度兜底最终的绑定必须传判据",
            source.contains("bindExistingCarWifi(connector, wifi.ssid, sleepFn = sleepFn, isCancelled = isCancelled)"),
        )
    }

    /** 现状快照：配件交接路径必须真正使用 handoffCancelled 判据。 */
    @Test
    fun accessoryHandoffPassesTheHandoffPredicate() {
        val controllerCode = KotlinSource.scan(controller).code
        val joinerCode = KotlinSource.scan(joiner).code
        val directCalls =
            Regex(
                """joinAccessoryWifi\(request\.accessoryWifi!!, request\.handoffCancelled, sleepFn, awaitLatchFn\)|""" +
                    """joinAccessoryWifi\(cachedConfiguration, request\.handoffCancelled, sleepFn, awaitLatchFn\)""",
            ).findAll(joinerCode).count()
        val namedParams =
            Regex("""isCancelled\s*=\s*request\.handoffCancelled""").findAll(joinerCode).count()
        assertTrue("joinAccessoryWifi 两处直接调用必须传 handoffCancelled 与可取消等待端口", directCalls == 2)
        assertTrue("交接绑定/确认/手动路径必须至少三处显式传 handoffCancelled", namedParams >= 3)
        assertTrue(
            "SessionController 必须把 scope/handoff 判据接到握手请求",
            controllerCode.contains("handoffCancelled = { handoffCancelled() }") &&
                controllerCode.contains("scopeActive = { scope.isActive }"),
        )
    }

    /** 现状快照：handoffCancelled 必须同时看全局取消与交接归属。 */
    @Test
    fun handoffCancelledPredicateCoversOwnershipAndCancellation() {
        val predicateLine = controller.lineSequence().first { "fun handoffCancelled(): Boolean" in it }
        assertTrue("handoffCancelled 必须委托真实 helper", predicateLine.contains("attemptHandoffCancelled(scope)"))
        assertTrue(
            "交接归属必须由调用点独立提供，不能折进 scope",
            predicateLine.contains("handoffOwnership.owns(endpoint.observation)"),
        )
        val helperLine = scopeSource.lineSequence().first { "): Boolean = !scope.isActive || !ownsHandoff()" in it }
        assertTrue(
            "helper 必须保留取消/失主两层短路或组合",
            helperLine.contains("!scope.isActive || !ownsHandoff()"),
        )
    }
}
