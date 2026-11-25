package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2g-1 S 层源码契约：`session/StreamPipeline` 承接正式 CarLife 源、租约与输出槽，
 * 控制器只保留薄门面与既有调用点的不变量。
 *
 * 与 Phase 2f 的 [ScreenOwnershipSourceContractTest] 同风格：定位真实文件/声明，缺失即失败；
 * 不从注释或旧分支兜底，禁止扩大架构 allowlist。
 */
class StreamPipelineSourceContractTest {
    private val controller: String by lazy { SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText() }
    private val pipeline: String by lazy { SourceTree.mainKotlinFiles.getValue("session/StreamPipeline.kt").readText() }

    private fun code(source: String): String = KotlinSource.scan(source).code

    @Test
    fun componentOwnsCarLifeSourceLeaseAndOutput() {
        val component = code(pipeline)
        listOf(
            "internal class StreamPipeline(",
            "internal interface CarLifeStreamPorts",
            "internal class CarLifeOutput(",
            "fun claim(",
            "fun release(",
            "fun stopFormal()",
            "fun newFormalSession(",
            "fun publishOutput(",
            "fun clearOutputIf(",
            "fun outputSender()",
            "internal fun handleAccessUnit(",
            "internal fun handleEnded(",
        ).forEach { assertTrue("组件缺少声明：$it", component.contains(it)) }
    }

    @Test
    fun controllerNoLongerOwnsCarLifeState() {
        val body = code(controller)
        listOf(
            "private val carLifeSessions",
            "private class CarLifeOutput",
            "private var carLifeOutput",
            "private var formalCarLifeSession",
            "private var diagnosticCarLifeSession",
            "fun newFormalCarLifeSession(",
            "CarLifeJoinGate(",
        ).forEach { assertFalse("状态应已迁入 StreamPipeline：$it", body.contains(it)) }
    }

    @Test
    fun controllerKeepsFacadesAndExistingCallSites() {
        val body = code(controller)
        listOf(
            "private val currentCarLifeSession: CarLifeSession?",
            "private val streamPipeline = createStreamPipeline()",
            "internal fun isFormalCarLifeActive()",
            "val runningDiagnosticCarLifeSession: CarLifeSession?",
            "internal fun claimCarLifeSession(",
            "internal fun releaseCarLifeSession(",
            "private fun stopFormalCarLife()",
            "this@SessionController.stopFormalCarLife()",
            "currentCarLifeSession?.let",
            "streamPipeline.newFormalSession(",
            "streamPipeline.claim(CarLifeSessionRegistry.Owner.FORMAL",
            "streamPipeline.publishOutput(diagnosticAttempt, sessionVideoSender)",
            "streamPipeline.clearOutputIf(sessionVideoSender)",
            "streamPipeline.outputSender()",
            "streamPipeline.clearOutput()",
            "streamPipeline.stopFormal()",
        ).forEach { assertTrue("控制器缺少门面/调用点：$it", body.contains(it)) }
    }

    @Test
    fun phase2gTwoBoundaryStaysInController() {
        val body = code(controller)
        listOf(
            "fun newScreenSender(",
            "private var screenStreamRecovery",
            "screenStreamRecovery = recover@{",
            "fun failure(): ScreenRecoveryResult",
        ).forEach { assertTrue("2g-2 范围必须留在控制器：$it", body.contains(it)) }
        val component = code(pipeline)
        listOf("newScreenSender", "screenStreamRecovery", "ScreenRecoveryResult").forEach {
            assertFalse("2g-1 不得提前搬移 2g-2 内容：$it", component.contains(it))
        }
    }

