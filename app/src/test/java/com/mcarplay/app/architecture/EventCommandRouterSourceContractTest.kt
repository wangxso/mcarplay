package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AirPlay 事件通道命令路由的现状契约：router 真实现 + Controller 的每通道适配器。
 *
 * 源码定位保持 fail closed：找不到 router/外层 when/外层 else/注册块时直接失败，
 * 不从注释或旧 Controller 分支中兜底。
 */
class EventCommandRouterSourceContractTest {
    private val controller: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    private val routerSource: String by lazy {
        SourceTree.mainKotlinFiles.getValue("session/EventCommandRouter.kt").readText()
    }

    private val routerStructure: RouterStructure by lazy { RouterStructure(routerSource) }

    private val registration: String by lazy {
        val start = controller.indexOf("val eventCommandRouter =")
        check(start >= 0) { "找不到每通道 EventCommandRouter 构造" }
        val channel = controller.indexOf("client.connectEventChannel(sessionKey, setup)", start)
        check(channel > start) { "找不到 EventCommandRouter 注册" }
        val end = controller.indexOf("\n                            // 像 iPhone", channel)
        check(end > channel) { "找不到事件注册后的 Controller 边界" }
        controller.substring(start, end)
    }

    @Test
    fun routerHasExactlyTheKnownCommandBranches() {
        assertEquals(
            "事件路由只允许六个已知 opcode 与最外层 else",
            listOf("hidSendReport", "requestSiri", "iAPSendMessage", "forceKeyFrame", "changeModes", "requestUI", "else"),
            routerStructure.labels,
        )
        assertEquals("最外层 else 必须只有一个且最后出现", 1, routerStructure.labels.count { it == "else" })
        assertEquals("else 必须是最后一个分支", "else", routerStructure.labels.last())
    }

    @Test
    fun routerGuardsAgainstStaleClientsAndCancellation() {
        val guard = "if (isStaleClient() || !scope.isActive) return"
        val whenAt = routerSource.indexOf("when (command[\"type\"])")
        val guardAt = routerSource.indexOf(guard)
        assertTrue("router 必须保留单次前置守卫", guardAt >= 0)
        assertTrue("守卫必须先于 when/command 读取", guardAt < whenAt)
        assertTrue("Controller 必须用本轮 client 的身份比较接线", registration.contains("isStaleClient = { currentClient !== client }"))
        assertTrue("Controller 必须传入本轮 scope", registration.contains("scope = scope"))
        assertEquals(
            "注册必须只委托一次 router",
            1,
            Regex("""eventCommandRouter\.onCommand\(command\)""").findAll(registration).count(),
        )
        assertFalse("不得把旧 onEvent guard 留在 Controller", registration.contains("return@onEvent"))
    }

    @Test
    fun hidReportsFollowTheDescriptorAndDispatchToInputs() {
        val code = routerStructure.branchCode("hidSendReport")
        assertTrue("HID 必须先查设备描述符", code.contains("setup.hidDescriptors[it]"))
        assertTrue("Consumer 键必须按描述符解析", code.contains("ConsumerKeys::parseDescriptor"))
        assertTrue("非 Consumer 设备必须走导航输入缝", code.contains("inputs.handleNavigation("))
        assertTrue("触摸必须走 CarLife 触摸映射", code.contains("mapCarLifeTouch("))
        assertTrue("媒体键必须走媒体键分发", code.contains("output.routeCarMediaKey("))
        assertTrue("触摸映射前必须先判定媒体键/方向键", code.indexOf("inputs.handleNavigation(") < code.indexOf("mapCarLifeTouch("))

        val allRouterCode = KotlinSource.scan(routerSource).code
        assertTrue("默认导航开关仍读旧全局开关", allRouterCode.contains("HidNavigation.rawLogEnabled"))
        assertTrue("默认导航处理仍调用旧全局 helper", allRouterCode.contains("HidNavigation.handle("))
        assertTrue("默认触摸格式仍调用旧 helper", allRouterCode.contains("TouchReports.formatFromDescriptor("))
        assertTrue("默认触摸解析仍调用旧 helper", allRouterCode.contains("TouchReports.parse("))
        assertTrue("Controller 媒体出口仍接旧方法", registration.contains("routeCarMediaKey(keyCode, label)"))
        assertTrue("Controller 导航出口仍接旧方法", registration.contains("routeCarNavigationKey(keyCode)"))
        assertTrue("Controller 语音出口仍接 Siri 键回调", registration.contains("onSiriButtonPressed()"))
        assertTrue("触摸目标必须捕获 formal CarLife", registration.contains("currentCarLifeSession?.let"))
        assertTrue("触摸必须发往捕获实例", registration.contains("carLife.sendTouch(x, y, down)"))
        assertTrue("触摸成功仍读动态 endpoint", registration.contains("ReceiverDetectionRuntime.touch(client.currentEndpoint.observation)"))
    }

