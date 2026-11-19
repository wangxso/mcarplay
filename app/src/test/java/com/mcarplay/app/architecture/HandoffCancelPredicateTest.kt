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
 * 这些路径依赖 Android UI（`Handler`/`Looper`）与会话单例状态，JVM 单测无法直接驱动，
 * 因此按仓库既有做法（`ArchitectureRulesTest`、`LogContractTest`）用源码契约钉住。
 */
class HandoffCancelPredicateTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    /** 取 [name] 的函数体：先配平参数表的圆括号，再配平函数体的花括号。 */
    private fun body(name: String): String {
        val start = controller.indexOf("private fun $name(")
        check(start >= 0) { "找不到函数 $name" }
        // 参数表可能跨行，默认参数里还含 `{ connectCancelled }`：只按圆括号配平。
        var parens = 0
        var i = controller.indexOf('(', start)
        while (i < controller.length) {
            when (controller[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            i++
        }
        check(parens == 0) { "$name 的参数表没有闭合" }
        val open = controller.indexOf('{', i)
        check(open > i) { "找不到 $name 的函数体" }
        var depth = 0
        var j = open
        while (j < controller.length) {
            when (controller[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return controller.substring(open, j + 1)
                }
            }
            j++
        }
        error("$name 的函数体没有闭合")
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
        assertTrue("手动兜底必须透传判据", source.contains("manualJoinCarWifi(wifi, isCancelled)"))
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

    /** 现状快照：autoJoin 静默失败后仍查裸全局标志，不接收交接归属。 */
    @Test
    fun autoJoinCarWifiStillUsesBareConnectCancelled() {
        val source = body("autoJoinCarWifi")
        val code = KotlinSource.scan(source).code
        val bareChecks =
            Regex("""(?<![A-Za-z0-9_.])connectCancelled(?![A-Za-z0-9_])""").findAll(code).count()
        assertTrue("autoJoinCarWifi 应保留两处裸 connectCancelled 现状（快照钉住，不代表修复）", bareChecks == 2)
        assertTrue("autoJoinCarWifi 没有 isCancelled 参数", !source.contains("isCancelled"))
    }

    /** 现状快照：autoJoin 兜底不传谓词，自动入网链路不查交接归属。 */
    @Test
    fun autoJoinFallbacksDoNotReceiveTheCallersPredicate() {
        val source = body("autoJoinCarWifi")
        val code = KotlinSource.scan(source).code
        assertTrue("兜底调用必须保持 legacyJoinCarWifi(wifi, shellReady)", code.contains("legacyJoinCarWifi(wifi, shellReady)"))
        assertTrue("兜底调用必须保持 promptSpecifierFailed(wifi, shellReady)", code.contains("promptSpecifierFailed(wifi, shellReady)"))
        assertFalse("兜底调用不得传 isCancelled（现状未接归属）", code.contains("isCancelled"))
    }

    /** 现状快照：specifier 兜底不查取消标志，快照钉住当前行为。 */
    @Test
    fun specifierQuickFallbackHasNoCancellationCheck() {
        val code = KotlinSource.scan(body("specifierQuickFallback")).code
        assertFalse("specifierQuickFallback 现状不含任何取消检查", code.contains("connectCancelled") || code.contains("isCancelled"))
    }

    /** 现状快照：深度兜底轮询仍查裸全局标志。 */
    @Test
    fun specifierDeepFallbackStillUsesBareConnectCancelled() {
        val code = KotlinSource.scan(body("specifierDeepFallback")).code
        val bareChecks =
            Regex("""(?<![A-Za-z0-9_.])connectCancelled(?![A-Za-z0-9_])""").findAll(code).count()
        assertTrue("specifierDeepFallback 应保留一处裸 connectCancelled 现状", bareChecks == 1)
        assertTrue("specifierDeepFallback 没有 isCancelled 参数", !code.contains("isCancelled"))
    }

    /** 现状快照：配件交接路径必须真正使用 handoffCancelled 判据。 */
    @Test
    fun accessoryHandoffPassesTheHandoffPredicate() {
        val root = controller.substringBefore("private fun joinAccessoryWifi(")
        val code = KotlinSource.scan(root).code
        val directLambda =
            Regex(
                """joinAccessoryWifi\(.*?handoffCancelled\(\)\s*\}\s*\)\s*\{""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(code).count()
        val namedParams = Regex("""isCancelled\s*=\s*\{\s*handoffCancelled\(\)\s*\}""").findAll(code).count()
        assertTrue("joinAccessoryWifi 两处直接调用必须传 handoffCancelled", directLambda == 2)
        assertTrue("交接绑定/确认/手动路径必须至少四处显式传 handoffCancelled", namedParams >= 4)
    }

    /** 现状快照：handoffCancelled 必须同时看全局取消与交接归属。 */
    @Test
    fun handoffCancelledPredicateCoversOwnershipAndCancellation() {
        val predicateLine = controller.lineSequence().first { "fun handoffCancelled(): Boolean" in it }
        assertTrue("handoffCancelled 必须包含 wasCancelled()", predicateLine.contains("wasCancelled()"))
        assertTrue("handoffCancelled 必须包含 handoffOwnership.owns", predicateLine.contains("handoffOwnership.owns"))
        assertTrue("handoffCancelled 必须保留两条件的短路或组合", predicateLine.contains("||"))
    }
}
