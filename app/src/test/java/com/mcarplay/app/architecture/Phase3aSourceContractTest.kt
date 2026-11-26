package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3a S 层源码契约（fail-closed，去注释/字符串扫描）：
 *  1. `RealAttemptScope`、`DualCheckAttemptScope`、`AttemptRegistry` 不反向引用控制器/UI/WiFi 锁/车型词；
 *  2. `RoundRetirement` 与 `SessionController.retireSession` 严禁取消或取代当前活跃轮次（防 L3572 配对自杀）；
 *  3. 全量引擎包生产源码不得引用 `AttemptScope` 或 `AttemptCancelled`；
 *  4. `SessionController` 的 7 处 `AttemptRegistry` 接线点与 `DualCheckAttemptScope` 构造按序落位；
 *  5. `DualCheckAttemptScope` 保持 `SESSION: SCOPE_MISMATCH` 固定诊断模板。
 */
class Phase3aSourceContractTest {
    private val controllerRaw: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val controllerCode: String by lazy { KotlinSource.scan(controllerRaw).code }

    private val roundRetirementCode: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("RoundRetirement.kt").readText()).code
    }

    private val realScopeRaw: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/RealAttemptScope.kt").readText()
    }

    private val dualCheckRaw: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/DualCheckAttemptScope.kt").readText()
    }

    private val registryRaw: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/AttemptRegistry.kt").readText()
    }

    private val enginePackages =
        setOf(
            "airplay",
            "audio",
            "bt",
            "carlife",
            "compat",
            "diagnostics",
            "i18n",
            "iap2",
            "input",
            "media",
            "net",
            "shizuku",
            "transport",
            "util",
            "video",
        )

    private val bannedVehicleWords =
        setOf("passat", "audi", "honda", "cadillac", "diplay", "winplay", "macplay", "chery", "arrizo", "mib3", "id7", "vw", "cinemo")

    private fun normalize(source: String): String = source.replace(Regex("\\s+"), " ")

    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val parenOpen = source.indexOf('(', start)
        assertTrue("$signature 缺少参数列表", parenOpen >= 0)
        var parens = 0
        var i = parenOpen
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
        assertEquals("$signature 参数列表未闭合", 0, parens)
        val open = source.indexOf('{', i)
        assertTrue("$signature 缺少函数体", open > i)
        var depth = 0
        var cursor = open
        while (cursor < source.length) {
            when (source[cursor]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, cursor + 1)
                }
            }
            cursor++
        }
        error("$signature 函数体未闭合")
    }

    private fun requireOrder(
        source: String,
        vararg fragments: String,
    ) {
        val normalized = normalize(source)
        var cursor = -1
        fragments.forEach { fragment ->
            val at = normalized.indexOf(normalize(fragment), cursor + 1)
            assertTrue("找不到或顺序错误：$fragment", at > cursor)
            cursor = at
        }
    }

    @Test
    fun newSessionFilesHaveNoControllerWifiLockOrVehicleIdentifiers() {
        val forbiddenTokens = listOf("SessionController", "MainActivity", "wifiBindLock", "HandoffWifiGuard", "SystemClock")
        listOf(
            "session/RealAttemptScope.kt" to realScopeRaw,
            "session/DualCheckAttemptScope.kt" to dualCheckRaw,
            "session/AttemptRegistry.kt" to registryRaw,
        ).forEach { (name, raw) ->
            val stripped = KotlinSource.scan(raw).code
            forbiddenTokens.forEach { token ->
                assertFalse("$name 不得引用 $token", stripped.contains(token))
            }
            val vehicleHits = KotlinSource.identifierWords(stripped) intersect bannedVehicleWords
            assertTrue("$name 标识符不得含车型禁词：$vehicleHits", vehicleHits.isEmpty())
        }
    }

    @Test
    fun retireSessionAndRoundRetirementNeverCancelOrSupersedeActiveAttempt() {
        val retireBody = functionBody(controllerCode, "fun retireSession(")
        val forbiddenCalls =
            listOf(
                "attemptRegistry.cancelActive",
                "attemptRegistry.supersedeAll",
                "attemptRegistry.cancelIfObservation",
                "attemptRegistry.supersedeClient",
            )
        forbiddenCalls.forEach { call ->
            assertFalse("SessionController.retireSession 不得调用 $call（防配对自杀）", retireBody.contains(call))
            assertFalse("RoundRetirement 不得调用 $call", roundRetirementCode.contains(call))
        }
        assertFalse("RoundRetirement 不得引用 attemptRegistry", roundRetirementCode.contains("attemptRegistry"))
    }

    @Test
    fun enginePackagesNeverReferenceAttemptScopeOrAttemptCancelled() {
        val violations = mutableListOf<String>()
        SourceTree.mainKotlinFiles.keys
            .filter { SourceTree.topLevelPackage(it) in enginePackages }
            .sorted()
            .forEach { path ->
                val code = SourceTree.scan(path).code
                if (Regex("""\bAttemptScope\b""").containsMatchIn(code)) {
                    violations += "$path -> AttemptScope"
                }
                if (Regex("""\bAttemptCancelled\b""").containsMatchIn(code)) {
                    violations += "$path -> AttemptCancelled"
                }
            }
        assertTrue("引擎包严禁引用 AttemptScope 或 AttemptCancelled：\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun sessionControllerWiresAllSevenAttemptRegistryHooksInOrder() {
        val normalizedController = normalize(controllerCode)
        assertTrue("缺少 attemptRegistry 字段声明", normalizedController.contains("private val attemptRegistry = AttemptRegistry()"))

        val onLinkClosed = functionBody(controllerCode, "fun onSenderLinkClosed(")
        requireOrder(
            onLinkClosed,
            "handoffOwnership.cancelIfOwnedBy(",
            "connectCancelled = true",
            "attemptRegistry.cancelIfObservation(observation, DiagnosticCode.BT_LINK_CLOSED)",
        )

        val startConnection = functionBody(controllerCode, "fun startConnection(")
        requireOrder(
            startConnection,
            "ReceiverDetectionRuntime.connectionStarted(diagnosticAttempt)",
            "attemptRegistry.supersedeAll()",
        )

        val disconnect = functionBody(controllerCode, "fun disconnect(")
        requireOrder(
            disconnect,
            "connectCancelled = true",
            "attemptRegistry.cancelActive(reason)",
            "ReceiverDetectionRuntime.retireConnection()",
            "ReceiverDetectionRuntime.stop(",
            "attemptRegistry.supersedeAll()",
        )

        val reconnect = functionBody(controllerCode, "private fun reconnectOnStreamingInvite(")
        requireOrder(
            reconnect,
            "streamingDiagnosticAttempt = null",
            "attemptRegistry.supersedeClient(client, DiagnosticCode.SUPERSEDED)",
            "retireSession()",
        )

        val onEndpoint = functionBody(controllerCode, "private fun onCarPlayEndpoint(")
        requireOrder(
            onEndpoint,
            "val realScope = RealAttemptScope(",
            "attemptRegistry.activate(realScope)",
            "val scope: AttemptScope = DualCheckAttemptScope(",
            "legacy = LegacyAttemptScope(",
            "real = realScope,",
            "onMismatch = ::appendLog,",
            "attemptRegistry.detachIfActive(realScope)",
            "releaseHandoff()",
            "currentClient = client",
            "attemptRegistry.bindClient(client, realScope)",
            "currentClient = client",
            "attemptRegistry.bindClient(client, realScope)",
        )
        assertEquals(
            "onCarPlayEndpoint 必须在两处 currentClient = client 后绑定 client",
            2,
            Regex("""attemptRegistry\.bindClient\(client,\s*realScope\)""").findAll(onEndpoint).count(),
        )
    }

    @Test
    fun dualCheckPreservesScopeMismatchLogTemplate() {
        assertTrue(
            "DualCheckAttemptScope 必须保持 SESSION: SCOPE_MISMATCH 日志模板",
            dualCheckRaw.contains("SESSION: SCOPE_MISMATCH attempt=\$id prop=\$prop legacy=\$legacyValue real=\$realValue "),
        )
        assertTrue(
            "DualCheckAttemptScope 必须保持完整后缀字段",
            dualCheckRaw.contains("cancelReason=\$reason superseded=\$superseded obsNull=\${observation == null}"),
        )
    }
}