    @Test
    fun iapEventBytesAreFedOnlyWhenDataIsPresent() {
        val code = routerStructure.branchCode("iAPSendMessage")
        val raw = routerStructure.branch("iAPSendMessage")
        assertTrue("iAP 数据必须从 params.data 取", raw.contains("params?.get(\"data\") as? ByteArray"))
        assertTrue("无 data 必须记录事件日志", code.contains("output.appendLog("))
        assertTrue("有 data 必须喂链路引擎", code.contains("client.onIapEventBytes(data)"))
        requireOrder(code, "counters.iapEventMessageCount++", "client.onIapEventBytes(data)", "先计数再喂链路引擎")
        assertTrue("Controller 仍把本轮 client 接到 iAP 端口", registration.contains("client.onIapEventBytes(data)"))
    }

    @Test
    fun forceKeyFrameRequestsTheVideoSenderKeyFrame() {
        val code = routerStructure.branchCode("forceKeyFrame")
        assertTrue("关键帧请求必须落到视频发送端", code.contains("sender?.requestKeyFrame()"))
        assertTrue("关键帧请求必须计数", code.contains("counters.forceKeyFrameCount++"))
        assertTrue(
            "关键帧日志必须限流",
            code.contains("counters.forceKeyFrameCount <= 3 || counters.forceKeyFrameCount % 20 == 0"),
        )
        assertTrue("Controller 当场读取全局 videoSender", registration.contains("videoSender?.let"))
        assertTrue("Controller 包装请求仍使用默认 receiver 参数", registration.contains("sender.requestKeyFrame()"))
        assertTrue("sender 的 gap getter 保持活读", registration.contains("sender.lastReceiverRequestIdrGapMs"))
        assertTrue("sender 的透传 getter 保持活读", registration.contains("sender.isExternalPassthrough"))
        assertTrue("sender 的转码 getter 保持活读", registration.contains("sender.isTranscoding"))
    }

    @Test
    fun changeModesReassertsModesWithoutRequestingAKeyFrame() {
        val code = routerStructure.branchCode("changeModes")
        val raw = routerStructure.branch("changeModes")
        assertTrue("changeModes 必须更新模式归属", raw.contains("client.modes.onChangeModes(command[\"params\"] as? Map<*, *>)"))
        assertTrue("changeModes 必须回 modesChanged", code.contains("output.enqueueModesAssert("))
        assertTrue("changeModes 不得请求关键帧", code.contains("requestKeyFrame = false"))
        requireOrder(code, "client.modes.onChangeModes", "output.enqueueModesAssert(", "先更新归属再回 modesChanged")
        requireOrder(code, "output.enqueueModesAssert(", "client.modes.consumeAudioClaimRetry()", "先入队再消费 pending claim")
        assertTrue("Controller 适配器仍把同一 client 交给模式队列", registration.contains("this@SessionController.enqueueModesAssert("))
        assertTrue("Controller 仍原样使用 Bg.run", registration.contains("Bg.run(name, task)"))
        assertTrue("Controller 仍接本轮回调", registration.contains("retryAudioAfterClaimReturned(client)"))
    }