    @Test
    fun componentHasNoReverseDependenciesOrUiWrites() {
        val component = code(pipeline)
        listOf(
            "SessionController",
            "MainActivity",
            "SettingsContentActivity",
            "SenderApp",
            "com.mcarplay.app.service",
            "com.mcarplay.app.ui",
            "com.mcarplay.app.settings",
            "com.mcarplay.app.update",
            "com.mcarplay.app.legal",
            "SessionUiPort",
            "AttemptScope",
        ).forEach { assertFalse("组件不得反向依赖 $it", component.contains(it)) }
        listOf("ui.phase", "ui.hint", "ui.ssidChanged", "ui.ask", "ui.dismiss").forEach {
            assertFalse("组件不得写 UI：$it", component.contains(it))
        }
    }

    @Test
    fun leaseLockStaysOneNamedMonitorWithoutBareThis() {
        val component = code(pipeline)
        assertTrue("租约必须沿用 registry 监视器", component.contains("synchronized(registry)"))
        assertFalse("组件不得出现裸 synchronized(this)", Regex("""synchronized\(this\)""").containsMatchIn(component))
    }

    @Test
    fun componentHasNoVehicleSpecificIdentifiers() {
        val banned =
            setOf("passat", "audi", "honda", "cadillac", "diplay", "winplay", "macplay", "chery", "arrizo", "mib3", "id7", "vw", "cinemo")
        val hits = KotlinSource.identifierWords(code(pipeline)) intersect banned
        assertTrue("StreamPipeline 标识符不得出现车型词：$hits", hits.isEmpty())
    }

    @Test
    fun componentKeepsCarLifeLogTemplatesVerbatim() {
        listOf(
            "CARLIFE: 入流门控第 ",
            "补参数集=",
            "CARLIFE: 实际视频尺寸 ",
            "CARLIFE: 会话结束 - ",
            "CARLIFE: 未接入推流的旧会话结束 - ",
            "CARWITH_VIDEO_FORMAT ",
            "CARWITH_AUDIO_FORMAT channel=",
            "CarWith 返回车机（→ ",
        ).forEach { assertTrue("日志模板必须逐字保留：$it", pipeline.contains(it)) }
    }

    @Test
    fun staleEndedGuardPrecedesAnySenderSideEffect() {
        val raw = pipeline
        val guard = raw.indexOf("val out = output.takeIf { formal === session }")
        val stop = raw.indexOf("out.sender.stop()")
        val staleLog = raw.indexOf("CARLIFE: 未接入推流的旧会话结束")
        val release = raw.indexOf("release(session)")
        assertTrue("找不到旧源身份守卫", guard >= 0)
        assertTrue("找不到 sender.stop()", stop >= 0)
        assertTrue("找不到旧源日志", staleLog >= 0)
        assertTrue("身份守卫必须先于 sender.stop()", guard < stop)
        assertTrue("身份守卫必须先于旧源日志", guard < staleLog)
        assertTrue("旧源分支后必须释放自身租约", staleLog < release)
        assertTrue("身份判据必须是 ===（旧源不得命中）", raw.contains("output.takeIf { formal === session }"))
        assertFalse("不得用 takeUnless 反转身份", raw.contains("output.takeUnless"))
        val block = raw.substring(guard, stop)
        assertTrue("sender.stop() 必须位于 out != null 分支内", block.contains("if (out != null)"))
    }

    @Test
    fun controllerComposesPortsWithoutNewLocksOrUi() {
        val creator = rawBlock(controller, "private fun createStreamPipeline()", "private var disconnectCompletion")
        listOf(
            "override fun log(line: String) = appendLog(line)",
            "override fun currentClient(): AirPlayClient? = this@SessionController.currentClient",
            "override fun nativeBridge(): CarLifeAudioBridge? = nativeAudioBridge",
        ).forEach { assertTrue("端口装配缺少 $it", creator.contains(it)) }
        assertEquals(
            "组件只允许构造一次",
            1,
            Regex("""(?<![A-Za-z])StreamPipeline\(""").findAll(code(controller)).count(),
        )
    }

    private fun rawBlock(
        source: String,
        startMarker: String,
        endMarker: String,
    ): String {
        val start = source.indexOf(startMarker)
        check(start >= 0) { "missing $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        check(end > start) { "missing end of $startMarker" }
        return source.substring(start, end)
    }
}
