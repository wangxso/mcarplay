package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AirPlay 事件通道命令路由的现状契约：`connectEventChannel` 的 `onEvent@{ ... }`
 * 块。断言前置守卫、五个真实 opcode 的关键调用，以及未识别命令的日志形态；
 * 按实际源码记录，不假设任何规划中的新 opcode 行为。
 */
class EventCommandRouterSourceContractTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val router: String by lazy {
        val start = controller.indexOf("connectEventChannel(sessionKey, setup) onEvent@")
        check(start >= 0) { "找不到 connectEventChannel onEvent 块" }
        var depth = 0
        var i = controller.indexOf('{', start)
        check(i > start) { "找不到 onEvent 块的花括号" }
        while (i < controller.length) {
            when (controller[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        return@lazy controller.substring(start, i + 1)
    }

    private fun branch(name: String): String {
        val start = router.indexOf("\"$name\" -> {")
        check(start >= 0) { "事件路由缺少 $name 分支" }
        var depth = 0
        var i = router.indexOf('{', start)
        while (i < router.length) {
            when (router[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        return router.substring(start, i + 1)
    }

    /** 路由块按原始源码配平花括号；单分支再去注释/字符串，避免断言被文案污染。 */
    private fun branchCode(name: String): String = KotlinSource.scan(branch(name)).code

    private fun requireOrder(
        source: String,
        first: String,
        second: String,
        message: String,
    ) {
        val firstAt = source.indexOf(first)
        val secondAt = source.indexOf(second)
        assertTrue("$message（第一个未找到）", firstAt >= 0)
        assertTrue("$message（第二个未找到）", secondAt >= 0)
        assertTrue(message, firstAt < secondAt)
    }

    @Test
    fun routerHasExactlyTheKnownCommandBranches() {
        val branches =
            Regex(""""(hidSendReport|iAPSendMessage|forceKeyFrame|changeModes|requestUI)"\s*->""").findAll(router).count()
        assertEquals("事件路由只允许五个已知 opcode 分支", 5, branches)
        assertTrue("事件路由必须有 else 兜底分支", router.contains("else ->"))
    }

    @Test
    fun routerGuardsAgainstStaleClientsAndCancellation() {
        assertTrue(
            "事件路由必须先核对本轮客户端与取消状态",
            router.contains("if (currentClient !== client || !scope.isActive) return@onEvent"),
        )
    }

    @Test
    fun hidReportsFollowTheDescriptorAndDispatchToInputs() {
        val code = branchCode("hidSendReport")
        assertTrue("HID 必须先查设备描述符", code.contains("setup.hidDescriptors[it]"))
        assertTrue("Consumer 键必须按描述符解析", code.contains("ConsumerKeys::parseDescriptor"))
        assertTrue("非 Consumer 设备必须走方向键处理", code.contains("HidNavigation.handle("))
        assertTrue("触摸必须走 CarLife 触摸映射", code.contains("mapCarLifeTouch("))
        assertTrue("媒体键必须走媒体键分发", code.contains("routeCarMediaKey("))
        assertTrue("触摸映射前必须先判定媒体键/方向键", code.indexOf("HidNavigation.handle(") < code.indexOf("mapCarLifeTouch("))
    }

    @Test
    fun iapEventBytesAreFedOnlyWhenDataIsPresent() {
        val code = branchCode("iAPSendMessage")
        val rawCode = branch("iAPSendMessage")
        assertTrue("iAP 数据必须从 params.data 取", rawCode.contains("params?.get(\"data\") as? ByteArray"))
        assertTrue("无 data 必须记录事件日志", code.contains("appendLog("))
        assertTrue("有 data 必须喂链路引擎", code.contains("client.onIapEventBytes(data)"))
        requireOrder(code, "iapEventMessageCount++", "client.onIapEventBytes(data)", "先计数再喂链路引擎")
    }

    @Test
    fun forceKeyFrameRequestsTheVideoSenderKeyFrame() {
        val code = branchCode("forceKeyFrame")
        assertTrue("关键帧请求必须落到视频发送端", code.contains("sender?.requestKeyFrame()"))
        assertTrue("关键帧请求必须计数", code.contains("forceKeyFrameCount++"))
        assertTrue("关键帧日志必须限流", code.contains("forceKeyFrameCount <= 3 || forceKeyFrameCount % 20 == 0"))
    }

    @Test
    fun changeModesReassertsModesWithoutRequestingAKeyFrame() {
        val code = branchCode("changeModes")
        val rawChangeModes = branch("changeModes")
        assertTrue("changeModes 必须更新模式归属", rawChangeModes.contains("client.modes.onChangeModes(command[\"params\"] as? Map<*, *>)"))
        assertTrue("changeModes 必须回 modesChanged", code.contains("enqueueModesAssert(client"))
        assertTrue("changeModes 不得请求关键帧", code.contains("requestKeyFrame = false"))
        requireOrder(code, "client.modes.onChangeModes", "enqueueModesAssert(client", "先更新归属再回 modesChanged")
    }

    @Test
    fun requestUiCountsDecisionAndModesAssertInOrder() {
        val code = branchCode("requestUI")
        assertTrue("requestUI 必须计数", code.contains("requestUiCount++"))
        assertTrue("requestUI 必须走统一音频决策", code.contains("requestUiAudioDecision("))
        assertTrue("requestUI 必须更新 UI 模式", code.contains("client.modes.onUserRequestedUi(takeAudio = audio.takeMainAudio)"))
        assertTrue("requestUI 的 modesChanged 必须带 requestKeyFrame", code.contains("requestKeyFrame = request.allowed"))
        assertTrue("requestUI 必须保留重建流策略", code.contains("PassatReturnPolicy.rebuildStream("))
        requireOrder(code, "requestUiCount++", "requestUiAudioDecision(", "先计数再做音频决策")
        requireOrder(code, "requestUiAudioDecision(", "client.modes.onUserRequestedUi(", "先做音频决策再更新 UI 模式")
    }

    @Test
    fun unknownCommandsKeepTheirParamsInDiagnostics() {
        val code = router.substringAfter("else ->").trim()
        assertTrue("未识别命令必须记录日志", code.contains("appendLog("))
        assertTrue("日志必须保留命令 type", code.contains("command[\"type\"]"))
        assertTrue("日志必须保留其它参数", code.contains("command.entries"))
        assertTrue("参数内容必须限长", code.contains(".take(600)"))
        assertTrue("单值必须限长", code.contains(".take(240)"))
        assertTrue("未识别命令必须只记录日志", !code.contains("throw") && !code.contains("return"))
    }

    @Test
    fun routerDoesNotAssumePlannedOpcodeAliases() {
        assertFalse(
            "事件路由不应预先假设规划中的别名 opcode",
            router.contains("\"iAPSendEvent\"") || router.contains("\"event\""),
        )
    }
}