    @Test
    fun requestUiCountsDecisionAndModesAssertInOrder() {
        val code = routerStructure.branchCode("requestUI")
        assertTrue("requestUI 必须计数", code.contains("counters.requestUiCount++"))
        assertTrue("requestUI 必须走统一音频决策", code.contains("requestUiAudioDecision("))
        assertTrue("requestUI 必须更新 UI 模式", code.contains("client.modes.onUserRequestedUi(takeAudio = audio.takeMainAudio)"))
        assertTrue("requestUI 的 modesChanged 必须带 requestKeyFrame", code.contains("requestKeyFrame = request.allowed"))
        assertTrue("requestUI 必须保留中性重建策略调用", code.contains("resolveScreenReturnRebuild(request.rebuildStream)"))
        assertTrue("requestUI 必须保留 retake", code.contains("retakeScreen = request.retake"))
        requireOrder(code, "counters.requestUiCount++", "requestUiAudioDecision(", "先计数再做音频决策")
        requireOrder(code, "requestUiAudioDecision(", "client.modes.onUserRequestedUi(", "先做音频决策再更新 UI 模式")
        requireOrder(code, "client.modes.onUserRequestedUi(", "output.enqueueModesAssert(", "先更新 UI 模式再入队")
        requireOrder(code, "output.enqueueModesAssert(", "client.modes.consumeAudioClaimRetry()", "入队后再消费 pending claim")
        assertTrue(
            "Controller 仍在适配器原位置执行中性回屏策略表达式",
            registration.contains("ScreenReturnPolicy.rebuildStream(currentScreenReturnPolicy(client).screenReturn, requested)"),
        )
    }

    @Test
    fun unknownCommandsKeepTheirParamsInDiagnostics() {
        val code = routerStructure.outerElseCode
        val raw = routerStructure.outerElse
        assertTrue("未识别命令必须记录日志", code.contains("output.appendLog("))
        assertTrue("日志必须保留命令 type", raw.contains("command[\"type\"]"))
        assertTrue("日志必须保留其它参数", raw.contains("command.entries"))
        assertTrue("参数 key 过滤必须保持", raw.contains("it.key?.toString() != \"type\""))
        assertTrue("ByteArray 必须保持连续小写 hex", raw.contains("ByteArray -> value.joinToString(\"\") { \"%02x\".format(it) }"))
        assertTrue("参数内容必须限长", raw.contains(".take(600)"))
        assertTrue("单值必须限长", raw.contains(".take(240)"))
        assertTrue("未识别命令必须只记录日志", !code.contains("throw") && !code.contains("return"))
        assertFalse("未识别命令不得调用业务端口", code.contains("output.routeCarMediaKey("))
    }

    @Test
    fun routerDoesNotAssumePlannedOpcodeAliases() {
        assertFalse(
            "事件路由不应预先假设规划中的别名 opcode",
            routerSource.contains("\"iAPSendEvent\"") || routerSource.contains("\"event\""),
        )
        assertFalse(
            "Controller 注册也不得引入别名 opcode",
            registration.contains("\"iAPSendEvent\"") || registration.contains("\"event\""),
        )
    }

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

    private class RouterStructure(
        source: String,
    ) {
        private val mask = codeMask(source)
        private val whenMatch =
            Regex("""when\s*\(\s*command\["type"\]\s*\)\s*\{""")
                .find(source)
                ?: error("找不到最外层 when (command[\"type\"])")
        private val whenOpen = mask.indexOf('{', whenMatch.range.first)
        private val whenClose = matchingBrace(mask, whenOpen)
        private val branchSpans = parseBranches(source, mask, whenOpen + 1, whenClose)

        val labels: List<String> = branchSpans.map { it.label }
        val outerElse: String =
            branchSpans.singleOrNull { it.label == "else" }?.raw
                ?: error("找不到最外层 else 分支")
        val outerElseCode: String = KotlinSource.scan(outerElse).code

        fun branch(name: String): String =
            branchSpans.singleOrNull { it.label == name }?.raw
                ?: error("事件路由缺少 $name 分支")

        fun branchCode(name: String): String = KotlinSource.scan(branch(name)).code
    }

    private data class Branch(
        val label: String,
        val raw: String,
    )

