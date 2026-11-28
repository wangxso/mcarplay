package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3b S 层源码契约（fail closed，去注释/字符串扫描）：
 *  1. 全量引擎包零引用 `SessionCancelSignal` / `AttemptScope` / `AttemptCancelled`；
 *  2. `SessionCancelSignal` 不反向依赖控制器、WiFi 锁或车型词；
 *  3. A/B 组函数体零残留裸 `Thread.sleep(`，`onSenderLinkClosed` 不得取消会话信号；
 *  4. C 组排除项保留原等待常量且零引用会话信号；
 *  5. 三类中性端口（sleeper / awaitLatch / sleepFor）保持接线。
 */
class Phase3bSourceContractTest {
    private val controllerRaw: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val controllerCode: String by lazy { KotlinSource.scan(controllerRaw).code }

    private val sessionSignalRaw: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/SessionCancelSignal.kt").readText()
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

    /** 解析函数体（不含签名参数表），fail closed。 */
    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "找不到函数：$signature" }
        val paramsOpen = source.indexOf('(', start)
        check(paramsOpen >= 0) { "$signature 缺少参数列表" }
        var depth = 0
        var i = paramsOpen
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        check(depth == 0) { "$signature 参数列表未闭合" }
        val open = source.indexOf('{', i)
        check(open > i) { "$signature 缺少函数体" }
        var braces = 0
        var cursor = open
        while (cursor < source.length) {
            when (source[cursor]) {
                '{' -> braces++
                '}' -> {
                    braces--
                    if (braces == 0) return source.substring(open, cursor + 1)
                }
            }
            cursor++
        }
        error("$signature 函数体未闭合")
    }

    @Test
    fun enginePackagesNeverReferenceSessionCancellationTypes() {
        val forbidden = listOf("SessionCancelSignal", "AttemptScope", "AttemptCancelled", "com.mcarplay.app.session")
        val violations = mutableListOf<String>()
        SourceTree.mainKotlinFiles.forEach { (path, file) ->
            if (SourceTree.topLevelPackage(path) !in enginePackages) return@forEach
            val code = KotlinSource.scan(file.readText()).code
            forbidden.forEach { token ->
                if (code.contains(token)) violations += "$path -> $token"
            }
        }
        assertTrue("引擎包出现编排层取消类型引用：$violations", violations.isEmpty())
    }

    @Test
    fun sessionCancelSignalHasNoControllerWifiLockOrVehicleIdentifiers() {
        val code = KotlinSource.scan(sessionSignalRaw).code
        listOf("SessionController", "MainActivity", "wifiBindLock", "HandoffWifiGuard", "SystemClock", "SCOPE_MISMATCH").forEach {
            assertFalse("SessionCancelSignal 不得引用 $it", code.contains(it))
        }
        val hits = KotlinSource.identifierWords(code) intersect bannedVehicleWords
        assertTrue("SessionCancelSignal 标识符不得出现车型词：$hits", hits.isEmpty())
    }

    @Test
    fun aAndBFunctionsHaveNoRawThreadSleep() {
        val controllerScan = KotlinSource.scan(controllerRaw)
        val controllerCode = controllerScan.code
        val aFunctions =
            listOf(
                "private fun runInitialSetup(",
                "private fun armAutoPlayBehavior(",
                "private fun awaitMediaPcm(",
                "private fun awaitVideoRunning(",
                "private fun watchForPendingStartSession(",
                "private fun scheduleInviteRetry(",
            )
        aFunctions.forEach { signature ->
            val body = functionBody(controllerCode, signature)
            assertFalse("$signature 仍有裸 Thread.sleep(", body.contains("Thread.sleep("))
        }
    }

    @Test
    fun sessionCancelSignalIsWiredToStartAndDisconnectButNotSenderLinkClose() {
        val start = controllerCode.indexOf("sessionCancelSignal.reset()")
        val connect = controllerCode.indexOf("connectCancelled = false")
        val disconnect = controllerCode.indexOf("sessionCancelSignal.cancel()")
        val disconnectGate = controllerCode.lastIndexOf("connectCancelled = true", disconnect)
        assertTrue("startConnection 必须 reset 会话信号", start >= 0)
        assertTrue("reset 必须紧邻新一轮开始", start < connect && connect - start < 200)
        assertTrue("disconnect 必须 cancel 会话信号", disconnect >= 0)
        assertTrue("cancel 必须紧邻终态 disconnect 门", disconnectGate >= 0 && disconnect - disconnectGate < 200)

        val closeBody = functionBody(controllerCode, "fun onSenderLinkClosed(")
        assertFalse("onSenderLinkClosed 不得取消会话信号（B-10/B-5）", closeBody.contains("sessionCancelSignal.cancel()"))
        assertFalse("onSenderLinkClosed 不得 reset 会话信号", closeBody.contains("sessionCancelSignal.reset()"))
    }

    @Test
    fun cGroupExclusionsKeepOriginalWaitsAndDoNotReferenceSessionSignal() {
        val teardown = KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("TeardownCoordination.kt").readText()).code
        assertTrue(
            "TeardownCoordination 必须保留 stopping.await(10, TimeUnit.SECONDS)",
            teardown.contains("stopping.await(10, TimeUnit.SECONDS)"),
        )
        assertFalse("TeardownCoordination 不得引用 SessionCancelSignal", teardown.contains("SessionCancelSignal"))

        val power = KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("service/ScreenPowerController.kt").readText()).code
        assertTrue("ScreenPowerController 必须保留 STARTUP_POLL_MS", power.contains("STARTUP_POLL_MS"))
        assertTrue("ScreenPowerController 必须保留 WAKEUP_SETTLE_MS", power.contains("WAKEUP_SETTLE_MS"))
        assertFalse("ScreenPowerController 不得引用 SessionCancelSignal", power.contains("SessionCancelSignal"))
    }

    @Test
    fun neutralPortsAreWiredInsteadOfSessionTypes() {
        val joiner = KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("net/CarWifiJoiner.kt").readText()).code
        assertTrue("CarWifiHandoffRequest 必须接受中性 sleep 端口", joiner.contains("val sleep: ((Long) -> Unit)? = null"))
        assertTrue(
            "CarWifiHandoffRequest 必须接受中性 awaitLatch 端口",
            joiner.contains("val awaitLatch: ((CountDownLatch, Long) -> Boolean)? = null"),
        )
        assertTrue("Joiner 构造必须有默认 awaitLatch", joiner.contains("private val awaitLatch: (CountDownLatch, Long) -> Boolean"))

        val browser = KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("net/CarPlayServiceBrowser.kt").readText()).code
        assertTrue("CarPlayServiceBrowser 必须有可取消判据", browser.contains("isCancelled: () -> Boolean = { false }"))
        assertTrue("CarPlayServiceBrowser 必须接受中性 sleeper", browser.contains("sleepMillis: (Long) -> Unit = { Thread.sleep(it) }"))

        val ownership = KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("airplay/ScreenOwnership.kt").readText()).code
        assertEquals("三道等待必须全部走 sleepFor", 3, Regex("""scheduling\.sleepFor\(""").findAll(ownership).count())
        assertEquals("ScreenOwnership 不得再有 scheduling.sleep(", 0, Regex("""scheduling\.sleep\(""").findAll(ownership).count())
    }

    @Test
    fun dialogWaitExpiresOnSessionCancelToo() {
        val controllerCode = KotlinSource.scan(controllerRaw).code
        val expiredStart = controllerCode.indexOf("fun expired(): Boolean")
        assertTrue("找不到弹窗过期判据", expiredStart >= 0)
        val expiredEnd = controllerCode.indexOf("// 旧请求可能正为另一张网络展示", expiredStart).let { if (it > expiredStart) it else expiredStart + 400 }
        val expiredBody = controllerCode.substring(expiredStart, minOf(expiredEnd, controllerCode.length))
        assertTrue("弹窗等待必须在会话信号取消时立即过期", expiredBody.contains("sessionCancelSignal.isCancelled"))
    }
}
