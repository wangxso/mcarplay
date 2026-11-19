package com.mcarplay.app.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-D1 源码契约：SessionController 三个收尾入口（disconnect/cancel/start/retire）和真实推流
 * onEnded 的现状结构，由 [KotlinSource] 提取，禁止用全文顺序冒充分支顺序。
 *
 * 这是源码快照断言，不是 JVM 行为测试；真实设备时序仍需台架/实车证据。
 */
class EndAttemptTraceTest {
    private val controller: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()).code
    }

    private val videoSenderSource: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("video/VideoSender.kt").readText()).code
    }

    private val disconnectTailSource: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("DisconnectTail.kt").readText()).code
    }

    /** 去注释/字符串后的指定函数，包含签名和函数体，避免日志文案或注释里的关键词污染断言。 */
    private fun scFunction(name: String): String = normalize(function(controller, name))

    private fun tailFunction(name: String): String = normalize(function(disconnectTailSource, name))

    private fun vsFunction(name: String): String = normalize(function(videoSenderSource, name))

    private fun normalize(source: String): String = source.replace(Regex("\\s+"), " ")

    private fun function(
        source: String,
        name: String,
    ): String {
        val start = requireIndex(source, "fun $name(", "找不到函数 $name")
        val parenOpen = source.indexOf('(', start)
        check(parenOpen >= 0) { "$name 的参数表没有开始" }
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
        check(parens == 0) { "$name 的参数表没有闭合" }
        val open = source.indexOf('{', i)
        check(open > i) { "找不到 $name 的函数体" }
        return balancedFrom(source, open)
    }

    private fun balancedFrom(
        source: String,
        open: Int,
    ): String {
        check(source[open] == '{') { "配平起点不是花括号" }
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
        error("花括号没有闭合")
    }

    private fun lambdaAfter(
        source: String,
        marker: String,
    ): String {
        val markerAt = requireIndex(source, marker, "找不到标记 $marker")
        val open = source.indexOf('{', markerAt + marker.length)
        check(open >= 0) { "$marker 后没有花括号" }
        return normalize(balancedFrom(source, open))
    }

    /** 函数声明头的指定注解，避免误抓远处注解。 */
    private fun hasAnnotation(
        name: String,
        annotation: String,
    ): Boolean = Regex("@$annotation\\s*\\n\\s*fun $name\\(").containsMatchIn(controller)

    private fun requireIndex(
        source: String,
        needle: String,
        message: String,
    ): Int {
        val at = source.indexOf(needle)
        assertTrue("$message（未找到 $needle）", at >= 0)
        return at
    }

    private fun requireOrder(
        source: String,
        first: String,
        second: String,
        message: String,
        from: Int = 0,
    ) {
        val firstAt = source.indexOf(first, from)
        val secondAt = if (firstAt >= 0) source.indexOf(second, firstAt + first.length) else -1
        assertTrue("$message（第一个未找到）", firstAt >= 0)
        assertTrue("$message（第二个未找到）", secondAt >= 0)
        assertTrue(message, firstAt < secondAt)
    }

    private fun requireContains(
        source: String,
        needle: String,
        message: String,
    ) {
        assertTrue(message, source.contains(needle))
    }

    private fun assertContractRejected(
        negative: String,
        needles: List<String>,
        assertion: (String) -> Unit,
    ) {
        needles.forEach { needle -> assertTrue("反例应包含 $needle", negative.contains(needle)) }
        assertFalse("反例应被契约断言拒绝", runCatching { assertion(negative) }.isSuccess)
    }

    @Test
    fun disconnectEntryAndCaptureContract() {
        assertTrue("disconnect 必须持有控制器锁", hasAnnotation("disconnect", "Synchronized"))
        val body = scFunction("disconnect")
        requireOrder(body, "handoffOwnership.blockNewHandoffs()", "connectCancelled = true", "先关交接入口再置取消标志")
        requireOrder(
            body,
            "connectCancelled = true",
            "val autoAttempt = ConnectionDiagnostics.timeline.snapshot()?.id",
            "取消标志先于 attempt 捕获",
        )
        requireOrder(
            body,
            "val autoAttempt = ConnectionDiagnostics.timeline.snapshot()?.id",
            "if (reason == DiagnosticCode.USER_STOP)",
            "attempt 捕获先于用户停止分支",
        )
        requireOrder(body, "if (reason == DiagnosticCode.USER_STOP)", "userStopGeneration++", "用户停止才递增代际")
        requireOrder(body, "userStopGeneration++", "AutoConnectPrefs.recordManualStop(context)", "代际递增先记手动停止")
        requireOrder(body, "AutoConnectPrefs.recordManualStop(context)", "AutoReconnectGuard.clear(context)", "手动停止后清熔断")
        requireOrder(body, "AutoReconnectGuard.clear(context)", "if (disconnectCompletion != null) return", "完成去重在用户停止分支后")
        requireContains(body, "if (disconnectCompletion != null) return", "已有收尾任务时直接返回")
        assertTrue(
            "收尾 latch 只能创建一次",
            Regex("val completed = java\\.util\\.concurrent\\.CountDownLatch\\(1\\)").findAll(body).count() == 1,
        )
        requireOrder(
            body,
            "if (disconnectCompletion != null) return",
            "val completed = java.util.concurrent.CountDownLatch(1)",
            "首次收尾才创建 latch",
        )
        requireOrder(body, "val completed = java.util.concurrent.CountDownLatch(1)", "disconnectCompletion = completed", "latch 先发布")
        requireOrder(body, "disconnectCompletion = completed", "val retiringClient = currentClient", "completion 先发布再捕获资源")
        requireOrder(body, "val retiringClient = currentClient", "val retiringWifi = wifiConnector", "捕获 client 后捕获 WiFi")
        requireOrder(body, "val retiringWifi = wifiConnector", "val retiringVideo = videoSender", "捕获 WiFi 后捕获视频")
        requireOrder(body, "val retiringVideo = videoSender", "val hadProjection = CarPlayService.mediaProjection != null", "捕获视频后记录投影状态")
        requireOrder(
            body,
            "val retiringVideo = videoSender",
            "retireSession(closeClient = false, preserveScreenSocket = true)",
            "捕获在 retire 前完成",
        )
        requireOrder(
            body,
            "retireSession(closeClient = false, preserveScreenSocket = true)",
            "BtSessionGate.sessionActive = true",
            "retire 后保持会话静默直到后台",
        )
        requireOrder(body, "BtSessionGate.sessionActive = true", "ui.phase(null)", "会话静默先于同步 UI 收尾")
        requireOrder(body, "ui.phase(null)", "CarPlayService", "UI 收尾先停服务")
        requireOrder(body, "CarPlayService", "boundCarSsid = null", "停服务后清热点")
        requireOrder(body, "boundCarSsid = null", "disconnectTail.enqueue(", "同步收尾后提交后台清理")
        requireContains(body, "retiringClient?.let { RetiringAirPlayClient(it) }", "快照 client 绑定捕获对象")
        requireContains(body, "stopCapturedVideo = { retiringVideo?.stop() }", "快照视频绑定捕获对象")
        requireContains(body, "releaseCapturedWifi = { retiringWifi?.release() }", "快照 WiFi 绑定捕获对象")
        requireContains(body, "completed = completed", "快照 completion 绑定本轮身份")
    }

    @Test
    fun disconnectBackgroundCleanupContract() {
        val background = tailFunction("execute")
        val innerFinally = lambdaAfter(background, "finally")
        requireContains(
            background,
            "if (snapshot.reason == DiagnosticCode.USER_STOP && snapshot.client?.sessionActive == true)",
            "后台协议退出条件",
        )
        requireOrder(
            background,
            "snapshot.client?.sessionActive == true",
            "snapshot.client.stopLikeIphone()",
            "会话活跃才按 iPhone 顺序退出",
        )
        requireOrder(background, "snapshot.client.stopLikeIphone()", "live.withWifiLock", "stopLikeIphone 失败进入 WiFi 锁")
        requireOrder(
            background,
            "live.withWifiLock",
            "snapshot.releaseCapturedWifi()",
            "WiFi fallback 释放先于 teardown",
        )
        requireOrder(background, "snapshot.client?.teardownSession()", "finally", "teardown 分支后进入收尾")
        requireContains(innerFinally, "runCatching { snapshot.stopCapturedVideo() }", "视频清理在 finally")
        requireOrder(
            innerFinally,
            "runCatching { snapshot.stopCapturedVideo() }",
            "runCatching { snapshot.client?.close() }",
            "视频先关再关 client",
        )
        requireOrder(
            innerFinally,
            "runCatching { snapshot.client?.close() }",
            "runCatching { live.detachBt(",
            "client 关闭先于 BT detach",
        )
        requireOrder(innerFinally, "runCatching { live.detachBt(", "runCatching { live.stopBtServer() }", "BT detach 先于 server stop")
        requireOrder(innerFinally, "runCatching { live.stopBtServer() }", "runCatching { live.stopBtClient() }", "server 先停")
        requireOrder(innerFinally, "runCatching { live.stopBtClient() }", "runCatching { live.finishBtDiagnostics() }", "诊断收尾最后")
        requireOrder(
            innerFinally,
            "runCatching { live.finishBtDiagnostics() }",
            "live.withWifiLock",
            "诊断先于 WiFi 清理",
        )
        requireOrder(
            innerFinally,
            "runCatching { live.stopCtrlAdvertiser() }",
            "runCatching { live.stopEndpointBrowser() }",
            "ctrl 先停",
        )
        requireOrder(
            innerFinally,
            "runCatching { live.stopEndpointBrowser() }",
            "runCatching { snapshot.releaseCapturedWifi() }",
            "浏览器后释放捕获 WiFi",
        )
        requireOrder(
            innerFinally,
            "runCatching { snapshot.releaseCapturedWifi() }",
            "runCatching { live.cleanupShellFallback() }",
            "WiFi 捕获释放先于 shell",
        )
        requireOrder(innerFinally, "runCatching { live.cleanupShellFallback() }", "live.completeOuter(snapshot.completed)", "shell 清理先于完成")
        val completeAdapter = lambdaAfter(controller, "override fun completeOuter(completed: java.util.concurrent.CountDownLatch)")
        val syncBlock = lambdaAfter(completeAdapter, "synchronized(SessionController)")
        val identityBlock = lambdaAfter(syncBlock, "if (disconnectCompletion === completed)")
        requireContains(syncBlock, "BtSessionGate.sessionActive = false", "锁内先解除会话静默")
        requireOrder(
            syncBlock,
            "BtSessionGate.sessionActive = false",
            "if (disconnectCompletion === completed)",
            "先解除静默再看完成身份",
        )
        requireContains(identityBlock, "handoffOwnership.allowNewHandoffs()", "身份匹配后开放交接")
        requireContains(identityBlock, "disconnectCompletion = null", "身份匹配后清 completion")
        requireOrder(identityBlock, "handoffOwnership.allowNewHandoffs()", "disconnectCompletion = null", "仅身份匹配才开放交接并清 completion")
        assertFalse("gate 关闭必须留在完成身份块之前", identityBlock.contains("BtSessionGate.sessionActive = false"))
        assertFalse("countDown 不得在完成身份同步块内", syncBlock.contains("completed.countDown()"))
        requireOrder(completeAdapter, "synchronized(SessionController)", "completed.countDown()", "countDown 在完成同步块之后")
        requireOrder(completeAdapter, "completed.countDown()", "appendLog(", "countDown 后记录完成日志")
    }

    @Test
    fun startConnectionDelaysUserStopGeneration() {
        assertTrue("startConnection 必须持有控制器锁", hasAnnotation("startConnection", "Synchronized"))
        val body = scFunction("startConnection")
        requireOrder(
            body,
            "AutoConnectPrefs.isManualStopCoolingDown(context)",
            "AutoReconnectGuard.block(context, peer)",
            "自动连接冷却与熔断先于收尾捕获",
        )
        requireOrder(body, "AutoReconnectGuard.block(context, peer)", "val stopping = disconnectCompletion", "熔断检查先于收尾捕获")
        requireOrder(body, "val stopping = disconnectCompletion", "val stopGeneration = userStopGeneration", "先捕获收尾再捕获代际")
        val stoppingBranch = lambdaAfter(body, "if (stopping != null)")
        requireContains(stoppingBranch, "Bg.run(", "等待任务已排队")
        requireOrder(stoppingBranch, "stopping.await(10, java.util.concurrent.TimeUnit.SECONDS)", "synchronized(this)", "等待后再拿锁")
        requireOrder(stoppingBranch, "synchronized(this)", "userStopGeneration == stopGeneration", "锁内校验代际")
        requireOrder(
            stoppingBranch,
            "userStopGeneration == stopGeneration",
            "startConnection(context, automatic, automaticPeerAddress)",
            "代际一致才递归",
        )
        requireOrder(stoppingBranch, "startConnection(context, automatic, automaticPeerAddress)", "return", "递归后直接返回")
    }

    @Test
    fun cancelConnectingOverridesPhaseAfterDisconnect() {
        assertTrue("cancelConnecting 必须持有控制器锁", hasAnnotation("cancelConnecting", "Synchronized"))
        val body = scFunction("cancelConnecting")
        requireOrder(body, "wifiPromptBroker.clear()", "disconnect(context)", "清 broker 先于 disconnect")
        requireOrder(body, "disconnect(context)", "ui.phase(t(R.string.sc_cancelled))", "disconnect 后覆盖取消提示")
    }

    @Test
    fun retireSessionIsRoundScopedAndDoesNotDetachBt() {
        assertFalse("retireSession 不应持有控制器锁", hasAnnotation("retireSession", "Synchronized"))
        val body = scFunction("retireSession")
        requireOrder(body, "currentClient?.beginOrderlyStop()", "screenStreamRecovery = null", "begin 先撤恢复")
        requireOrder(body, "screenStreamRecovery = null", "stopAutoPlayWatch()", "恢复先于 watch")
        requireOrder(body, "stopAutoPlayWatch()", "val retiringSender = videoSender", "watch 先捕获 sender")
        requireOrder(body, "val retiringSender = videoSender", "videoSender = null", "先捕获再撤 sender")
        requireOrder(
            body,
            "videoSender = null",
            "if (preserveScreenSocket) retiringSender?.quiesceForOrderlyStop() else retiringSender?.stop()",
            "撤 sender 后再停或静默",
        )
        requireOrder(
            body,
            "if (preserveScreenSocket) retiringSender?.quiesceForOrderlyStop() else retiringSender?.stop()",
            "StreamingWifiLock.release()",
            "视频先停再释放 WiFi",
        )
        requireOrder(body, "StreamingWifiLock.release()", "appContext?.let(ScreenLockMonitor::stop)", "WiFi 后停锁屏监控")
        requireOrder(body, "appContext?.let(ScreenLockMonitor::stop)", "ScreenDimmer.setSessionActive(false)", "监控后收暗幕")
        requireOrder(body, "ScreenDimmer.setSessionActive(false)", "restorePhoneScreen(sleepIfCarSide = false)", "暗幕后恢复物理屏")
        requireOrder(body, "restorePhoneScreen(sleepIfCarSide = false)", "audioSender?.stop()", "屏幕恢复先停音频")
        requireOrder(body, "audioSender?.stop()", "audioSender = null", "音频 sender 置空")
        requireOrder(body, "audioSender = null", "nativeAudioBridge?.stop()", "sender 后停桥")
        requireOrder(body, "nativeAudioBridge?.stop()", "nativeAudioBridge = null", "桥置空")
        requireOrder(body, "nativeAudioBridge = null", "currentSharedSecret = null", "桥后清密钥")
        requireOrder(body, "currentSharedSecret = null", "currentAudioHost = null", "密钥后清 host")
        requireOrder(body, "currentAudioHost = null", "stopFormalCarLife()", "host 后停 CarLife")
        requireOrder(body, "stopFormalCarLife()", "currentClient?.cancelled = true", "CarLife 后取消 client")
        requireOrder(body, "currentClient?.cancelled = true", "if (closeClient) currentClient?.close()", "取消后按条件关闭")
        requireOrder(body, "if (closeClient) currentClient?.close()", "currentClient = null", "关闭后清引用")
        requireOrder(body, "currentClient = null", "BtSessionGate.sessionActive = false", "client 清理后解除静默")
        listOf(
            "detachActiveBtSessions",
            "bluetoothServer?.stop()",
            "bluetoothClient?.stop()",
            "cleanupShellFallbackNetwork()",
            "attributeAudioTeardown()",
            "CarPlayService.stop(context)",
            "CountDownLatch",
        ).forEach { needle ->
            assertFalse("retireSession 不应包含 $needle", body.contains(needle))
        }
    }

    @Test
    fun screenEndedCallbackFiltersStaleSenderBeforeSideEffects() {
        requireOrder(controller, "fun newScreenSender(", "onEnded = onVideoEnded@", "回调挂在 newScreenSender")
        val marker = "onVideoEnded@"
        val markerAt = requireIndex(controller, marker, "找不到 onEnded 回调")
        val open = controller.indexOf('{', markerAt + marker.length)
        check(open >= 0) { "onEnded 回调没有函数体" }
        val body = normalize(balancedFrom(controller, open)).removePrefix("{").trimStart()
        assertTrue(
            "sender 身份守卫必须是回调第一句",
            Regex("^if\\s*\\(\\s*videoSender\\s*!==\\s*sender\\s*\\)").containsMatchIn(body),
        )
        val guardBlock = lambdaAfter(body, "videoSender !== sender")
        requireContains(guardBlock, "return@onVideoEnded", "身份不符立即返回")
        requireOrder(body, "return@onVideoEnded", "lastEnvironmentSampleAt = 0L", "guard 先于副作用")
        requireOrder(body, "lastEnvironmentSampleAt = 0L", "ConnectionDiagnostics.timeline.videoEnded(diagnosticAttempt)", "先清采样再记结束")
        requireOrder(
            body,
            "ConnectionDiagnostics.timeline.videoEnded(diagnosticAttempt)",
            "ReceiverDetectionRuntime.mediaEnded(client.currentEndpoint.observation)",
            "诊断先于探测恢复",
        )
        requireOrder(
            body,
            "ReceiverDetectionRuntime.mediaEnded(client.currentEndpoint.observation)",
            "carLife?.let { it.stop() releaseCarLifeSession(it) }",
            "探测后停 CarLife",
        )
        requireOrder(body, "carLife?.let", "if (carLifeOutput?.sender === sender) carLifeOutput = null", "CarLife 输出身份校验")
        requireOrder(body, "if (carLifeOutput?.sender === sender) carLifeOutput = null", "StreamingWifiLock.release()", "输出清理后释放 WiFi")
        requireOrder(body, "StreamingWifiLock.release()", "appContext?.let(ScreenLockMonitor::stop)", "WiFi 后停锁屏监控")
        requireOrder(body, "appContext?.let(ScreenLockMonitor::stop)", "nativeAudioBridge?.stop()", "监控后停音频桥")
        requireOrder(body, "nativeAudioBridge?.stop()", "nativeAudioBridge = null", "桥置空")
        requireOrder(body, "nativeAudioBridge = null", "bluetoothClient?.armAfterSessionEnd()", "桥后恢复蓝牙探测")
        requireOrder(body, "bluetoothClient?.armAfterSessionEnd()", "BtSessionGate.sessionActive = false", "蓝牙宽限后解除会话静默")
        requireOrder(body, "BtSessionGate.sessionActive = false", "ScreenDimmer.setSessionActive(false)", "静默后收暗幕")
        requireOrder(body, "ScreenDimmer.setSessionActive(false)", "restorePhoneScreen(sleepIfCarSide = true)", "暗幕后恢复物理屏")
        requireOrder(body, "restorePhoneScreen(sleepIfCarSide = true)", "cleanupShellFallbackNetwork()", "屏幕恢复后 shell 清理")
        requireOrder(body, "cleanupShellFallbackNetwork()", "attributeAudioTeardown()", "shell 清理后音频归因")
        requireOrder(body, "attributeAudioTeardown()", "recordAutoReconnectOutcome(", "归因后记录熔断")
        val finalBlock = lambdaAfter(body, "if (recordAutoReconnectOutcome(")
        requireOrder(
            body,
            "recordAutoReconnectOutcome(",
            "disconnect(it, DiagnosticCode.VIDEO_CLOSED)",
            "熔断且 Context 存在才终态收尾",
        )
        requireContains(finalBlock, "disconnect(it, DiagnosticCode.VIDEO_CLOSED)", "终态 disconnect 在熔断块内")
        assertTrue("onEnded 回调只允许一次 disconnect", body.split("disconnect(").size - 1 == 1)
        listOf(
            "retireSession(",
            "bluetoothServer?.stop()",
            "bluetoothClient?.stop()",
            "CarPlayService.stop(context)",
            "CountDownLatch",
            "detachActiveBtSessions",
        ).forEach { needle ->
            assertFalse("普通 onEnded 不应包含 $needle", body.contains(needle))
        }
        listOf("client?.secret", "audioSender", "VideoSender").forEach { needle ->
            assertFalse("普通 onEnded 不应清理 $needle", body.contains(needle))
        }
    }

    @Test
    fun videoSenderDrainFinallyStopsThenNotifiesOnce() {
        requireContains(
            videoSenderSource,
            "private val endedNotified = AtomicBoolean(false)",
            "两条线程共享通知状态",
        )
        val stopBody = vsFunction("stop")
        requireOrder(stopBody, "finalizeOutputHealth()", "stopped.set(true)", "health 先 finalized")
        requireOrder(stopBody, "stopped.set(true)", "running.set(false)", "先 stopped 再 running")
        requireOrder(stopBody, "running.set(false)", "overflowKeyFrameRecovery.stop()", "running false 后停补帧线程")
        requireOrder(stopBody, "overflowKeyFrameRecovery.stop()", "externalQueue.clear()", "补帧线程后清队列")
        requireOrder(stopBody, "externalQueue.clear()", "monitor?.interrupt()", "清队列后中断健康线程")
        requireOrder(stopBody, "monitor?.interrupt()", "runCatching { closing?.close() }", "中断后关 socket")
        requireOrder(stopBody, "runCatching { closing?.close() }", "synchronized(lifecycleLock)", "socket close 先于锁")
        requireOrder(stopBody, "synchronized(lifecycleLock)", "releaseVideoResources(active, codec, surface)", "锁内摘资源后释放")
        assertFalse("stop 本体不应通知 onEnded", stopBody.contains("onEnded"))
        val quiesceBody = vsFunction("quiesceForOrderlyStop")
        requireOrder(quiesceBody, "quiescing.set(true)", "overflowKeyFrameRecovery.stop()", "先静默再停补帧线程")
        requireOrder(quiesceBody, "overflowKeyFrameRecovery.stop()", "externalQueue.clear()", "补帧线程后清队列")
        assertFalse("quiesce 不应关闭 socket", quiesceBody.contains("closing?.close()"))
        assertFalse("quiesce 不应发布 stopped", quiesceBody.contains("stopped.set(true)"))
        assertFalse("quiesce 不应通知 onEnded", quiesceBody.contains("onEnded"))
        val externalBody = vsFunction("drainExternalVideo")
        val externalFinally = lambdaAfter(externalBody, "finally")
        val encoderBody = vsFunction("drainEncoder")
        val encoderFinally = lambdaAfter(encoderBody, "finally")
        requireOrder(externalFinally, "stop()", "endedNotified.compareAndSet(false, true)", "透传 finally 先 stop 再 CAS")
        requireOrder(externalFinally, "endedNotified.compareAndSet(false, true)", "runCatching { onEnded?.invoke() }", "CAS 成功才通知")
        requireOrder(encoderFinally, "stop()", "endedNotified.compareAndSet(false, true)", "编码 finally 先 stop 再 CAS")
        requireOrder(encoderFinally, "endedNotified.compareAndSet(false, true)", "runCatching { onEnded?.invoke() }", "CAS 成功才通知")
    }

    @Test
    fun btDetachHelperUsesSameReasonForServerAndClient() {
        val body = scFunction("detachActiveBtSessions")
        requireOrder(
            body,
            "bluetoothServer?.detachCurrentSession(eaStopReason)",
            "bluetoothClient?.detachCurrentSession(eaStopReason)",
            "server 先 detach",
        )
    }

    @Test
    fun pairingRetireHackDetachesWithoutEaOrLoopStop() {
        val body = scFunction("onCarPlayEndpoint")
        val scope = lambdaAfter(body, "if (fallbackAfterFirstRound.useBtRetireHack)")
        requireOrder(scope, "detachActiveBtSessions(eaStopReason = null)", "client = newClient()", "detach 后重建 client")
        requireOrder(scope, "client = newClient()", "!scope.isActive", "重建后检查 scope")
        requireOrder(scope, "!scope.isActive", "client.close()", "scope 失效才关闭")
        requireOrder(scope, "client.close()", "currentClient = client", "关闭后再接管引用")
        requireOrder(scope, "currentClient = client", "drainPendingEndpoint(client)", "接管后补投端点")
        requireOrder(scope, "drainPendingEndpoint(client)", "keys = client.runPairing()", "补投后再配对")
        requireOrder(scope, "keys = client.runPairing()", "!scope.isActive", "配对后再检查 scope")
        requireOrder(scope, "!scope.isActive", "releaseHandoff()", "失效才释放 handoff")
        requireContains(scope, "detachActiveBtSessions(eaStopReason = null)", "配对退役 reason 为空")
        assertFalse("配对退役不应发 EA", scope.contains("detachActiveBtSessions(eaStopReason = \""))
        listOf("bluetoothServer?.stop()", "bluetoothClient?.stop()").forEach { needle ->
            assertFalse("配对退役不应停 $needle", scope.contains(needle))
        }
    }

    @Test
    fun pendingStartSessionTimeoutDetachesOnlyAfterCas() {
        val body = scFunction("watchForPendingStartSession")
        requireContains(body, "PENDING_START_SESSION_WAIT_MS", "5 分钟观察窗口")
        requireContains(body, "fun stillPending(): Boolean = pendingStartSessionEndpoint.get() === endpoint", "身份谓词")
        requireOrder(body, "if (!connectCancelled &&", "pendingStartSessionEndpoint.compareAndSet(endpoint, null)", "取消、占用和 CAS 条件")
        requireOrder(
            body,
            "pendingStartSessionEndpoint.compareAndSet(endpoint, null)",
            "detachActiveBtSessions(eaStopReason =",
            "CAS 成功才拆链",
        )
        requireContains(body, "detachActiveBtSessions(eaStopReason =", "空端点等待超时 detach")
        assertFalse("空端点等待不应使用配对退役 reason", body.contains("detachActiveBtSessions(eaStopReason = null)"))
    }

    @Test
    fun teardownReasonLiteralsStayPinned() {
        val innerFinally = lambdaAfter(tailFunction("execute"), "finally")
        val detachCall = innerFinally.substringAfter("runCatching { live.detachBt(", "missing").substringBefore(") }")
        assertTrue("disconnect 的 BT detach 必须携带非 null reason 表达式", detachCall.contains("snapshot.reason.name"))
        assertTrue("disconnect 的 BT detach 不得使用配对退役 reason", !detachCall.contains("eaStopReason = null"))
        requireContains(
            controller,
            "override fun detachBt(reason: String) = detachActiveBtSessions(eaStopReason = reason)",
            "reason 透传给 BT 拆链",
        )
        val watchBody = scFunction("watchForPendingStartSession")
        val timeoutCall = watchBody.substringAfter("detachActiveBtSessions(", "missing")
        assertTrue("空端点等待超时必须携带非 null reason 表达式", timeoutCall.contains("eaStopReason = \""))
        assertFalse("空端点等待不应使用配对退役 reason", watchBody.contains("detachActiveBtSessions(eaStopReason = null)"))
    }

    @Test
    fun audioAttributionPreservesClientWatchdogGuard() {
        val bodyTop = scFunction("attributeAudioTeardown")
        val guardScope = lambdaAfter(bodyTop, "currentClient?.let")
        val guardBlock =
            lambdaAfter(
                guardScope,
                "if (client.mib3LinkArmed && !client.mib3ControlSessionDone)",
            )
        requireContains(bodyTop, "val profile = activeAudioProfile ?: return", "无 active profile 直接返回")
        requireOrder(bodyTop, "val profile = activeAudioProfile ?: return", "currentClient?.let { client ->", "profile 先取，client 守卫后判断")
        requireContains(guardScope, "if (client.mib3LinkArmed && !client.mib3ControlSessionDone)", "MIB3 看门狗守卫")
        requireOrder(guardBlock, "activeAudioProfile = null", "activeAudioStartedAt = 0L", "profile 清理后清 start")
        requireContains(guardBlock, "return", "守卫命中直接返回")
        requireOrder(bodyTop, "currentClient?.let { client ->", "if (startedAt == 0L || identity.isBlank()) return", "client 守卫先于无记忆判断")
        requireOrder(bodyTop, "if (startedAt == 0L || identity.isBlank()) return", "if (lived >= AUDIO_PROFILE_PROBE_MS)", "无记忆先于探测判定")
        requireContains(bodyTop, "AudioProfileMemory.recordWorking(ctx, identity, profile.key)", "达到阈值记 working")
        requireContains(bodyTop, "AudioProfileMemory.recordRejected(ctx, identity, profile.key, allKeys)", "未达阈值记 rejected")
    }

    @Test
    fun sourceContractRejectsFabricatedTeardownShapes() {
        val disconnect = scFunction("disconnect")
        val swappedEntry =
            disconnect.replace(
                "handoffOwnership.blockNewHandoffs() connectCancelled = true",
                "connectCancelled = true handoffOwnership.blockNewHandoffs()",
            )
        assertContractRejected(
            swappedEntry,
            listOf("connectCancelled = true", "handoffOwnership.blockNewHandoffs()"),
        ) { source ->
            requireOrder(source, "handoffOwnership.blockNewHandoffs()", "connectCancelled = true", "交接入口先于取消标志")
        }
        val completeAdapter = lambdaAfter(controller, "override fun completeOuter(completed: java.util.concurrent.CountDownLatch)")
        val syncBlock = lambdaAfter(completeAdapter, "synchronized(SessionController)")
        val countDownInsideLock =
            syncBlock.replace(
                "BtSessionGate.sessionActive = false",
                "BtSessionGate.sessionActive = false completed.countDown()",
            )
        assertContractRejected(
            countDownInsideLock,
            listOf("BtSessionGate.sessionActive = false", "completed.countDown()"),
        ) { source ->
            assertTrue("countDown 不得在完成身份同步块内", !source.contains("completed.countDown()"))
        }
        val onEnded = normalize(lambdaAfter(controller, "onEnded = onVideoEnded@"))
        val invertedGuard = onEnded.replace("videoSender !== sender", "videoSender === sender")
        assertContractRejected(
            invertedGuard,
            listOf("videoSender === sender", "return@onVideoEnded"),
        ) { source ->
            requireContains(source, "videoSender !== sender", "sender 身份不符立即返回")
        }
        val externalBody = vsFunction("drainExternalVideo")
        val externalFinally = lambdaAfter(externalBody, "finally")
        val swappedStopAndCas =
            externalFinally.replace(
                "stop() if (endedNotified.compareAndSet(false, true)) runCatching { onEnded?.invoke() }",
                "if (endedNotified.compareAndSet(false, true)) runCatching { onEnded?.invoke() } stop()",
            )
        assertContractRejected(
            swappedStopAndCas,
            listOf("endedNotified.compareAndSet(false, true)", "stop()"),
        ) { source ->
            requireOrder(source, "stop()", "endedNotified.compareAndSet(false, true)", "stop 先于 CAS")
        }
    }
}