    private companion object {
        private val knownLabels =
            setOf(
                "hidSendReport",
                "requestSiri",
                "iAPSendMessage",
                "forceKeyFrame",
                "changeModes",
                "requestUI",
                "else",
            )

        private fun parseBranches(
            source: String,
            mask: String,
            bodyStart: Int,
            bodyEnd: Int,
        ): List<Branch> {
            val branches = ArrayList<Branch>()
            var pos = bodyStart
            while (true) {
                pos = skipWhitespace(source, pos, bodyEnd)
                if (pos >= bodyEnd) break
                val start = pos
                val arrow = mask.indexOf("->", pos)
                check(arrow in pos until bodyEnd) { "when 分支缺少 ->（offset $pos）" }
                val labelText = source.substring(start, arrow).trim()
                val label =
                    if (labelText == "else") {
                        "else"
                    } else {
                        Regex("""^"([^"]+)"$""").matchEntire(labelText)?.groupValues?.get(1)
                            ?: error("无法识别 when 分支标签：$labelText")
                    }
                check(label in knownLabels) { "发现未知 opcode 分支：$label" }
                val open = skipWhitespace(mask, arrow + 2, bodyEnd)
                if (mask.getOrNull(open) == '{') {
                    val close = matchingBrace(mask, open)
                    check(close < bodyEnd) { "when 分支 $label 越界" }
                    branches += Branch(label, source.substring(start, close + 1))
                    pos = close + 1
                } else {
                    check(label == "else") { "when 分支 $label 不是块体" }
                    branches += Branch(label, source.substring(start, bodyEnd))
                    pos = bodyEnd
                }
                if (label == "else") {
                    break
                }
            }
            check(branches.isNotEmpty()) { "when 没有分支" }
            return branches
        }

        private fun matchingBrace(
            mask: String,
            open: Int,
        ): Int {
            check(mask.getOrNull(open) == '{') { "不是左花括号（offset $open）" }
            var depth = 0
            for (i in open until mask.length) {
                when (mask[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            error("花括号没有闭合（offset $open）")
        }

        private fun skipWhitespace(
            mask: String,
            start: Int,
            end: Int,
        ): Int {
            var pos = start
            while (pos < end && mask[pos].isWhitespace()) pos++
            return pos
        }

        /** 保留原始长度；注释与字符串内容/引号替换为空格，花括号定位不受干扰。 */
        private fun codeMask(source: String): String {
            val chars = source.toCharArray()
            var i = 0
            while (i < chars.size) {
                when {
                    source.startsWith("//", i) -> {
                        while (i < chars.size && chars[i] != '\n') {
                            chars[i] = ' '
                            i++
                        }
                    }

                    source.startsWith("/*", i) -> {
                        var depth = 0
                        while (i < chars.size) {
                            if (source.startsWith("/*", i)) {
                                depth++
                                chars[i] = ' '
                                chars[i + 1] = ' '
                                i += 2
                            } else if (source.startsWith("*/", i)) {
                                chars[i] = ' '
                                chars[i + 1] = ' '
                                i += 2
                                depth--
                                if (depth == 0) break
                            } else {
                                if (chars[i] != '\n') chars[i] = ' '
                                i++
                            }
                        }
                    }

                    source.startsWith("\"\"\"", i) -> {
                        chars[i] = ' '
                        chars[i + 1] = ' '
                        chars[i + 2] = ' '
                        i += 3
                        while (i < chars.size) {
                            if (source.startsWith("\"\"\"", i)) {
                                chars[i] = ' '
                                chars[i + 1] = ' '
                                chars[i + 2] = ' '
                                i += 3
                                break
                            }
                            chars[i] = ' '
                            i++
                        }
                    }

                    chars[i] == '"' -> {
                        chars[i] = ' '
                        i++
                        while (i < chars.size) {
                            if (chars[i] == '\\' && i + 1 < chars.size) {
                                chars[i] = ' '
                                chars[i + 1] = ' '
                                i += 2
                            } else if (chars[i] == '"') {
                                chars[i] = ' '
                                i++
                                break
                            } else {
                                chars[i] = ' '
                                i++
                            }
                        }
                    }

                    chars[i] == '\'' -> {
                        chars[i] = ' '
                        i++
                        while (i < chars.size) {
                            if (chars[i] == '\\' && i + 1 < chars.size) {
                                chars[i] = ' '
                                chars[i + 1] = ' '
                                i += 2
                            } else if (chars[i] == '\'') {
                                chars[i] = ' '
                                i++
                                break
                            } else {
                                chars[i] = ' '
                                i++
                            }
                        }
                    }

                    else -> i++
                }
            }
            return String(chars)
        }
    }
}
