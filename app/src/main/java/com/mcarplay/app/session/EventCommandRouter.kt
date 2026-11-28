package com.mcarplay.app.session

import android.view.KeyEvent
import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.airplay.ModeArbiter
import com.mcarplay.app.carlife.mapCarLifeTouch
import com.mcarplay.app.input.ConsumerKeys
import com.mcarplay.app.input.HidNavigation
import com.mcarplay.app.input.MediaKeyEdge
import com.mcarplay.app.input.SiriButtonEdge
import com.mcarplay.app.input.TouchReports
import com.mcarplay.app.requestUiAudioDecision

/**
 * AirPlay 事件通道上的命令路由。
 *
 * 一条事件通道只创建一个实例；[mediaKeyEdges] 随通道重建，不跨连接复用。动态资源通过窄端口
 * 在每条命令的原执行点读取，构造时不冻结 sender、CarLife、音频或开关状态。
 */
internal class EventCommandRouter(
    private val scope: AttemptScope,
    private val setup: AirPlayClient.SetupInfo,
    private val isStaleClient: () -> Boolean,
    private val client: EventClient,
    private val counters: EventCounters,
    private val nowUptimeMillis: () -> Long,
    private val output: EventOutput,
    private val currentVideoSender: () -> KeyFrameSender?,
    private val currentTouchTarget: () -> TouchTarget?,
    private val audioEnabled: () -> Boolean,
    private val audioStreamRunning: () -> Boolean,
    private val resolveScreenReturnRebuild: (Boolean) -> Boolean,
    private val inputs: EventInputs = DefaultEventInputs,
) {
    /** 每台媒体键 HID 设备一个边沿判定器；随本条 event 通道重建，旧轮按住状态不残留。 */
    private val mediaKeyEdges = HashMap<String, MediaKeyEdge>()
    private val loggedTouchRangeUuids = HashSet<String>()
    private val voiceButton = SiriButtonEdge()
    private val consoleBackMasks = setup.hidDescriptors.mapValues { (_, descriptor) -> HidNavigation.consoleBackMask(descriptor) }
    private val pressedBackDevices = HashSet<String>()

    fun onCommand(command: Map<*, *>) {
        if (isStaleClient() || !scope.isActive) return
        when (command["type"]) {
            "hidSendReport" -> {
                val uuid = command["uuid"]?.toString()
                val report = command["hidReport"] as? ByteArray
                // 车机媒体键（Consumer Control）走 hidSendReport 通路，
                // 判定**以设备描述符为准**（不按 uuid、不按车机型号写死）：
                // 描述符声明了 Consumer 页(0x0C)的设备才当媒体键，报告字节是
                // Usage 数组下标（HID Array 语义）。触摸/旋钮/电话设备的描述符
                // 分别是 Digitizer/GenericDesktop/Telephony 页，一律不走这里——
                // DiPlay 的 knob(4B) 与 telephony(1B) 报告长度与媒体键重叠，
                // 只靠长度会误判（如 telephony usage 0xB3 会误发「快进」）。
                val descriptor = uuid?.let { setup.hidDescriptors[it] }
                val keyMap =
                    descriptor?.let(ConsumerKeys::parseDescriptor)
                // 本帧处于按下状态的 Consumer Usage；null = 不是媒体键设备。
                // 位图（Variable，`81 02`）与 Array（`81 00`）都按描述符展开——
                // 只读首字节当下标会让位图车机的「下一首 0x04」解码成无键。
                val mediaUsages =
                    when {
                        report == null -> null
                        // 有描述符：只有 Consumer 设备才解析（非 Consumer → null）
                        descriptor != null -> keyMap?.usagesFor(report)
                        // 描述符缺失（未知车机）：保守回退——仅 1 字节报告，
                        // 值当 Usage ID 直读
                        report.size == 1 -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                        else -> null
                    }
                val backMask = consoleBackMasks[uuid]
                val consumerUsages =
                    if (uuid != null && backMask != null && report?.size == 1) {
                        if (report[0].toInt() and backMask == 0) {
                            pressedBackDevices.remove(uuid)
                        } else if (pressedBackDevices.add(uuid) && client.modes.phoneOwnsScreen) {
                            output.injectNavigationKey(KeyEvent.KEYCODE_BACK)
                        }
                        mediaUsages.orEmpty().filterNot { it == ConsumerKeys.USAGE_AC_BACK }
                    } else {
                        mediaUsages
                    }
                // 旋钮/四向拨杆（Generic Desktop 页的 Wheel/Hat）：
                // 不是 Consumer 设备才走这里；输出 DPAD_* 注入虚拟屏。
                val navKeys =
                    if (consumerUsages == null && report != null) {
                        if (inputs.rawLogEnabled()) {
                            output.appendLog(
                                "HID-RAW: uuid=$uuid bytes=" +
                                    report.joinToString(" ") { "%02X".format(it) } +
                                    " desc=${descriptor?.size ?: 0}B",
                            )
                        }
                        inputs.handleNavigation(uuid.orEmpty(), descriptor, report)
                    } else {
                        emptyList()
                    }
                val contacts =
                    if (consumerUsages == null && navKeys.isEmpty() && report != null) {
                        // 触摸格式按设备描述符判定（Digitizer 页 + 单指/多槽），
                        // 描述符不可用时按报文长度回退——不依赖车机型号或 uuid
                        val touchFormat =
                            descriptor?.let(inputs::touchFormat)
                        inputs.parseTouch(report, touchFormat)
                    } else {
                        null
                    }
                if (navKeys.isNotEmpty()) {
                    navKeys.forEach(output::injectNavigationKey)
                    output.appendLog(
                        "EVENT: 旋钮/导航键 " +
                            navKeys.joinToString("+") { inputs.navigationName(it) } +
                            "（uuid=$uuid）",
                    )
                } else if (consumerUsages != null) {
                    // Consumer 设备的「无按键」/释放报文（值为 0 或下标越界）：
                    // 静默计数，避免释放事件刷屏
                    if (consumerUsages.isEmpty()) counters.consumerReleaseCount++
                    val edge = mediaKeyEdges.getOrPut(uuid.orEmpty()) { MediaKeyEdge() }
                    for (usage in edge.onReport(consumerUsages, nowUptimeMillis())) {
                        if (usage == ConsumerKeys.USAGE_AC_BACK) {
                            output.injectNavigationKey(KeyEvent.KEYCODE_BACK)
                            continue
                        }
                        val label = "${ConsumerKeys.nameOf(usage)} uuid=$uuid usage=0x%02x".format(usage)
                        val keyCode = ConsumerKeys.keyCodeFor(usage)
                        if (keyCode != 0) {
                            output.routeCarMediaKey(keyCode, label)
                        } else {
                            output.appendLog("EVENT: 车机媒体键 $label 无对应 Android 键，忽略")
                        }
                    }
                } else if (!contacts.isNullOrEmpty()) {
                    val target = currentTouchTarget()
                    // 触摸坐标不一定覆盖 displays[0]：部分车机对外声明 1280×720 视频面，
                    // 数字化仪却按物理 1920×720 面板报绝对值。优先使用描述符逻辑范围。
                    val hidRange = descriptor?.let(inputs::touchRange)
                    val sourceRange =
                        hidRange
                            ?: TouchReports.CoordinateRange(
                                minX = 0,
                                maxX = (setup.displayWidth - 1).coerceAtLeast(1),
                                minY = 0,
                                maxY = (setup.displayHeight - 1).coerceAtLeast(1),
                            )
                    if (target != null && loggedTouchRangeUuids.add(uuid.orEmpty())) {
                        output.appendLog(
                            "EVENT: 触摸坐标范围 ${sourceRange.describe()}" +
                                if (hidRange != null) {
                                    "（HID 描述符；显示 ${setup.displayWidth}x${setup.displayHeight}）"
                                } else {
                                    "（无可用描述符，按 displays[0] 回退）"
                                },
                        )
                    }
                    if (counters.touchLogCount <= 30 || counters.touchLogCount % 50 == 0) {
                        output.appendLog(
                            "EVENT: 触摸 " +
                                contacts.joinToString {
                                    "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                },
                        )
                    }
                    counters.touchLogCount++
                    if (target != null) {
                        val contact =
                            contacts.firstOrNull { it.slot == 0 }
                                ?: contacts.first()
                        val point =
                            mapCarLifeTouch(
                                x = contact.xPixels,
                                y = contact.yPixels,
                                source = sourceRange,
                                targetWidth = target.videoWidth,
                                targetHeight = target.videoHeight,
                            )
                        if (point != null) {
                            if (target.sendTouch(point.first, point.second, contact.down)) {
                                output.onTouchSent()
                            }
                        }
                    }
                } else if (report != null) {
                    output.appendLog(
                        "EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}" +
                            " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                    )
                }
            }

            "requestSiri" -> {
                val params = command["params"] as? Map<*, *>
                val action = params?.get("siriAction")
                if (voiceButton.onAction(action, nowUptimeMillis())) {
                    output.onSiriButtonPressed()
                }
            }

            "iAPSendMessage" -> {
                // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                val params = command["params"] as? Map<*, *>
                val data = params?.get("data") as? ByteArray
                if (data == null) {
                    output.appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                } else {
                    counters.iapEventMessageCount++
                    client.onIapEventBytes(data)
                }
            }

            "forceKeyFrame" -> {
                // 车机解不出画面时会要 I 帧。必须立即响应：
                // 拖到下一个周期 I 帧（1s）车机可能一直卡在等帧状态。
                // 帕萨特 2025 解不了透传流时每 ~300ms 要一次，一轮几百条——限流记录。
                val sender = currentVideoSender()
                val forced = sender?.requestKeyFrame() == true
                counters.forceKeyFrameCount++
                if (counters.forceKeyFrameCount <= 3 || counters.forceKeyFrameCount % 20 == 0) {
                    val gap = sender?.lastReceiverRequestIdrGapMs
                    output.appendLog(
                        "EVENT: 车机请求关键帧（forceKeyFrame，第 ${counters.forceKeyFrameCount} 次" +
                            (if (gap != null) "，距上个已发 IDR ${gap}ms" else "") + "）→ " +
                            when {
                                !forced -> "源未就绪，等周期 I 帧"
                                sender?.isExternalPassthrough == true ->
                                    "已向 CarWith 请求（透传源不保证立即出 I 帧）"
                                sender?.isTranscoding == true -> "已强制编码 I 帧（转码输出）"
                                else -> "已强制编码 I 帧"
                            },
                    )
                }
            }

            "changeModes" -> {
                // 车机请求资源（如用户回原车主界面时 take 主屏）：按请求更新归属后如实回 modesChanged。
                // 旧实现一律回「手机持有」，帕萨特回主界面后再点图标就切不回 CarPlay（2026-11-01 22:47）
                val note = client.modes.onChangeModes(command["params"] as? Map<*, *>)
                output.enqueueModesAssert(
                    reason = "车机请求 changeModes（→ $note）",
                    requestKeyFrame = false,
                    retakeScreen = false,
                    rebuildStream = false,
                )
                if (client.modes.consumeAudioClaimRetry()) {
                    output.appendLog("AUDIO: 车机已归还主音频——后台重试建立音频流")
                    output.runBackground("audio-claim-retry") { output.retryAudioAfterClaimReturned() }
                }
            }

            "requestUI" -> {
                // 用户在原生菜单点 CarPlay 图标（帕萨特 2025 / VW4211 实测：每点一次一条，
                // 无 params）。事件层已回 RTSP 200，但旧实现不做业务处理（不回 modesChanged），
                // 车机始终停在原生界面。与 changeModes 同样回 modesChanged 接管主屏，
                // 并补一个 I 帧，让车机切屏后马上有可解码的画面。
                counters.requestUiCount++
                // 只有本轮音频流真的在跑才把主音频声明给手机：audioEnabled
                // 只是用户的选择，无共享密钥、无投屏授权或全部档位 SETUP 失败时
                // startAudioChannel 返回 false，此时声明 resourceID=2 会让车机停掉
                // 自己的音频而 CarPlay 没有发送端接替（重进后静音/抢走车机本地音频）。
                val audio =
                    requestUiAudioDecision(
                        audioEnabled = audioEnabled(),
                        audioStreamRunning = audioStreamRunning(),
                        audioClaimAwaitingReturn = client.modes.audioClaimAwaitingReturn,
                    )
                val request = client.modes.onUserRequestedUi(takeAudio = audio.takeMainAudio)
                val audioSuffix =
                    if (audioEnabled() && !audio.takeMainAudio) {
                        "；音频流未建立，暂不声明主音频"
                    } else {
                        ""
                    }
                output.enqueueModesAssert(
                    reason =
                        "车机 requestUI（用户点了 CarPlay 图标，第 ${counters.requestUiCount} 次；" +
                            "${request.note}$audioSuffix）",
                    requestKeyFrame = request.allowed,
                    retakeScreen = request.retake,
                    rebuildStream = resolveScreenReturnRebuild(request.rebuildStream),
                )
                if (audio.retryAfterPendingClaim && client.modes.consumeAudioClaimRetry()) {
                    // 音频 SETUP 此前因车机以高约束持有/借用主音频而推迟：用户重进
                    // CarPlay 是用户发起动作，后台按正常路径重试一次；归属仍由
                    // onAudioStreamStarting 按当时的约束判断决定（倒车提示音等不抢）。
                    // 先原子消费 pending claim，避免 requestUI 与 changeModes 各跑一遍；
                    // startAudioChannel 的单飞闸门再兜住 SETUP 窗口内的并发。
                    output.appendLog("AUDIO: requestUI 重进——主音频此前未接管，后台重试建立音频流")
                    output.runBackground("audio-request-ui-retry") { output.retryAudioAfterClaimReturned() }
                }
            }

            else ->
                output.appendLog(
                    // 未识别的命令必须带参数：车机的私有命令（如 requestUI）
                    // 到底要什么，全在 params 里——只打 type 等于没打。
                    "EVENT: 命令 type=${command["type"]} " +
                        command.entries
                            .filter { it.key?.toString() != "type" }
                            .joinToString(" ") { (key, value) ->
                                "$key=" +
                                    when (value) {
                                        is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                        else -> value.toString().take(240)
                                    }
                            }.take(600),
                )
        }
    }

    internal interface EventClient {
        val modes: ModeArbiter

        fun onIapEventBytes(data: ByteArray)
    }

    internal interface EventCounters {
        var touchLogCount: Int
        var consumerReleaseCount: Int
        var iapEventMessageCount: Int
        var forceKeyFrameCount: Int
        var requestUiCount: Int
    }

    internal interface EventOutput {
        fun appendLog(line: String)

        fun routeCarMediaKey(
            keyCode: Int,
            label: String,
        )

        fun injectNavigationKey(keyCode: Int)

        fun onSiriButtonPressed() {}

        fun enqueueModesAssert(
            reason: String,
            requestKeyFrame: Boolean,
            retakeScreen: Boolean,
            rebuildStream: Boolean,
        )

        fun runBackground(
            name: String,
            task: () -> Unit,
        )

        fun retryAudioAfterClaimReturned()

        fun onTouchSent()
    }

    internal interface KeyFrameSender {
        fun requestKeyFrame(): Boolean

        val lastReceiverRequestIdrGapMs: Long?
        val isExternalPassthrough: Boolean
        val isTranscoding: Boolean
    }

    internal interface TouchTarget {
        val videoWidth: Int
        val videoHeight: Int

        fun sendTouch(
            x: Int,
            y: Int,
            down: Boolean,
        ): Boolean
    }

    /** 可测试输入缝；生产默认实现直接委托原 HID/触摸 helper，不复制解码算法。 */
    internal interface EventInputs {
        fun rawLogEnabled(): Boolean

        fun handleNavigation(
            uuid: String,
            descriptor: ByteArray?,
            report: ByteArray,
        ): List<Int>

        fun navigationName(keyCode: Int): String

        fun touchFormat(descriptor: ByteArray): TouchReports.Format?

        fun touchRange(descriptor: ByteArray): TouchReports.CoordinateRange?

        fun parseTouch(
            report: ByteArray,
            format: TouchReports.Format?,
        ): List<TouchReports.Contact>?
    }

    internal object DefaultEventInputs : EventInputs {
        override fun rawLogEnabled(): Boolean = HidNavigation.rawLogEnabled

        override fun handleNavigation(
            uuid: String,
            descriptor: ByteArray?,
            report: ByteArray,
        ): List<Int> = HidNavigation.handle(uuid, descriptor, report)

        override fun navigationName(keyCode: Int): String = HidNavigation.nameOf(keyCode)

        override fun touchFormat(descriptor: ByteArray): TouchReports.Format? = TouchReports.formatFromDescriptor(descriptor)

        override fun touchRange(descriptor: ByteArray): TouchReports.CoordinateRange? = TouchReports.coordinateRange(descriptor)

        override fun parseTouch(
            report: ByteArray,
            format: TouchReports.Format?,
        ): List<TouchReports.Contact>? = TouchReports.parse(report, format)
    }
}
