package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2b `EndpointResolver` 源码接线与边界守卫（设计方案 §6.4，fail closed）。
 *
 * 校验：
 *  - 六函数与四个 `preferEndpoint` 调用点真经 `EndpointResolver` 接线；
 *  - `captureInviteView` 入口捕获与 `endpointBrowser` / `ctrlAdvertiser` / `wifiConnector` 延迟动态读取；
 *  - PR #93 中性策略回调参数顺序及 `net/EndpointResolver.kt` 禁止出现 `Ora`/`ora03`/`VehiclePresetRuntime` 标识符；
 *  - `airplay-pairing` 配对任务内 `endpointSelfContained` → `awaitCtrlInvite` → `resolveEndpointHost` →
 *    `preferEndpoint` → `RECEIVER_RESOLVED` → `scope.isActive` 顺序，以及 `resolveEndpointHost` 块体薄包装。
 */
class EndpointResolverWiringTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val controllerScan: KotlinSource.Scan by lazy {
        SourceTree.scan("SessionController.kt")
    }

    private val controllerCode: String by lazy {
        controllerScan.code
    }

    private val resolverText: String by lazy {
        SourceTree.mainKotlinFiles.getValue("net/EndpointResolver.kt").readText()
    }

    private val resolverScan: KotlinSource.Scan by lazy {
        SourceTree.scan("net/EndpointResolver.kt")
    }

    private val resolverCode: String by lazy {
        resolverScan.code
    }

    private fun normalizeWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** 在已去注释/字符串的源码中提取指定签名的块体或表达式体（找不到或未闭合时直接 fail closed）。 */
    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "找不到函数签名：$signature" }
        val paramsOpen = source.indexOf('(', start)
        check(paramsOpen >= 0) { "$signature 缺少参数列表" }
        var depth = 0
        var paramsClose = -1
        for (i in paramsOpen until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        paramsClose = i
                        break
                    }
                }
            }
        }
        check(paramsClose > paramsOpen) { "$signature 参数列表未闭合" }
        val openBrace = source.indexOf('{', paramsClose)
        val equals = source.indexOf('=', paramsClose)
        if (equals >= 0 && (openBrace < 0 || equals < openBrace)) {
            val lineEnd = source.indexOf('\n', equals).let { if (it < 0) source.length else it }
            return source.substring(equals, lineEnd).trim()
        }
        check(openBrace > paramsClose) { "$signature 缺少函数体" }
        var braceDepth = 0
        for (i in openBrace until source.length) {
            when (source[i]) {
                '{' -> braceDepth++
                '}' -> {
                    braceDepth--
                    if (braceDepth == 0) return source.substring(openBrace, i + 1)
                }
            }
        }
        error("$signature 函数体花括号未闭合")
    }

    /** 提取 `private val endpointResolver = EndpointResolver(...)` 构造块（fail closed）。 */
    private fun resolverConstructionBlock(): String {
        val start = controllerCode.indexOf("private val endpointResolver =")
        check(start >= 0) { "SessionController 缺少 private val endpointResolver 定义" }
        val ctorAt = controllerCode.indexOf("EndpointResolver(", start)
        check(ctorAt > start) { "endpointResolver 未构造 EndpointResolver(" }
        val openParen = controllerCode.indexOf('(', ctorAt)
        check(openParen >= 0) { "EndpointResolver 构造缺少 (" }
        var depth = 0
        for (i in openParen until controllerCode.length) {
            when (controllerCode[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return controllerCode.substring(start, i + 1)
                }
            }
        }
        error("EndpointResolver 构造块未闭合")
    }

    /** 提取给定锚点之后首个 `{ ... }` 块（fail closed）。 */
    private fun bracedBlockAfter(
        source: String,
        anchor: String,
    ): String {
        val anchorAt = source.indexOf(anchor)
        check(anchorAt >= 0) { "找不到锚点：$anchor" }
        val open = source.indexOf('{', anchorAt)
        check(open >= 0) { "锚点后缺少 {：$anchor" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("锚点后的花括号未闭合：$anchor")
    }

    private fun requireOrder(
        source: String,
        vararg fragments: String,
    ) {
        val normalized = normalizeWhitespace(source)
        var cursor = -1
        for (fragment in fragments) {
            val target = normalizeWhitespace(fragment)
            val at = normalized.indexOf(target, cursor + 1)
            assertTrue("未找到片段或顺序错误：$fragment\n源码：$normalized", at > cursor)
            cursor = at
        }
    }

    @Test
    fun controllerDelegatesSixFunctionsAndFourPreferCallSitesToEndpointResolver() {
        val awaitBody = normalizeWhitespace(functionBody(controllerCode, "private fun awaitCtrlInvite("))
        assertEquals("= endpointResolver.awaitCtrlInvite(endpoint, timeoutMillis, sleep)", awaitBody)

        // resolveEndpointHost 必须保持块体薄包装，且内部唯一持有 sc_mdns UI 回调
        val resolveRawBody = functionBody(controllerCode, "private fun resolveEndpointHost(")
        assertTrue("resolveEndpointHost 必须是块体薄包装", resolveRawBody.startsWith("{") && resolveRawBody.endsWith("}"))
        requireOrder(
            resolveRawBody,
            "return endpointResolver.resolveEndpointHost(",
            "endpoint = endpoint,",
            "source = source,",
            "onResolutionNeeded = { ui.phase(t(R.string.sc_mdns)) },",
        )

        val enrichBody = normalizeWhitespace(functionBody(controllerCode, "private fun enrichEndpointFromBrowser("))
        assertEquals("= endpointResolver.enrichEndpointFromBrowser(endpoint, source)", enrichBody)

        val txtBody = normalizeWhitespace(functionBody(controllerCode, "private fun txtValue("))
        assertEquals("= EndpointResolver.txtValue(info, key)", txtBody)

        val preferBody = normalizeWhitespace(functionBody(controllerCode, "private fun preferEndpoint("))
        assertEquals("= EndpointResolver.preferEndpoint(late, fallback)", preferBody)

        // mergeBrowserEndpoint 已迁入 EndpointResolver，Controller 不保留重复实现
        assertFalse(
            "SessionController 不得保留 mergeBrowserEndpoint 算法副本",
            controllerCode.contains("fun mergeBrowserEndpoint("),
        )

        // 四个 preferEndpoint 调用点（onLateEndpoint 两处、drainPendingEndpoint 一处、onCarPlayEndpoint 一处）
        val onLateBody = normalizeWhitespace(functionBody(controllerCode, "private fun onLateEndpoint("))
        requireOrder(
            onLateBody,
            "if (previous.observation === latest.observation) preferEndpoint(latest, previous) else latest",
            "val merged = preferEndpoint(endpoint, client.currentEndpoint)",
        )
        assertEquals(
            "onLateEndpoint 必须恰好有 2 处 preferEndpoint 调用",
            2,
            Regex("""\bpreferEndpoint\(""").findAll(onLateBody).count(),
        )

        val drainBody = normalizeWhitespace(functionBody(controllerCode, "private fun drainPendingEndpoint("))
        assertTrue(
            "drainPendingEndpoint 必须调用 preferEndpoint(late, client.currentEndpoint)",
            drainBody.contains("client.updateEndpoint(preferEndpoint(late, client.currentEndpoint),"),
        )
        assertEquals(
            "drainPendingEndpoint 必须恰好有 1 处 preferEndpoint 调用",
            1,
            Regex("""\bpreferEndpoint\(""").findAll(drainBody).count(),
        )

        val onEndpointBody = normalizeWhitespace(functionBody(controllerCode, "private fun onCarPlayEndpoint("))
        assertTrue(
            "onCarPlayEndpoint 必须调用 preferEndpoint(late, effectiveEndpoint)",
            onEndpointBody.contains("effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)"),
        )
        assertEquals(
            "onCarPlayEndpoint 必须恰好有 1 处 preferEndpoint 调用",
            1,
            Regex("""\bpreferEndpoint\(""").findAll(onEndpointBody).count(),
        )
    }

    @Test
    fun dynamicDependenciesAndCaptureSemanticsArePreservedInControllerWiring() {
        val ctor = normalizeWhitespace(resolverConstructionBlock())
        requireOrder(
            ctor,
            "uptimeMillis = { android.os.SystemClock.uptimeMillis() },",
            "sleepMillis = { millis -> sessionCancelSignal.sleep(millis) },",
            "isConnectCancelled = { connectCancelled },",
            "captureInviteView = {",
            "awaitEndpoint = { timeoutMs ->",
            "forceAnnounce = { ssid ->",
            "gatewayCandidates = {",
            "directDiscoveryPolicy = { endpoint, discovered ->",
            "log = ::appendLog,",
            "resolveWaitMillis = ENDPOINT_RESOLVE_WAIT_MS,",
        )

        // captureInviteView 必须在进入时捕获一次 ctrlAdvertiser，三个 getter 各自动态读取 captured 实例
        val captureBlock = normalizeWhitespace(bracedBlockAfter(ctor, "captureInviteView ="))
        requireOrder(
            captureBlock,
            "val advertiser = ctrlAdvertiser",
            "advertiser?.let { captured ->",
            "object : InviteView {",
            "override val inviteCount: Int get() = captured.inviteCount",
            "override val lastInviteAtMillis: Long get() = captured.lastInviteAtMillis",
            "override val advertisedAtMillis: Long get() = captured.advertisedAtMillis",
        )

        // awaitEndpoint、forceAnnounce、gatewayCandidates 必须在每次回调时动态读取当前组件
        val awaitBlock = normalizeWhitespace(bracedBlockAfter(ctor, "awaitEndpoint ="))
        requireOrder(
            awaitBlock,
            "val scope = attemptRegistry.activeScope()",
            "endpointBrowser",
            "awaitEndpoint(",
            "timeoutMs,",
            "isCancelled = {",
            "scope?.isActive == false ||",
            "sessionCancelSignal.isCancelled ||",
            "connectCancelled",
            "sleepMillis = { millis -> (scope?.sleep(millis) ?: sessionCancelSignal.sleep(millis)) },",
        )
        assertTrue("awaitEndpoint 必须动态读取 endpointBrowser", awaitBlock.contains("endpointBrowser"))
        assertTrue("awaitEndpoint 必须接线到 discoveredEndpoint", awaitBlock.contains("?.let(::discoveredEndpoint)"))

        val forceBlock = normalizeWhitespace(bracedBlockAfter(ctor, "forceAnnounce ="))
        assertTrue(
            "forceAnnounce 必须动态读取 ctrlAdvertiser 与 wifiConnector?.currentNetwork",
            forceBlock.contains(
                "ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)",
            ),
        )
        assertFalse("forceAnnounce 不得复用已捕获的 captured 实例", forceBlock.contains("captured"))

        val gatewayBlock = normalizeWhitespace(bracedBlockAfter(ctor, "gatewayCandidates ="))
        assertTrue(
            "gatewayCandidates 必须动态读取 wifiConnector?.gatewayCandidates().orEmpty()",
            gatewayBlock.contains("wifiConnector?.gatewayCandidates().orEmpty()"),
        )

        // NsdServiceInfo 适配器必须保持懒转发，不预解码全量属性
        val adapterBody = normalizeWhitespace(bracedBlockAfter(controllerCode, "private fun discoveredEndpoint("))
        requireOrder(
            adapterBody,
            "override val hostAddress: String? get() = info.host?.hostAddress",
            "override val port: Int get() = info.port",
            "override fun txtBytes(key: String): ByteArray? = info.attributes[key]",
        )
    }

    @Test
    fun directDiscoveryPolicyKeepsPr93ArgumentOrderAndEndpointResolverHasNoVehicleOrPresetIdentifiers() {
        val ctor = normalizeWhitespace(resolverConstructionBlock())
        val policyBlock = normalizeWhitespace(bracedBlockAfter(ctor, "directDiscoveryPolicy ="))
        requireOrder(
            policyBlock,
            "ReceiverCompatibility.prefersOra03DirectDiscovery(",
            "endpoint.receiverSignals,",
            "txtValue(discovered,",
            "txtValue(discovered,",
            "txtValue(discovered,",
            "VehiclePresetRuntime.current,",
        )

        // 核对带字符串字面量的原始构造块中 TXT 键顺序精确为 model -> fv -> srcvers
        val rawCtorStart = controller.indexOf("private val endpointResolver =")
        check(rawCtorStart >= 0) { "找不到原始 endpointResolver 构造块" }
        val rawPolicyBlock = bracedBlockAfter(controller.substring(rawCtorStart), "directDiscoveryPolicy =")
        requireOrder(
            rawPolicyBlock,
            "endpoint.receiverSignals,",
            "txtValue(discovered, \"model\"),",
            "txtValue(discovered, \"fv\"),",
            "txtValue(discovered, \"srcvers\"),",
            "VehiclePresetRuntime.current,",
        )

        // EndpointResolver.kt 必须通过中性布尔回调与 Gate 通信，且标识符不得含 Ora/ora03/VehiclePresetRuntime
        val awaitInResolver = normalizeWhitespace(functionBody(resolverCode, "fun awaitCtrlInvite("))
        requireOrder(
            awaitInResolver,
            "val discovered = awaitEndpoint(0)",
            "val preferResolvedMdns = discovered != null && directDiscoveryPolicy(endpoint, discovered)",
            "CtrlInviteGate.decide(",
            "preferResolvedMdns = preferResolvedMdns,",
            "CtrlInviteGate.Decision.MDNS_DIRECT -> {",
        )

        val forbiddenWords = setOf("ora", "ora03", "gwm", "es11", "preset", "vehicle", "vehiclepreset", "vehiclepresetruntime")
        val resolverWords = KotlinSource.identifierWords(resolverCode)
        val hitWords = resolverWords intersect forbiddenWords
        assertTrue("net/EndpointResolver.kt 标识符不得包含车型或预设词：$hitWords", hitWords.isEmpty())
        assertFalse("net/EndpointResolver.kt 不得引用 ReceiverCompatibility", resolverCode.contains("ReceiverCompatibility"))
        assertFalse("net/EndpointResolver.kt 不得引用 VehiclePresetRuntime", resolverCode.contains("VehiclePresetRuntime"))
        assertFalse("net/EndpointResolver.kt 不得引用 VehiclePreset", resolverCode.contains("VehiclePreset"))
    }

    @Test
    fun resolverAlgorithmsAndPairingTaskOrderStayIntact() {
        // 1) 常量与帕萨特 2026-11-01 23:02:38 证据注释保留在 SessionController
        assertTrue(
            "必须保留 CTRL_INVITE_WAIT_MS = 9_000L",
            controllerCode.contains("private const val CTRL_INVITE_WAIT_MS = 9_000L"),
        )
        assertTrue(
            "必须保留帕萨特 2026-11-01 23:02:38 邀请证据注释",
            controller.contains("2026-11-01 23:02:38"),
        )
        assertTrue(
            "必须保留 ENDPOINT_RESOLVE_WAIT_MS = 3_000L",
            controllerCode.contains("private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L"),
        )

        // 2) EndpointResolver 内部的双次 uptimeMillis 采样与 resolve/merge/enrich 调用链
        val awaitInResolver = normalizeWhitespace(functionBody(resolverCode, "fun awaitCtrlInvite("))
        requireOrder(
            awaitInResolver,
            "val advertiser = captureInviteView()",
            "val deadline = uptimeMillis() + timeoutMillis",
            "val startedAt = uptimeMillis()",
            "var lastLogAt = startedAt",
            "var reannouncedAt = 0L",
            "while (uptimeMillis() < deadline)",
            "if (isConnectCancelled()) return false",
            "val invites = advertiser?.inviteCount ?: 0",
            "val now = uptimeMillis()",
            "val discovered = awaitEndpoint(0)",
            "reannouncedAt = now",
            "forceAnnounce(endpoint.ssid)",
            "if (now - lastLogAt > 1_200)",
            "try {",
            "sleep(50)",
            "} catch (_: InterruptedException) {",
            "return false",
        )

        val resolveInResolver = normalizeWhitespace(functionBody(resolverCode, "fun resolveEndpointHost("))
        requireOrder(
            resolveInResolver,
            "val cached = awaitEndpoint(0)",
            "effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)",
            "val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)",
            "val needPort = effective.airPlayPort <= 0",
            "if (!needHost && !needPort) return effective",
            "onResolutionNeeded()",
            "log(",
            "awaitEndpoint(resolveWaitMillis) ?: run {",
            "val gateways = gatewayCandidates()",
            "return mergeBrowserEndpoint(effective, info, source, needPort)",
        )
        assertEquals(
            "onResolutionNeeded() 在 resolveEndpointHost 中必须只调用一次",
            1,
            Regex("""\bonResolutionNeeded\(\)""").findAll(resolveInResolver).count(),
        )
        assertFalse("resolveEndpointHost 内部不得新增取消判断", resolveInResolver.contains("isConnectCancelled"))

        val mergeInResolver = normalizeWhitespace(functionBody(resolverCode, "fun mergeBrowserEndpoint("))
        requireOrder(
            mergeInResolver,
            "val address = info.hostAddress",
            "EndpointHosts.candidates(listOf(address)).isNotEmpty()",
            "result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }",
            "if ((needPort || result.portIsFallback) && info.port > 0)",
            "if (result.publicKey == null)",
            "if (result.deviceIdentifier == null)",
            "if (result.sourceVersion == null)",
            "if (result.advertisedFeatures == null)",
            "AirPlayFeatures.parse(txtValue(info,",
        )

        val enrichInResolver = normalizeWhitespace(functionBody(resolverCode, "fun enrichEndpointFromBrowser("))
        requireOrder(
            enrichInResolver,
            "val info = awaitEndpoint(0) ?: return null",
            "val address = info.hostAddress",
            "if ((result.airPlayPort <= 0 || result.portIsFallback) && info.port > 0)",
            "if (result == endpoint) return null",
            "log(",
            "return result",
        )

        // 3) onCarPlayEndpoint 内 airplay-pairing 任务的调用顺序与返回值忽略契约
        val endpointCode = functionBody(controllerCode, "private fun onCarPlayEndpoint(")
        val rawEndpointStart = controller.indexOf("private fun onCarPlayEndpoint(")
        check(rawEndpointStart >= 0) { "找不到原始 onCarPlayEndpoint" }
        val pairingMarker = "Bg.run(\"airplay-pairing\")"
        val pairingAtInRaw = controller.indexOf(pairingMarker, rawEndpointStart)
        check(pairingAtInRaw > rawEndpointStart) { "onCarPlayEndpoint 缺少 Bg.run(\"airplay-pairing\") 任务" }
        assertTrue(
            "airplay-pairing 前必须计算 endpointSelfContained",
            normalizeWhitespace(endpointCode).contains(
                "val endpointSelfContained = endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)",
            ),
        )

        val pairingTailCode = KotlinSource.scan(controller.substring(pairingAtInRaw)).code
        val pairingBlock = bracedBlockAfter(pairingTailCode, "Bg.run(\"\")")
        requireOrder(
            pairingBlock,
            "if (!scope.isActive) {",
            "if (endpointSelfContained) {",
            "awaitCtrlInvite(endpoint, CTRL_INVITE_WAIT_MS) { ms ->",
            "if (!scope.sleep(ms)) throw InterruptedException(",
            "var effectiveEndpoint = resolveEndpointHost(endpoint, source)",
            "pendingEndpointUpdate.takeIf { it.observation === endpoint.observation }?.let { late ->",
            "effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)",
            "if (effectiveEndpoint.airPlayPort > 0 && EndpointHosts.hasUsable(effectiveEndpoint.ipAddresses)) {",
            "ConnectionDiagnostics.timeline.milestone( diagnosticAttempt, ConnectionMilestone.RECEIVER_RESOLVED,",
            "if (!scope.isActive) {",
            "endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },",
        )
        // awaitCtrlInvite 与 resolveEndpointHost 之间不得插入新的 scope.isActive 门禁；
        // sleeper 自身的 scope.sleep 属于端口实参，不算门禁。
        val afterAwaitBeforeResolve =
            normalizeWhitespace(pairingBlock)
                .substringAfter("awaitCtrlInvite(endpoint, CTRL_INVITE_WAIT_MS)")
                .substringBefore("var effectiveEndpoint = resolveEndpointHost(endpoint, source)")
        assertFalse(
            "awaitCtrlInvite 与 resolveEndpointHost 之间不得新增 scope.isActive 检查",
            afterAwaitBeforeResolve.contains("scope.isActive"),
        )
        assertFalse(
            "awaitCtrlInvite 与 resolveEndpointHost 之间不得新增 scope.checkActive()",
            afterAwaitBeforeResolve.contains("scope.checkActive"),
        )
    }
}
