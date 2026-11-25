package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S 层（F24/F25）：ScreenOwnership 抽取后的生产接线与边界。定位真实文件/函数，缺失即失败；
 * 不从注释或旧分支兜底。组件不得反向依赖编排层/界面层，也不得出现车型标识符。
 */
class ScreenOwnershipSourceContractTest {
    private val controller: String by lazy { SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText() }
    private val ownership: String by lazy { SourceTree.mainKotlinFiles.getValue("airplay/ScreenOwnership.kt").readText() }
    private val pipeline: String by lazy { SourceTree.mainKotlinFiles.getValue("session/StreamPipeline.kt").readText() }

    private fun code(source: String): String = KotlinSource.scan(source).code

    /** 原文切片：用于断言日志文案与注释证据，避免词法扫描把字符串清空。 */
    private fun rawBlock(
        source: String,
        startMarker: String,
        endMarker: String,
        label: String = startMarker,
    ): String {
        val start = source.indexOf(startMarker)
        check(start >= 0) { "missing $label" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        check(end > start) { "missing end of $label" }
        return source.substring(start, end)
    }

    /** 去注释/去字符串后的函数体（含签名），用于结构顺序断言。 */
    private fun functionBody(
        source: String,
        signature: String,
    ): String {
        val scanned = code(source)
        val at = scanned.indexOf(signature)
        check(at >= 0) { "missing function $signature" }
        val open = scanned.indexOf('{', at + signature.length)
        check(open >= 0) { "missing body for $signature" }
        var depth = 0
        var i = open
        while (i < scanned.length) {
            when (scanned[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return scanned.substring(at, i + 1)
                }
            }
            i++
        }
        error("unbalanced body for $signature")
    }

    @Test
    fun componentIsConstructedOnceForTheWholeController() {
        assertEquals(
            "组件必须是控制器字段（与控制器同寿命）",
            1,
            Regex("""private val screenOwnership = createScreenOwnership\(\)""").findAll(controller).count(),
        )
        assertEquals(
            "控制器内只构造一次组件",
            1,
            Regex("""(?<![A-Za-z])ScreenOwnership\(""").findAll(controller).count(),
        )
        val creator = rawBlock(controller, "private fun createScreenOwnership()", "private fun currentScreenReturnPolicy")
        listOf("clients =", "runtime =", "scheduling =", "log = ::appendLog").forEach {
            assertTrue("端口装配缺少 $it", creator.contains(it))
        }
    }

    @Test
    fun productionExecutorKeepsSingleNamedDaemonThread() {
        val executor = rawBlock(controller, "private val controlActions =", "private val screenOwnership")
        assertTrue("单线程", executor.contains("Executors.newSingleThreadExecutor"))
        assertTrue("线程名保持", executor.contains("\"carplay-control-actions\""))
        assertTrue("daemon", executor.contains("isDaemon = true"))

        val scheduling = rawBlock(controller, "object : ScreenOwnershipScheduling", "log = ::appendLog")
        assertTrue("控制队列注入", scheduling.contains("override fun executeControl(task: () -> Unit) = controlActions.execute(task)"))
        assertTrue("后台任务保持 Bg.run", scheduling.contains("override fun runBackground(") && scheduling.contains("= Bg.run(name, task)"))
        assertTrue("单调 uptime 保持", scheduling.contains("override fun uptimeMillis(): Long = SystemClock.uptimeMillis()"))
        assertTrue("sleep 保持 Thread.sleep", scheduling.contains("override fun sleep(millis: Long) = Thread.sleep(millis)"))

        assertTrue("重申后台任务名保持", ownership.contains("\"modes-reclaim\""))
        assertTrue("3 秒检查任务名保持", ownership.contains("\"takeback-check\""))
        assertTrue("邀请重连任务名保持", ownership.contains("\"streaming-invite-reconnect\""))
    }

    @Test
    fun disconnectPortsUseExplicitControllerMonitor() {
        val recoveryFailed =
            rawBlock(controller, "override fun onRecoveryFailed(client: AirPlayClient)", "override fun onTakeBackDisconnect")
        assertTrue("恢复失败必须在原控制器锁内", recoveryFailed.contains("synchronized(this@SessionController)"))
        assertFalse("不得用匿名对象锁", Regex("""synchronized\(this\)""").containsMatchIn(recoveryFailed))
        assertTrue("锁内先记 SCREEN 失败日志", recoveryFailed.contains("SCREEN: 主屏流恢复失败——结束本轮，等待用户重新连接"))
        assertTrue("锁内原条件成立才断开", recoveryFailed.contains("!connectCancelled && !client.cancelled && currentClient === client"))
        assertTrue(recoveryFailed.contains("disconnect(it, DiagnosticCode.VIDEO_CLOSED)"))

        val takeBack = rawBlock(controller, "override fun onTakeBackDisconnect(client: AirPlayClient)", "override fun reconnectStreaming")
        assertTrue("策略断开同样走原控制器锁", takeBack.contains("synchronized(this@SessionController)"))
        assertFalse("不得用匿名对象锁", Regex("""synchronized\(this\)""").containsMatchIn(takeBack))
        assertTrue(takeBack.contains("disconnect(it, DiagnosticCode.VIDEO_CLOSED)"))
        assertFalse("断开策略日志仍在锁外（组件内）", takeBack.contains("PASSAT:"))

        val creator = rawBlock(controller, "private fun createScreenOwnership()", "private fun currentScreenReturnPolicy")
        assertFalse("端口对象内不得出现裸 synchronized(this)", Regex("""synchronized\(this\)""").containsMatchIn(creator))
    }

    @Test
    fun policiesAreSeparatePortsAndOnlyScreenReturnReadsPreference() {
        val receiver = rawBlock(controller, "override fun receiverPolicy(client: AirPlayClient)", "override fun screenReturnPolicy")
        assertTrue("邀请路径原样读兼容策略", receiver.contains("client.receiverCompatibility()"))
        assertFalse("邀请路径不得读回屏偏好", receiver.contains("currentScreenReturnPolicy") || receiver.contains("CompatibilityPrefs"))

        val screenReturn = rawBlock(controller, "override fun screenReturnPolicy(client: AirPlayClient)", "scheduling =")
        assertTrue("回屏读取点叠加动态偏好", screenReturn.contains("currentScreenReturnPolicy(client)"))
    }

    @Test
    fun controllerKeepsThinDelegatesAndOriginalCallSites() {
        assertTrue(
            "scheduleModesReclaim 薄委托",
            controller.contains(
                "private fun scheduleModesReclaim(client: AirPlayClient) = screenOwnership.scheduleModesReclaim(client)",
            ),
        )
        assertTrue(
            "enqueueModesAssert 薄委托保持全部参数",
            controller.contains(
                ") = screenOwnership.enqueueModesAssert(client, reason, requestKeyFrame, retakeScreen, rebuildStream, takeBackRetried)",
            ),
        )
        val enqueueSignature = rawBlock(controller, "private fun enqueueModesAssert(", ") = screenOwnership.enqueueModesAssert(")
        assertTrue(
            "默认值保持",
            enqueueSignature.contains("retakeScreen: Boolean = false") &&
                enqueueSignature.contains("rebuildStream: Boolean = false") &&
                enqueueSignature.contains("takeBackRetried: Boolean = false"),
        )

        val onCtrlInvite =
            rawBlock(controller, "private fun onCtrlInvite(localAddress: String? = null)", "private fun reconnectOnStreamingInvite")
        assertTrue("推流分支委托组件", onCtrlInvite.contains("screenOwnership.onStreamingInvite(client)"))
        assertTrue("保留 CtrlInviteEvidence 写入", onCtrlInvite.contains("ctrlInviteEvidence ="))
        assertTrue("保留无 client 时的 Wi-Fi 交接", onCtrlInvite.contains("ctrl-invite-wifi-handoff"))
        assertFalse("邀请决策不再留在控制器", onCtrlInvite.contains("StreamingInviteReconnect"))
    }

    @Test
    fun newConnectionResetsOnlyTheTwoExactStateFields() {
        val start = controller.indexOf("fun startConnection(")
        val inviteRetryAt = controller.indexOf("inviteRetryCount = 0", start)
        assertTrue("找不到新连接重置块", inviteRetryAt > start)
        val running = controller.lastIndexOf("if (!alreadyRunning) {", inviteRetryAt)
        val elseAt = controller.indexOf("} else {", running)
        assertTrue(running in start until elseAt)
        val reset = controller.substring(running, elseAt)

        val inviteRetry = reset.indexOf("inviteRetryCount = 0")
        val resetInvite = reset.indexOf("screenOwnership.resetInviteReconnectAt()")
        val forceFrame = reset.indexOf("forceKeyFrameCount = 0")
        val requestUi = reset.indexOf("requestUiCount = 0")
        val resetTakeBack = reset.indexOf("screenOwnership.resetTakeBackFailures()")
        val touch = reset.indexOf("touchLogCount = 0")
        assertTrue("邀请重连时刻在原位置", inviteRetry >= 0 && resetInvite > inviteRetry && forceFrame > resetInvite)
        assertTrue("失败计数在原位置", requestUi >= 0 && resetTakeBack > requestUi && touch > resetTakeBack)
        assertFalse("不得合并为 resetAll", reset.contains("resetAll"))
        assertFalse("重申 epoch 不随新连接清零", reset.contains("modesReclaimEpoch") || reset.contains("resetModesReclaim"))
    }

    @Test
    fun mutableStateMovedOutOfController() {
        val state =
            listOf(
                "private var modesReclaimEpoch",
                "private data class ModesAction",
                "private val modesActionLock",
                "private var pendingModesAction",
                "private var takeBackFailures",
                "private var lastInviteReconnectAtMillis",
                "private val inviteReconnectLock",
            )
        state.forEach {
            assertFalse("状态应迁入 ScreenOwnership：$it", controller.contains(it))
            assertTrue("组件必须持有：$it", code(ownership).contains(it))
        }
    }

    @Test
    fun recoveryClosureMovedIntoPipelineAndInviteReconnectStaysInController() {
        // Phase 2g-2：恢复闭包本体与三态枚举迁入 session/StreamPipeline，控制器不再持有字段或实现。
        assertFalse("控制器不得再持有恢复闭包字段", controller.contains("private var screenStreamRecovery"))
        assertFalse("控制器不得再安装恢复闭包", controller.contains("screenStreamRecovery = recover@{"))
        assertFalse("控制器不得再持有恢复失败判定闭包", controller.contains("fun failure(): ScreenRecoveryResult"))
        assertTrue("恢复闭包字段必须由组件持有", code(pipeline).contains("private var recovery: (() -> ScreenRecoveryResult)"))
        assertTrue("恢复闭包本体必须迁入组件", pipeline.contains("recover@{"))
        assertTrue("恢复失败判定闭包必须迁入组件", pipeline.contains("fun failure(): ScreenRecoveryResult"))
        assertTrue("控制器必须经门面读取恢复闭包", controller.contains("streamPipeline.recoveryOrNull()"))

        val reconnect =
            rawBlock(controller, "private fun reconnectOnStreamingInvite(client: AirPlayClient)", "private var inviteRetryCount")
        assertTrue("退役顺序保持", reconnect.indexOf("streamingDiagnosticAttempt = null") < reconnect.indexOf("retireSession()"))
        assertTrue("端点重交付保持", reconnect.contains("onCarPlayEndpoint(endpoint, \"推流中邀请重连\")"))

        assertFalse("组件不得持有恢复闭包旧字段名", code(ownership).contains("screenStreamRecovery"))
        assertFalse("组件不得复用三态原名", code(ownership).contains("ScreenRecoveryResult"))
    }

    @Test
    fun controllerMapsThreeStateRecoveryToNeutralOutcome() {
        val creator = rawBlock(controller, "private fun createScreenOwnership()", "private fun currentScreenReturnPolicy")
        assertTrue(creator.contains("ScreenRecoveryResult.RESTORED -> RecoveryOutcome.RESTORED"))
        assertTrue(creator.contains("ScreenRecoveryResult.ABORTED -> RecoveryOutcome.ABORTED"))
        assertTrue(creator.contains("ScreenRecoveryResult.FAILED -> RecoveryOutcome.FAILED"))
    }

    @Test
    fun componentDeclaresNeutralPortsAndNoUpperLayers() {
        val componentCode = code(ownership)
        listOf(
            "internal class ScreenOwnership<C : Any>(",
            "internal enum class RecoveryOutcome",
            "internal interface ScreenOwnershipClient<C : Any>",
            "internal interface ScreenOwnershipRuntime<C : Any>",
            "internal interface ScreenOwnershipScheduling",
        ).forEach { assertTrue("缺少中性声明：$it", componentCode.contains(it)) }
        listOf(
            "com.mcarplay.app.SessionController",
            "com.mcarplay.app.MainActivity",
            "com.mcarplay.app.SettingsContentActivity",
            "com.mcarplay.app.SenderApp",
            "com.mcarplay.app.session",
            "com.mcarplay.app.service",
            "com.mcarplay.app.ui",
            "com.mcarplay.app.settings",
            "com.mcarplay.app.update",
            "com.mcarplay.app.legal",
        ).forEach { assertFalse("组件不得反向依赖 $it", componentCode.contains(it)) }
        assertFalse("不得复用 session 的 AttemptScope", componentCode.contains("AttemptScope"))
        assertFalse("不得复用 SessionUiPort", componentCode.contains("SessionUiPort"))
    }

    @Test
    fun componentHasNoVehicleIdentifiers() {
        val banned =
            setOf("passat", "audi", "honda", "cadillac", "diplay", "winplay", "macplay", "chery", "arrizo", "mib3", "id7", "vw", "cinemo")
        val hits = KotlinSource.identifierWords(code(ownership)) intersect banned
        assertTrue("ScreenOwnership 标识符不得出现车型词：$hits", hits.isEmpty())
    }

    @Test
    fun componentKeepsLogTextVerbatim() {
        listOf(
            "EVENT: \$reason → 与待执行的 modesChanged 合并",
            "EVENT: \$reason → 会话已结束或更替，丢弃迟到的 modesChanged",
            "AIRPLAY: modesChanged 资源重申（burst 后 +\${atMs / 1000}s，AADisplay r17 时序）",
            "SESSION: 拿回主屏 \${TAKE_BACK_CHECK_MS}ms 后：车机触控 +",
            "PASSAT: 策略「失败后重建一次」——拿回主屏车机没反应，重建主屏流重试",
            "PASSAT: 策略「连续失败后断开」——连续 \$failures 次拿回主屏车机没反应，结束本轮，请重新连接",
            "CTRL: 推流中收到车机邀请——该车机未登记邀请重连，保持当前会话",
        ).forEach { assertTrue("日志文案必须逐字保留：$it", ownership.contains(it)) }
        assertFalse("控制器不再输出已搬移的 PASSAT 文案", controller.contains("PASSAT: 策略"))
        assertTrue("SCREEN 恢复失败日志仍在控制器锁内", controller.contains("SCREEN: 主屏流恢复失败——结束本轮，等待用户重新连接"))
    }

    @Test
    fun componentKeepsOriginalQueueAndGateOrder() {
        val scanned = code(functionBody(ownership, "fun enqueueModesAssert("))
        val merge = scanned.indexOf("pending.requestKeyFrame = pending.requestKeyFrame || requestKeyFrame")
        val slotClear = scanned.indexOf("if (pendingModesAction === action) pendingModesAction = null")
        val copy = scanned.indexOf("action.copy()")
        val firstGate = scanned.indexOf("runtime.connectCancelled")
        val release = scanned.indexOf("clients.assertModes(client, releaseScreen = true")
        val gap = scanned.indexOf("scheduling.sleep(SCREEN_RETAKE_GAP_MS)")
        val plain = scanned.indexOf("clients.assertModes(client, releaseScreen = false")
        val recovery = scanned.indexOf("runtime.readRecovery()")
        val cancelledGate = scanned.indexOf("clients.isCancelled(client)")
        val reprime = scanned.indexOf("runtime.reprime()")
        val takeBack = scanned.indexOf("scheduleTakeBackCheck(client, reason, takeBackRetried)")
        val localFrame = scanned.indexOf("runtime.requestLocalKeyFrame()")
        listOf(merge, slotClear, copy, firstGate, release, gap, plain, recovery, cancelledGate, reprime, takeBack, localFrame).forEach {
            assertTrue("关键步骤缺失（offset $it）", it >= 0)
        }
        assertTrue("合并 → 出队清槽 → 快照", merge < slotClear && slotClear < copy)
        assertTrue("首门禁先于让出", copy < firstGate && firstGate < release)
        assertTrue("让出 → 200ms → 普通 assert", release < gap && gap < plain)
        assertTrue("恢复读取在普通 assert 之后", plain < recovery)
        assertTrue("恢复/三态之后是独有 cancelled 门禁", recovery < cancelledGate)
        assertTrue("reprime 先于 3 秒检查", reprime < takeBack)
        assertTrue("检查先于本地补帧", takeBack < localFrame)
        assertTrue(
            "3 秒检查沿用首条闭包 takeBackRetried",
            scanned.substring(takeBack, minOf(takeBack + 120, scanned.length)).contains("takeBackRetried"),
        )
        assertTrue("本地补帧保持 fromReceiver=false 语义由控制器提供", controller.contains("requestKeyFrame(fromReceiver = false) == true"))
    }
}
