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

    private val teardownCoordinationSource: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("TeardownCoordination.kt").readText()).code
    }

    private val endNotificationSource: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("video/VideoEndNotification.kt").readText()).code
    }

    private val retirementSource: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("RoundRetirement.kt").readText()).code
    }

    private val videoEndedSource: String by lazy {
        KotlinSource.scan(SourceTree.mainKotlinFiles.getValue("VideoEndedHandling.kt").readText()).code
    }

    /** 去注释/字符串后的指定函数，包含签名和函数体，避免日志文案或注释里的关键词污染断言。 */
    private fun scFunction(name: String): String = normalize(function(controller, name))

    private fun scFunctionLast(name: String): String {
        val marker = "fun $name("
        val at = controller.lastIndexOf(marker)
        assertTrue("找不到函数 $name", at >= 0)
        val open = controller.indexOf('{', at + marker.length)
        check(open >= 0) { "$name 的函数体没有开始" }
        return normalize(balancedFrom(controller, open))
    }

    private fun tailFunction(name: String): String = normalize(function(disconnectTailSource, name))

    private fun coordinationFunction(name: String): String = normalize(function(teardownCoordinationSource, name))

    private fun retirementFunction(name: String): String = normalize(function(retirementSource, name))

    private fun videoEndedFunction(name: String): String = normalize(function(videoEndedSource, name))

    private fun vsFunction(name: String): String = normalize(function(videoSenderSource, name))

    private fun notificationFunction(name: String): String = normalize(function(endNotificationSource, name))

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

    private fun lambdaBody(
        source: String,
        parameter: String,
    ): String {
        val markerAt = requireIndex(source, "$parameter = {", "找不到命名参数 $parameter")
        val open = source.indexOf('{', markerAt + parameter.length)
        check(open >= 0) { "$parameter 后没有花括号" }
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

    private fun requireIndex(
        source: String,
        needle: String,
        from: Int,
    ): Int {
        val at = source.indexOf(needle, from)
        check(at >= 0) { "在指定起点后未找到 $needle" }
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

    /** 在指定作用域内提取带显式类型成员的函数体，避免同名匿名成员或全局首个匹配遮蔽。 */
    private fun memberFunction(
        source: String,
        kind: String,
        name: String,
        scope: String,
    ): String {
        val start = requireIndex(scope, "$kind fun $name(", "找不到成员 $name")
        val open = source.indexOf('{', start)
        check(open >= 0) { "成员 $name 没有函数体" }
        return normalize(balancedFrom(source, open))
    }

    /** 在指定作用域内提取带显式类型成员的属性声明和 getter。 */
    private fun memberProperty(
        source: String,
        kind: String,
        name: String,
        scope: String,
    ): String {
        val start = requireIndex(scope, "$kind $name:", "找不到成员 $name")
        val getterAt = requireIndex(scope, "get()", start)
        val getterOpen = requireIndex(source, "{", getterAt)
        val getterClose = requireIndex(source, "}", getterOpen)
        return normalize(source.substring(start, getterClose + 1))
    }

    /** 提取 `RoundRetirement(...)` 完整构造调用；其尾部用原接收者限定同名叶子。 */
    private fun retirementConstructorCall(): String {
        val start = requireIndex(controller, "RoundRetirement(", "找不到 RoundRetirement 构造")
        val parenOpen = requireIndex(controller, "(", start)
        var parens = 0
        var i = parenOpen
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
        check(parens == 0) { "RoundRetirement 构造调用没有闭合" }
        return normalize(controller.substring(start, i + 1))
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
        val completeFunction = coordinationFunction("complete")
        val syncBlock = lambdaAfter(completeFunction, "synchronized(ownerMonitor)")
        val identityBlock = lambdaAfter(syncBlock, "if (state.completion === captured)")
        requireContains(completeAdapter, "teardownCoordination.complete(completed)", "完成端必须接入 TeardownCoordination")
        requireContains(syncBlock, "state.setSessionActive(false)", "锁内先解除会话静默")
        requireOrder(
            syncBlock,
            "state.setSessionActive(false)",
            "if (state.completion === captured)",
            "先解除静默再看完成身份",
        )
        requireContains(identityBlock, "state.allowNewHandoffs()", "身份匹配后开放交接")
        requireContains(identityBlock, "state.completion = null", "身份匹配后清 completion")
        requireOrder(identityBlock, "state.allowNewHandoffs()", "state.completion = null", "仅身份匹配才开放交接并清 completion")
        assertFalse("gate 关闭必须留在完成身份块之前", identityBlock.contains("setSessionActive(false)"))
        assertFalse("countDown 不得在完成身份同步块内", syncBlock.contains("captured.countDown()"))
        requireOrder(completeFunction, "synchronized(ownerMonitor)", "captured.countDown()", "countDown 在完成同步块之后")
        requireContains(completeAdapter, "appendLog(", "countDown 后记录完成日志")
        val deferFunction = coordinationFunction("deferStartIfStopping")
        val stoppingRead = deferFunction.indexOf("val stopping = state.completion ?: return false")
        val generationRead = deferFunction.indexOf("val stopGeneration = state.userStopGeneration")
        val submitCall = deferFunction.indexOf("tasks.submit(")
        assertTrue("defer 必须先读 completion 再读代际", stoppingRead >= 0 && generationRead > stoppingRead && submitCall > generationRead)
        requireContains(deferFunction, "stopping.await(10, TimeUnit.SECONDS)", "真实等待保持 10 秒")
        requireOrder(deferFunction, "stopping.await(10, TimeUnit.SECONDS)", "synchronized(ownerMonitor)", "等待后再取原 monitor")
        requireOrder(deferFunction, "synchronized(ownerMonitor)", "state.userStopGeneration == stopGeneration", "锁内校验代际")
        requireOrder(
            deferFunction,
            "state.userStopGeneration == stopGeneration",
            "retryOriginalEntry()",
            "代际一致才重入原入口",
        )
        val startBody = scFunction("startConnection")
        requireContains(startBody, "teardownCoordination.deferStartIfStopping(", "start 等待必须接入 TeardownCoordination")
        requireContains(
            lambdaBody(startBody, "retryOriginalEntry"),
            "startConnection(context, automatic, automaticPeerAddress)",
            "等待端重入原 startConnection",
        )
        requireContains(
            lambdaBody(startBody, "logTimeout"),
            "appendLog(",
            "超时日志保持在 defer 回调",
        )
        requireOrder(
            startBody,
            "AutoReconnectGuard.block(context, peer)",
            "teardownCoordination.deferStartIfStopping(",
            "自动冷却与熔断先于收尾等待",
        )
        requireOrder(startBody, "teardownCoordination.deferStartIfStopping(", "if (waitingForTeardown)", "defer 返回后立即判断等待")
        requireOrder(startBody, "if (waitingForTeardown)", "return", "等待收尾时直接返回")
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
        requireOrder(
            body,
            "AutoReconnectGuard.block(context, peer)",
            "teardownCoordination.deferStartIfStopping(",
            "熔断检查先于收尾捕获",
        )
        requireContains(
            lambdaBody(body, "retryOriginalEntry"),
            "startConnection(context, automatic, automaticPeerAddress)",
            "等待任务重入原入口",
        )
        requireContains(
            lambdaBody(body, "logTimeout"),
            "appendLog(",
            "等待任务排队",
        )
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
        requireContains(body, "roundRetirement.retire(closeClient, preserveScreenSocket)", "facade 仅委托 retirement")
        val retireBody = retirementFunction("retire")
        requireOrder(retireBody, "slots.client?.let(actions::beginClient)", "slots.clearRecovery()", "begin 先撤恢复")
        requireOrder(retireBody, "slots.clearRecovery()", "actions.stopAutoPlayWatch()", "恢复先于 watch")
        requireOrder(retireBody, "actions.stopAutoPlayWatch()", "val retiringSender = slots.sender", "watch 先捕获 sender")
        requireOrder(retireBody, "val retiringSender = slots.sender", "slots.sender = null", "先捕获再撤 sender")
        requireOrder(
            retireBody,
            "slots.sender = null",
            "if (preserveScreenSocket)",
            "撤 sender 后再静默或停",
        )
        requireOrder(
            retireBody,
            "if (preserveScreenSocket)",
            "actions.releaseWifiLock()",
            "sender 收口后释放 WiFi",
        )
        requireOrder(retireBody, "actions.releaseWifiLock()", "val context = slots.context", "WiFi 后晚读 Context")
        requireOrder(retireBody, "val context = slots.context", "context?.let(actions::stopScreenMonitor)", "Context 后停锁屏监控")
        requireOrder(retireBody, "context?.let(actions::stopScreenMonitor)", "actions.setDimmerInactive()", "监控后收暗幕")
        requireOrder(retireBody, "actions.setDimmerInactive()", "actions.restorePhoneScreen(false)", "暗幕后恢复物理屏")
        requireOrder(retireBody, "actions.restorePhoneScreen(false)", "val retiringAudio = slots.audio", "屏幕恢复后捕获音频")
        requireOrder(retireBody, "val retiringAudio = slots.audio", "retiringAudio?.let(actions::stopAudio)", "音频捕获后 stop")
        requireOrder(retireBody, "retiringAudio?.let(actions::stopAudio)", "slots.audio = null", "音频 sender 置空")
        requireOrder(retireBody, "slots.audio = null", "val retiringBridge = slots.bridge", "sender 后捕获桥")
        requireOrder(retireBody, "val retiringBridge = slots.bridge", "retiringBridge?.let(actions::stopBridge)", "桥捕获后 stop")
        requireOrder(retireBody, "retiringBridge?.let(actions::stopBridge)", "slots.bridge = null", "桥置空")
        requireOrder(retireBody, "slots.bridge = null", "slots.clearSharedSecret()", "桥后清密钥")
        requireOrder(retireBody, "slots.clearSharedSecret()", "slots.clearAudioHost()", "密钥后清 host")
        requireOrder(retireBody, "slots.clearAudioHost()", "actions.stopFormalCarLife()", "host 后停 CarLife")
        requireOrder(retireBody, "actions.stopFormalCarLife()", "slots.client?.let(slots::markCancelled)", "CarLife 后取消 client")
        requireOrder(retireBody, "slots.client?.let(slots::markCancelled)", "if (closeClient)", "取消后按条件关闭")
        requireOrder(retireBody, "if (closeClient)", "slots.client = null", "关闭后清引用")
        requireOrder(retireBody, "slots.client = null", "slots.setSessionInactive()", "client 清理后解除静默")
        listOf(
            "detachActiveBtSessions",
            "bluetoothServer?.stop()",
            "bluetoothClient?.stop()",
            "cleanupShellFallbackNetwork()",
            "attributeAudioTeardown()",
            "CarPlayService.stop(context)",
            "CountDownLatch",
        ).forEach { needle ->
            assertFalse("retireSession 不应包含 $needle", body.contains(needle) || retireBody.contains(needle))
        }
        assertFalse("retire 不应新增锁", retireBody.contains("synchronized"))
        assertFalse("retire 不应新增 catch", retireBody.contains("catch"))
        assertFalse("retire 不应新增 finally", retireBody.contains("finally"))
        assertFalse("retire 不应新增调度", retireBody.contains("Bg.run"))
        assertFalse("retire 不应新增 BT detach", retireBody.contains("detachActiveBtSessions"))
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
        requireContains(controller, "VideoEndedHandling(", "onEnded 收尾 helper 已接入")
        requireOrder(controller, "VideoEndedHandling(", "onEnded = onVideoEnded@", "adapter 先于回调")
        val callback = lambdaAfter(controller, "onEnded = onVideoEnded@").removePrefix("{").trimStart()
        requireContains(callback, "videoEndedHandling.handle(", "onEnded 直接委托 helper")
        requireContains(callback, "override val senderIdentity: () -> VideoSender = { sender }", "延迟读取真实 sender 身份")
        requireContains(callback, "override val carLifeBinding: () -> CarLifeSession? = { carLife }", "延迟读取真实 CarLife 绑定")
        requireContains(
            callback,
            "override val mediaObservation: () -> Any? = { client.currentEndpoint.observation }",
            "延迟读取当前端点观测",
        )
        requireContains(callback, "override val attempt: String = diagnosticAttempt", "透传诊断 attempt")
        requireContains(callback, "override val peerAddress: String? = peerAddress", "透传对端地址")
        val helper = videoEndedFunction("handle")
        requireOrder(helper, "capture.senderIdentity()", "if (slots.currentSender !== sender) return", "先读取身份再守卫")
        requireContains(helper, "if (slots.currentSender !== sender) return", "身份不符立即返回")
        val guardTail = helper.substringAfter("if (slots.currentSender !== sender) return")
        assertFalse("守卫后不得先 return 再执行副作用", guardTail.startsWith("return"))
        requireOrder(helper, "resetEnvironmentSampleAt()", "videoEndedTimeline(capture.attempt)", "先清采样再记结束")
        requireOrder(helper, "videoEndedTimeline(capture.attempt)", "mediaEnded(capture.mediaObservation())", "诊断先于探测恢复")
        requireOrder(helper, "mediaEnded(capture.mediaObservation())", "stopCarLife(carLife)", "探测后停 CarLife")
        requireOrder(helper, "stopCarLife(carLife)", "releaseCarLife(carLife)", "CarLife stop 后释放")
        requireOrder(helper, "releaseCarLife(carLife)", "clearCarLifeOutput()", "释放后再校验清理输出")
        requireOrder(helper, "clearCarLifeOutput()", "releaseWifiLock()", "输出清理后释放 WiFi")
        requireOrder(helper, "releaseWifiLock()", "slots.context?.let(actions::stopScreenMonitor)", "WiFi 后停锁屏监控")
        requireOrder(
            helper,
            "slots.context?.let(actions::stopScreenMonitor)",
            "slots.nativeBridge?.let(actions::stopNativeBridge)",
            "监控后停音频桥",
        )
        requireOrder(helper, "slots.nativeBridge?.let(actions::stopNativeBridge)", "slots.clearNativeBridge()", "桥置空后清 native 桥")
        requireOrder(helper, "slots.clearNativeBridge()", "slots.btClient?.let(actions::armBluetoothAfterSessionEnd)", "桥后恢复蓝牙探测")
        requireOrder(helper, "slots.btClient?.let(actions::armBluetoothAfterSessionEnd)", "slots.setSessionInactive()", "蓝牙宽限后解除会话静默")
        requireOrder(helper, "setSessionInactive()", "setDimmerInactive()", "静默后收暗幕")
        requireOrder(helper, "setDimmerInactive()", "restorePhoneScreen(sleepIfCarSide = true)", "暗幕后恢复物理屏")
        requireOrder(helper, "restorePhoneScreen(sleepIfCarSide = true)", "cleanupShellFallbackNetwork()", "屏幕恢复后 shell 清理")
        requireOrder(helper, "cleanupShellFallbackNetwork()", "attributeAudioTeardown()", "shell 清理后音频归因")
        requireOrder(helper, "attributeAudioTeardown()", "recordAutoReconnectOutcome(", "归因后记录熔断")
        val finalBlock = lambdaAfter(helper, "if (actions.recordAutoReconnectOutcome")
        requireOrder(helper, "recordAutoReconnectOutcome(", "slots.context?.let(actions::disconnect)", "熔断且 Context 存在才终态收尾")
        requireContains(finalBlock, "slots.context?.let(actions::disconnect)", "终态 disconnect 在熔断块内")
        assertTrue("helper 只允许一次 disconnect", helper.split("actions::disconnect").size - 1 == 1)
        listOf(
            "retireSession(",
            "bluetoothServer?.stop()",
            "bluetoothClient?.stop()",
            "CarPlayService.stop(context)",
            "CountDownLatch",
            "detachActiveBtSessions",
        ).forEach { needle ->
            assertFalse("普通 onEnded 不应包含 $needle", callback.contains(needle) || helper.contains(needle))
        }
        listOf("client?.secret", "audioSender").forEach { needle ->
            assertFalse("普通 onEnded 不应清理 $needle", callback.contains(needle) || helper.contains(needle))
        }
    }

    @Test
    fun videoSenderDrainFinallyStopsThenNotifiesOnce() {
        requireContains(
            endNotificationSource,
            "private val endedNotified = AtomicBoolean(false)",
            "每个 sender 通知器共享自己的通知状态",
        )
        val helperBody = notificationFunction("afterDrain")
        requireOrder(helperBody, "stop()", "endedNotified.compareAndSet(false, true)", "helper 先 stop 再 CAS")
        requireOrder(
            helperBody,
            "endedNotified.compareAndSet(false, true)",
            "runCatching { notify() }",
            "CAS 成功才通知",
        )
        requireContains(videoSenderSource, "private val endNotification = VideoEndNotification()", "每个 sender 独立持有通知器")
        requireContains(
            videoSenderSource,
            "endNotification.afterDrain(stop = { stop() }, notify = { onEnded?.invoke() })",
            "两个 finally 都委托同一通知器",
        )
        assertFalse("不得保留旧共享 CAS 字段", videoSenderSource.contains("endedNotified"))
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
        requireOrder(
            externalFinally,
            "endNotification.afterDrain(stop = { stop() }",
            "notify = { onEnded?.invoke() }",
            "透传 finally 委托 stop 闭包与延迟 onEnded 块",
        )
        requireOrder(
            encoderFinally,
            "endNotification.afterDrain(stop = { stop() }",
            "notify = { onEnded?.invoke() }",
            "编码 finally 委托 stop 闭包与延迟 onEnded 块",
        )
        assertFalse("透传 finally 不得直接保留旧 CAS", externalFinally.contains("endedNotified.compareAndSet"))
        assertFalse("编码 finally 不得直接保留旧 CAS", encoderFinally.contains("endedNotified.compareAndSet"))
    }

    @Test
    fun retireSessionAdapterForwardsToOriginalLeaves() {
        val call = retirementConstructorCall()
        val slotScope = call.substringBefore("object : RoundActions")
        val actionScope = call.substringAfter("object : RoundActions")
        val slotClient = memberProperty(source = slotScope, kind = "override var", name = "client", scope = slotScope)
        val slotSender = memberProperty(source = slotScope, kind = "override var", name = "sender", scope = slotScope)
        val slotAudio = memberProperty(source = slotScope, kind = "override var", name = "audio", scope = slotScope)
        val slotBridge = memberProperty(source = slotScope, kind = "override var", name = "bridge", scope = slotScope)
        val slotContext = memberProperty(source = slotScope, kind = "override val", name = "context", scope = slotScope)
        requireContains(slotClient, "get() = currentClient", "client getter 逐次转发")
        requireContains(slotClient, "currentClient = value", "client setter 只写原字段")
        requireContains(slotSender, "get() = videoSender", "sender getter 逐次转发")
        requireContains(slotSender, "videoSender = value", "sender setter 只写原字段")
        requireContains(slotAudio, "get() = audioSender", "audio getter 逐次转发")
        requireContains(slotAudio, "audioSender = value", "audio setter 只写原字段")
        requireContains(slotBridge, "get() = nativeAudioBridge", "bridge getter 逐次转发")
        requireContains(slotBridge, "nativeAudioBridge = value", "bridge setter 只写原字段")
        requireContains(slotContext, "get() = appContext", "context getter 逐次转发")
        requireContains(slotScope, "clearRecovery() { screenStreamRecovery = null", "clear recovery 只写原字段")
        requireContains(slotScope, "clearSharedSecret() { currentSharedSecret = null", "clear secret 只写原字段")
        requireContains(slotScope, "clearAudioHost() { currentAudioHost = null", "clear host 只写原字段")
        val markCancelled = memberFunction(source = slotScope, kind = "", name = "markCancelled", scope = slotScope)
        requireContains(markCancelled, "client.cancelled = true", "markCancelled 只写传入 client")
        val setSessionInactive = memberFunction(source = slotScope, kind = "", name = "setSessionInactive", scope = slotScope)
        requireContains(setSessionInactive, "BtSessionGate.sessionActive = false", "gate 赋值在 slot 适配器")
        assertFalse("setSessionInactive 不应 detach BT", setSessionInactive.contains("detachActiveBtSessions"))
        assertFalse(
            "setSessionInactive 不应停 BT",
            setSessionInactive.contains("bluetoothServer?.stop") || setSessionInactive.contains("bluetoothClient?.stop"),
        )

        val beginClient = memberFunction(source = actionScope, kind = "", name = "beginClient", scope = actionScope)
        requireContains(beginClient, "client.beginOrderlyStop()", "begin 转发原 client 方法")
        val stopAutoPlayWatch = memberFunction(source = actionScope, kind = "", name = "stopAutoPlayWatch", scope = actionScope)
        requireContains(stopAutoPlayWatch, "this@SessionController.stopAutoPlayWatch()", "watch 显式绑定原 SC 接收者")
        val stopSender = memberFunction(source = actionScope, kind = "", name = "stopSender", scope = actionScope)
        requireContains(stopSender, "sender.stop()", "stop 转发捕获 sender")
        val quiesceSender = memberFunction(source = actionScope, kind = "", name = "quiesceSender", scope = actionScope)
        requireContains(quiesceSender, "sender.quiesceForOrderlyStop()", "quiesce 转发捕获 sender")
        val releaseWifiLock = memberFunction(source = actionScope, kind = "", name = "releaseWifiLock", scope = actionScope)
        requireContains(releaseWifiLock, "StreamingWifiLock.release()", "WiFi release 转发原静态入口")
        val stopScreenMonitor = memberFunction(source = actionScope, kind = "", name = "stopScreenMonitor", scope = actionScope)
        requireContains(stopScreenMonitor, "ScreenLockMonitor.stop(context)", "monitor 转发原静态入口")
        val setDimmerInactive = memberFunction(source = actionScope, kind = "", name = "setDimmerInactive", scope = actionScope)
        requireContains(setDimmerInactive, "ScreenDimmer.setSessionActive(false)", "dimmer 转发原静态入口")
        val restorePhoneScreen = memberFunction(source = actionScope, kind = "", name = "restorePhoneScreen", scope = actionScope)
        requireContains(restorePhoneScreen, "this@SessionController.restorePhoneScreen(sleepIfCarSide)", "restore 显式绑定原 SC 接收者")
        val stopAudio = memberFunction(source = actionScope, kind = "", name = "stopAudio", scope = actionScope)
        requireContains(stopAudio, "audio.stop()", "audio 转发捕获对象")
        val stopBridge = memberFunction(source = actionScope, kind = "", name = "stopBridge", scope = actionScope)
        requireContains(stopBridge, "bridge.stop()", "bridge 转发捕获对象")
        val stopFormalCarLife = memberFunction(source = actionScope, kind = "", name = "stopFormalCarLife", scope = actionScope)
        requireContains(stopFormalCarLife, "this@SessionController.stopFormalCarLife()", "CarLife 显式绑定原 SC 接收者")
        val closeClient = memberFunction(source = actionScope, kind = "", name = "closeClient", scope = actionScope)
        requireContains(closeClient, "client.close()", "close 转发捕获 client")
    }

    @Test
    fun retireSessionConstructorHasZeroReadsOrSideEffects() {
        val constructor = retirementConstructorCall()
        listOf("slots.client", "slots.sender", "slots.audio", "slots.bridge", "slots.context").forEach { needle ->
            assertFalse("构造不得预读 $needle", constructor.contains(needle))
        }
        listOf(
            "beginOrderlyStop",
            "stopAutoPlayWatch",
            "stopSender",
            "quiesceForOrderlyStop",
            "releaseWifiLock",
            "ScreenLockMonitor.stop",
            "setSessionInactive",
        ).forEach { needle ->
            assertFalse("构造不得预执行 $needle", constructor.startsWith(needle))
        }
        val facade = scFunction("retireSession")
        assertTrue(
            "facade 只能一行委托",
            Regex("\\{ roundRetirement\\.retire\\(closeClient, preserveScreenSocket\\) \\}").containsMatchIn(facade),
        )
    }

    @Test
    fun retirementRoundScopedWithoutNewConcurrencyGuards() {
        val body = retirementFunction("retire")
        assertFalse("retire 不应新增锁", body.contains("synchronized"))
        assertFalse("retire 不应新增 catch", body.contains("catch"))
        assertFalse("retire 不应新增 finally", body.contains("finally"))
        assertFalse("retire 不应新增调度", body.contains("Bg.run"))
        assertFalse("retire 不应新增 latch", body.contains("CountDownLatch"))
        assertFalse("retire 不应新增 BT detach", body.contains("detachActiveBtSessions"))
        assertFalse("retire 不应新增 identity-CAS", body.contains("compareAndSet"))
    }

    @Test
    fun retireSessionCallsUseSixAuthorizedSites() {
        val facade = scFunction("retireSession")
        requireContains(facade, "roundRetirement.retire(closeClient, preserveScreenSocket)", "facade 委托 retirement")
        val disconnect = scFunction("disconnect")
        requireContains(disconnect, "retireSession(closeClient = false, preserveScreenSocket = true)", "disconnect 使用 false,true")
        val reconnect = scFunction("reconnectOnStreamingInvite")
        requireOrder(reconnect, "streamingDiagnosticAttempt = null", "retireSession()", "invite 默认参数")
        val startBody = scFunction("startConnection")
        val endpointBody = scFunction("onCarPlayEndpoint")
        val pairingScope = lambdaAfter(endpointBody, "val airPlayConnected = AtomicBoolean(false) Bg.run(\"\")")
        requireOrder(pairingScope, "if (scope.isCurrent) retireSession()", "BtSessionGate.sessionActive = true", "pair-start 默认参数")
        requireOrder(pairingScope, "if (scope.isCurrent) retireSession()", "appendLog(\"\")", "pair-cancel 默认参数")
        requireOrder(pairingScope, "if (scope.isCurrent) retireSession()", "releaseHandoff()", "pair-hack 默认参数")
        val recordScope = lambdaAfter(endpointBody, "if (!client.record())")
        requireOrder(recordScope, "if (scope.isCurrent && currentClient === client)", "retireSession()", "record 默认参数")
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
        val bodyTop = scFunctionLast("attributeAudioTeardown")
        val guardBlock = lambdaAfter(bodyTop, "if (client.mib3LinkArmed && !client.mib3ControlSessionDone)")
        requireContains(bodyTop, "val profile = activeAudioProfile ?: return", "无 active profile 直接返回")
        assertTrue("profile 先取，client 守卫后判断", bodyTop.indexOf("val profile = activeAudioProfile") < bodyTop.indexOf("mib3LinkArmed"))
        requireContains(bodyTop, "if (client.mib3LinkArmed && !client.mib3ControlSessionDone)", "MIB3 看门狗守卫")
        requireOrder(guardBlock, "activeAudioProfile = null", "activeAudioStartedAt = 0L", "profile 清理后清 start")
        requireContains(guardBlock, "return", "守卫命中直接返回")
        assertTrue("client 守卫先于无记忆判断", bodyTop.indexOf("mib3LinkArmed") < bodyTop.indexOf("if (startedAt == 0L"))
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
        val completeFunction = coordinationFunction("complete")
        val syncBlock = lambdaAfter(completeFunction, "synchronized(ownerMonitor)")
        val countDownInsideLock =
            syncBlock.replace(
                "state.setSessionActive(false)",
                "state.setSessionActive(false) captured.countDown()",
            )
        assertContractRejected(
            countDownInsideLock,
            listOf("state.setSessionActive(false)", "captured.countDown()"),
        ) { source ->
            assertTrue("countDown 不得在完成身份同步块内", !source.contains("captured.countDown()"))
        }
        val onEndedHelper = videoEndedFunction("handle")
        val invertedGuard = onEndedHelper.replace("slots.currentSender !== sender", "slots.currentSender === sender")
        assertContractRejected(
            invertedGuard,
            listOf("slots.currentSender === sender"),
        ) { source ->
            requireContains(source, "slots.currentSender !== sender", "sender 身份不符立即返回")
        }
        val helperBody = notificationFunction("afterDrain")
        val swappedStopAndCas =
            helperBody.replace(
                "stop() if (endedNotified.compareAndSet(false, true)) runCatching { notify() }",
                "if (endedNotified.compareAndSet(false, true)) runCatching { notify() } stop()",
            )
        assertContractRejected(
            swappedStopAndCas,
            listOf("endedNotified.compareAndSet(false, true)", "stop()"),
        ) { source ->
            requireOrder(source, "stop()", "endedNotified.compareAndSet(false, true)", "stop 先于 CAS")
        }
        val externalBody = vsFunction("drainExternalVideo")
        val externalFinally = lambdaAfter(externalBody, "finally")
        val bypassedNotification =
            externalFinally.replace(
                "endNotification.afterDrain(stop = { stop() }, notify = { onEnded?.invoke() })",
                "stop() if (endedNotified.compareAndSet(false, true)) runCatching { onEnded?.invoke() }",
            )
        assertContractRejected(
            bypassedNotification,
            listOf("endedNotified.compareAndSet(false, true)", "stop()"),
        ) { source ->
            assertTrue("finally 必须委托通知器", source.contains("endNotification.afterDrain(stop = { stop() }"))
        }
    }
}
