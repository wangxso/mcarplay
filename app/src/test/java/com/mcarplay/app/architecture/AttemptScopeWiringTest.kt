package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AttemptScope 必须接在真实入口与诊断归属之后，不能反向制造一轮新 attempt。 */
class AttemptScopeWiringTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val controllerCode: String by lazy { KotlinSource.scan(controller).code }

    private val router: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/EventCommandRouter.kt").readText()
    }

    private val routerCode: String by lazy { KotlinSource.scan(router).code }

    private val helper: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/AttemptScope.kt").readText()
    }

    private val helperCode: String by lazy { KotlinSource.scan(helper).code }

    private val pipeline: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("session/StreamPipeline.kt").readText()).code
    }

    private val endpointBody: String by lazy { functionBody(controllerCode, "private fun onCarPlayEndpoint(") }

    private val controllerLines: List<String> by lazy { controller.lines() }

    private val legacyToScope =
        mapOf(
            "ownsConnection()" to "scope.isCurrent",
            "wasCancelled()" to "!scope.isActive",
            "!wasCancelled()" to "scope.isActive",
        )

    /** 旧谓词 → 本轮 scope 的逐点迁移快照：按源码顺序登记，定位时动态解析行号。 */
    private data class MigrationSite(
        val legacy: String,
        val expectedText: String,
    )

    private val migrationSites =
        listOf(
            MigrationSite(
                "wasCancelled()",
                "ConnectionDiagnostics.timeline.abandon(diagnosticAttempt, !scope.isActive, DiagnosticCode.WIFI_JOIN_FAILED)",
            ),
            MigrationSite("!wasCancelled()", "if (scope.isActive) {"),
            MigrationSite("wasCancelled()", "if (!scope.isActive) {"),
            MigrationSite("wasCancelled()", "if (!scope.isActive) {"),
            MigrationSite("ownsConnection()", "if (scope.isCurrent) retireSession()"),
            MigrationSite("wasCancelled()", "if (!scope.isActive) {"),
            MigrationSite("wasCancelled()", "if (!scope.isActive) {"),
            MigrationSite("ownsConnection()", "if (scope.isCurrent) retireSession()"),
            MigrationSite("wasCancelled()", "if (!scope.isActive) {"),
            MigrationSite("wasCancelled()", "if (!scope.isActive) {"),
            MigrationSite("ownsConnection()", "if (scope.isCurrent) retireSession()"),
            MigrationSite("wasCancelled()", "if (!scope.isActive) {"),
            // Phase 2g-2：恢复闭包的 10 处 scope 判据迁入 session/StreamPipeline，改为经 ScreenRecoveryPort
            // 的 isScopeActive()/isCurrentClient()/isClientCancelled() 读取，不再登记为控制器迁移点。
            MigrationSite("ownsConnection()", "if (scope.isCurrent && currentClient === client) {"),
            MigrationSite("ownsConnection()", "if (scope.isCurrent && videoSender?.isRunning != true) {"),
            MigrationSite("wasCancelled()", "!scope.isActive,"),
            MigrationSite(
                "!wasCancelled()",
                "if (!airPlayConnected.get() && scope.isActive) DiagnosticCode.AIRPLAY_CONNECT_FAILED else null,",
            ),
            MigrationSite("!wasCancelled()", "if (scope.isActive) {"),
            MigrationSite("!wasCancelled()", "if (pending != null && scope.isActive) {"),
            MigrationSite("ownsConnection()", "targetCurrent = { scope.isCurrent },"),
            MigrationSite("!wasCancelled()", "scopeActive = { scope.isActive },"),
            MigrationSite("wasCancelled()", "cachedRetryCancelled = { !scope.isActive },"),
        )

    /** 在已去注释/字符串的源码中取块体或表达式体函数。 */
    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "找不到函数 $signature" }
        val parametersOpen = source.indexOf('(', start)
        check(parametersOpen >= 0) { "$signature 没有参数列表" }
        var parameterDepth = 0
        var parametersClose = -1
        for (i in parametersOpen until source.length) {
            when (source[i]) {
                '(' -> parameterDepth++
                ')' -> {
                    parameterDepth--
                    if (parameterDepth == 0) {
                        parametersClose = i
                        break
                    }
                }
            }
        }
        check(parametersClose > parametersOpen) { "$signature 的参数列表没有闭合" }
        val open = source.indexOf('{', parametersClose)
        val equals = source.indexOf('=', parametersClose)
        if (equals >= 0 && (open < 0 || equals < open)) {
            val lineEnd = source.indexOf('\n', equals).let { if (it < 0) source.length else it }
            return source.substring(equals, lineEnd).trim()
        }
        check(open > start) { "$signature 没有函数体" }
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("$signature 的函数体没有闭合")
    }

    private fun requireOrder(
        source: String,
        vararg fragments: String,
    ) {
        val normalizedSource = normalizeWhitespace(source)
        var cursor = -1
        fragments.forEach { fragment ->
            val at = normalizedSource.indexOf(normalizeWhitespace(fragment), cursor + 1)
            assertTrue("函数体中找不到或顺序错误：$fragment", at > cursor)
            cursor = at
        }
    }

    /** 生产代码会在 `=` 与构造器之间换行；顺序保护只看 token 先后，不看排版。 */
    private fun normalizeWhitespace(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun entryOwnershipIsCheckedBeforeHandoffStateChanges() {
        requireOrder(
            endpointBody,
            "if (!ReceiverDetectionRuntime.isCurrent(endpoint.observation))",
            "rejectPreAcquired()",
            "if (!handoffOwnership.begin(endpoint.observation)",
            "if (!handoffOwnership.owns(endpoint.observation))",
        )
    }

    @Test
    fun diagnosticAttemptAndAttachPrecedeScopeConstruction() {
        requireOrder(
            endpointBody,
            "val diagnosticAttempt = ConnectionDiagnostics.receiverAttempt()",
            "ReceiverDetectionRuntime.attach(endpoint.observation, diagnosticAttempt)",
            "val scope = LegacyAttemptScope(",
            "fun handoffCancelled(): Boolean = attemptHandoffCancelled(scope)",
        )
    }

    @Test
    fun migrationCountIsSixCurrentAndSixteenActiveIncludingHelper() {
        val current = Regex("""(?<![A-Za-z0-9_.])scope\.isCurrent\b""").findAll(controllerCode).count()
        val negatedActive = Regex("""!scope\.isActive\b""").findAll(controllerCode).count()
        val plainActive = Regex("""(?<![!A-Za-z0-9_.])scope\.isActive\b""").findAll(controllerCode).count()
        assertEquals("SessionController 的 isCurrent 迁移点", 6, current)
        // Phase 2g-2：恢复闭包的 10 处 !scope.isActive 迁入组件，改经 ScreenRecoveryPort.isScopeActive()。
        assertEquals("SessionController 的取反 isActive 迁移点", 10, negatedActive)
        // 5 处原迁移点 + 1 处 ScreenRecoveryPort 装配（scope.isActive → isScopeActive）。
        assertEquals("SessionController 的直查 isActive 迁移点", 6, plainActive)
        assertEquals("SessionController 活跃判断合计", 16, negatedActive + plainActive)

        val helperActive = Regex("""!scope\.isActive\b""").findAll(helperCode).count()
        assertEquals("第33处真实 helper 的活跃判断", 1, helperActive)
        val routerActive = Regex("""!scope\.isActive\b""").findAll(routerCode).count()
        assertEquals("事件 router 的活跃判断", 1, routerActive)
        assertEquals(
            "24 处总计 = 6 current + 16 Controller active + router active + helper active",
            24,
            current + negatedActive + plainActive + routerActive + helperActive,
        )

        assertEquals(
            "scope 构造前入口直查必须保留且只有一处",
            1,
            Regex("""if \(!ReceiverDetectionRuntime\.isCurrent\(endpoint\.observation\)\)""")
                .findAll(endpointBody)
                .count(),
        )
        assertFalse("旧局部 ownsConnection() 不得残留生产调用", controllerCode.contains("ownsConnection("))
        assertFalse("旧局部 wasCancelled() 不得残留生产调用", controllerCode.contains("wasCancelled("))
    }

    @Test
    fun everyControllerCallSiteKeepsItsLegacyPredicateMapping() {
        assertEquals("控制器迁移点（恢复闭包已迁入组件）", 21, migrationSites.size)
        assertEquals("current 点", 6, migrationSites.count { it.legacy == "ownsConnection()" })
        assertEquals("取消点", 10, migrationSites.count { it.legacy == "wasCancelled()" })
        assertEquals("活跃点", 5, migrationSites.count { it.legacy == "!wasCancelled()" })
        var cursor = 0
        migrationSites.forEach { site ->
            val index =
                (cursor until controllerLines.size).firstOrNull {
                    controllerLines[it].trim() == site.expectedText
                }
            assertNotNull("找不到按源码顺序排列的调用点：${site.expectedText}", index)
            val lineNumber = index!! + 1
            val actual = controllerLines[index].trim()
            val expectedNew = legacyToScope.getValue(site.legacy)
            assertTrue("$lineNumber 行缺少 $expectedNew：$actual", actual.contains(expectedNew))
            assertEquals("$lineNumber 行调用点上下文漂移", site.expectedText, actual)
            cursor = index + 1
        }
        requireOrder(endpointBody, *migrationSites.map { it.expectedText }.toTypedArray())
    }

    @Test
    fun handoffHelperIsTheThirtyThirdActiveSiteAndKeepsShortCircuit() {
        val helperBody = functionBody(helperCode, "internal fun attemptHandoffCancelled(")
        requireOrder(helperBody, "!scope.isActive", "||", "!ownsHandoff()")
        assertEquals("helper 只有一处 active 读取", 1, Regex("""!scope\.isActive\b""").findAll(helperBody).count())
        assertFalse("交接归属不得折进 AttemptScope 的 getter", helperCode.contains("handoffOwnership"))
    }

    @Test
    fun independentClientAndVideoGuardsRemainOutsideTheScope() {
        val code = normalizeWhitespace(controllerCode)
        val eventRouterCode = normalizeWhitespace(routerCode)
        val component = normalizeWhitespace(pipeline)

        // Phase 2g-2：恢复闭包迁入 StreamPipeline 后，client 取消/身份、sender 身份与主屏归属仍是
        // scope 活跃之外的独立判据，经 ScreenRecoveryPort 读取而不是折进 AttemptScope。
        assertTrue(
            component.contains(
                "if (!port.isScopeActive() || port.isClientCancelled(client) || !port.isCurrentClient(client))",
            ),
        )
        assertTrue(
            component.contains(
                "if (!port.isScopeActive() || !port.isCurrentClient(client) || sender !== replacement)",
            ),
        )
        assertTrue(component.contains("!port.phoneOwnsScreen(client)"))
        assertEquals(
            "事件通道 guard 必须留在 router，且只保留一处",
            1,
            Regex("""if \(isStaleClient\(\) \|\| !scope\.isActive\) return""")
                .findAll(eventRouterCode)
                .count(),
        )
        // 控制器仍以独立字段/比较承载 client 取消，而不是折进 scope 的 getter。
        assertTrue(code.contains("override fun isClientCancelled(client: AirPlayClient): Boolean = client.cancelled"))
        assertTrue(code.contains("override fun isCurrentClient(client: AirPlayClient): Boolean = currentClient === client"))
    }

    @Test
    fun finallyStillReleasesHandoffBeforeWakeAndFuseAccounting() {
        requireOrder(
            endpointBody,
            "catch (error: Throwable)",
            "} finally {",
            "releaseHandoff()",
            "if (scope.isCurrent && videoSender?.isRunning != true)",
            "ConnectionDiagnostics.timeline.abandon(",
            "!scope.isActive,",
            "if (!airPlayConnected.get() && scope.isActive)",
            "recordAutoReconnectOutcome(",
            "BtSessionGate.sessionActive = false",
            "if (scope.isActive)",
            "pendingEndpointUpdate.takeIf",
            "if (pending != null && scope.isActive)",
            "onCarPlayEndpoint(pending",
        )
    }
}
