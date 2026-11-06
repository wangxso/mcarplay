package com.mcarplay.app.carlife

import android.app.Application
import android.os.SystemClock
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.diagnostics.DiagnosticExportPolicy
import com.mcarplay.app.util.Bg
import java.io.EOFException
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Bridges a live CarWith/CarLife session into the CarPlay sender.
 *
 * CarWith owns the seven TCP listeners. mcarplay opens its exported entry with
 * an ordinary app intent, waits until
 * every channel is connected, then starts the authenticated handshake. Any
 * command/video disconnect tears down the media session so stale frames are
 * never submitted.
 */
class CarLifeSession(
    application: Application,
    private val onAccessUnit: (CarLifeVideoAccessUnit) -> Unit,
    private val onAudioEvent: (CarLifeChannel, CarLifeAudioEvent) -> Unit = { _, _ -> },
    private val onEnded: (String) -> Unit,
    private val log: (String) -> Unit,
    private val launchStrategy: CarLifeLaunchStrategy = CarLifeLaunchStrategy.COMPATIBLE,
    /** 向 CarWith 请求的编码尺寸；null 时沿用协议默认值。CarWith 可能不采纳。 */
    private val requestedVideoSize: Pair<Int, Int>? = null,
    private val isAppInForeground: () -> Boolean = CarLifeLauncher::isAppInForeground,
    private val connectorFactory: (String, (Int) -> Socket) -> CarLifeChannelConnector =
        { _, connect ->
            CarLifeChannelConnector(connect, log = log)
        },
    private val requestPublicEntry: () -> CarLifeLauncher.EntryLaunchResult = { CarLifeLauncher.requestPublicEntry(application) },
    private val requestLegacyEntry: () -> CarLifeLauncher.EntryLaunchResult = { CarLifeLauncher.requestLegacyCarWithService(application) },
    private val requestSettingsEntry: () -> CarLifeLauncher.EntryLaunchResult = { CarLifeLauncher.requestCarWithSettings(application) },
    private val attachedFirstFrameWaitMs: Long = ATTACHED_FIRST_FRAME_WAIT_MS,
    private val notificationSnapshot: () -> CarWithNotificationSnapshot = CarWithNotificationProbe::snapshot,
    /** CarWith 发来「返回车机」时回调（命令读取线程）。 */
    private val onReturnToCar: () -> Unit = {},
) {
    private val appContext: Application = application
    internal val lease = CarLifeSessionLease()
    private val running = AtomicBoolean(false)
    private val sourceRun = UUID.randomUUID().toString().take(8)
    private val stateMachine =
        CarLifeStateMachine(
            CarLifeAuthVerifier.xiaomi(),
            CarLifeSeedExchange(),
        )
    private val commandRouter = CarLifeCommandRouter(stateMachine)
    private val videoParser = CarLifeVideoParser()
    private val audioParsers =
        listOf(CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VOICE)
            .associateWith { CarLifeAudioParser() }

    /** 每个通道已见过的帧类型：首帧各记一行，区分「CarWith 没发」与「发了被丢」。 */
    private val seenAudioKinds = java.util.Collections.synchronizedSet(mutableSetOf<Pair<CarLifeChannel, Int>>())
    private val unparsedAudio = java.util.concurrent.ConcurrentHashMap<CarLifeChannel, Long>()
    private val touchState = CarLifeTouchState()

    @Volatile
    private var endReported = false

    @Volatile
    private var entryResult = CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED

    @Volatile
    private var entryRoute = EntryRoute.NONE

    private val entryRequestLock = Any()

    @Volatile
    private var compatibleEntryRequested = false

    @Volatile
    private var lastChannelSummary = "not_started"

    @Volatile
    private var startedAtMs: Long? = null

    @Volatile
    private var endedAtMs: Long? = null
    private var sourceThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var attachWatchThread: Thread? = null

    @Volatile
    private var connector: CarLifeChannelConnector? = null

    @Volatile
    private var notificationBaseline: CarWithNotificationSnapshot? = null

    @Volatile
    private var commandFramesReceived: Long = 0L

    /** 入口请求发出时本应用不在前台；七口超时时据此提示系统可能拦截了入口。 */
    @Volatile
    var entryLaunchedInBackground: Boolean = false
        private set

    /** 七口等待超时且入口是在后台发起的：诊断按 CARWITH_ENTRY_BLOCKED 归类。 */
    @Volatile
    var entryBlockedSuspected: Boolean = false
        private set

    @Volatile
    var endDiagnosticCode: DiagnosticCode = DiagnosticCode.CARWITH_FAILED
        private set

    @Volatile
    var videoWidth: Int = CarLifeProtocol.DEFAULT_WIDTH
        private set

    @Volatile
    var videoHeight: Int = CarLifeProtocol.DEFAULT_HEIGHT
        private set

    private val stats = CarLifeVideoStats()

    val isRunning: Boolean get() = running.get()

    val state: CarLifeStateMachine.State get() = stateMachine.state

    val accessUnitCount: Long get() = stats.snapshot().frames

    val keyFrameCount: Long get() = stats.snapshot().keyFrames

    val receivedVideoBytes: Long get() = stats.snapshot().bytes

    val videoStats: String get() = stats.snapshot().summary()

    /** 固定状态和计数；可在阻塞 connectAll() 时由报告/remote 只读观察。 */
    fun diagnosticSummary(): String {
        val elapsed = startedAtMs?.let { ((endedAtMs ?: monotonicMs()) - it).coerceAtLeast(0) } ?: 0
        return "schema=1 run=$sourceRun running=${running.get() && !endReported} failed=$endReported " +
            "elapsedMs=$elapsed state=${state.name} " +
            "entry=$entryResult route=$entryRoute background=$entryLaunchedInBackground " +
            "entryBlockedSuspected=$entryBlockedSuspected code=${endDiagnosticCode.name} " +
            "commandFrames=$commandFramesReceived carwithNotification=[${notificationProbeSummary()}] " +
            "listeners=[${connector?.diagnosticSummary() ?: lastChannelSummary}] video=${videoInfo()} ${stats.snapshot().summary()}"
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        startedAtMs = monotonicMs()
        notificationBaseline = notificationSnapshot()
        ConnectionDiagnostics.runtimeLog(
            "CARLIFE-DIAG run=$sourceRun event=NOTIFICATION_BASELINE ${notificationProbeSummary()}",
        )
        Bg.run("carlife-source-environment") {
            val fields = CarLifeSourceEnvironment.snapshot(appContext).entries.joinToString(" ") { "${it.key}=${it.value}" }
            ConnectionDiagnostics.runtimeLog("CARLIFE-DIAG run=$sourceRun event=ENV $fields")
        }
        if (!CarLifeLauncher.isAvailable(appContext)) {
            fail("未找到 CarWith（${CarLifeLauncher.CARWITH_PACKAGE}）或 CarLife 组件")
            return false
        }
        when (launchStrategy) {
            CarLifeLaunchStrategy.EXPORTED_ENTRY -> {
                entryLaunchedInBackground = !isAppInForeground()
                entryResult = requestPublicEntry()
                ConnectionDiagnostics.runtimeLog(
                    "CARLIFE-DIAG run=$sourceRun event=ENTRY result=$entryResult background=$entryLaunchedInBackground",
                )
                if (entryResult != CarLifeLauncher.EntryLaunchResult.REQUESTED) {
                    fail("当前 CarWith 版本不允许打开兼容入口，请检查 CarWith 设置")
                    return false
                }
                log("CARLIFE: 已通过应用 Intent 请求 CarWith 公开入口，等待七路端口")
                if (entryLaunchedInBackground) {
                    log("CARLIFE: 本应用当前不在前台，系统可能拦截 CarWith 入口（小米需允许「后台弹出界面」）")
                }
            }

            CarLifeLaunchStrategy.COMPATIBLE -> {
                entryLaunchedInBackground = !isAppInForeground()
                log("CARLIFE: 先附着已有 CarWith 会话；七口未就绪时再请求入口")
            }

            CarLifeLaunchStrategy.ATTACH_EXISTING -> {
                log("CARLIFE: 附着已有 CarWith 会话，等待七路端口")
            }
        }
        sourceThread =
            thread(name = "carlife-client", isDaemon = true) {
                connectAndRead()
            }
        return true
    }

    fun sendTouch(
        x: Int,
        y: Int,
        down: Boolean,
    ): Boolean {
        val action =
            touchState.onContact(
                x = x,
                y = y,
                down = down,
                width = videoWidth,
                height = videoHeight,
            ) ?: return false
        return sendTouchAction(action, x, y)
    }

    fun requestKeyFrame(): Boolean {
        if (stateMachine.state != CarLifeStateMachine.State.VIDEO_RUNNING) return false
        return sendCommand(
            CarLifeProtocol.VIDEO_ENCODER_RESET,
            ByteArray(0),
        )
    }

    fun videoInfo(): String = "${videoWidth}x$videoHeight"

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        if (endedAtMs == null) endedAtMs = monotonicMs()
        releaseActiveTouch()
        connector?.let {
            it.close()
            lastChannelSummary = it.diagnosticSummary()
        }
        connector = null
        sourceThread?.interrupt()
        sourceThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        attachWatchThread?.interrupt()
        attachWatchThread = null
        stateMachine.close()
        ConnectionDiagnostics.runtimeLog("CARLIFE-DIAG event=STOP ${diagnosticSummary()}")
        log("CARLIFE: 七路连接已关闭")
    }

    private fun connectAndRead() {
        val host = "127.0.0.1"
        val activeConnector =
            connectorFactory(host) { port ->
                connectCarLifeSocket(host, port, CONNECT_TIMEOUT_MS)
            }
        activeConnector.onAttemptFailed = { attempt, _ ->
            if (launchStrategy == CarLifeLaunchStrategy.COMPATIBLE && attempt == 1) {
                requestCompatibleEntry()
            }
        }
        connector = activeConnector
        try {
            activeConnector.connectAll(LISTENER_WAIT_MS)
            if (!running.get()) return
            if (entryResult == CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED) {
                entryResult = CarLifeLauncher.EntryLaunchResult.ATTACHED
                entryRoute = EntryRoute.ATTACHED
                ConnectionDiagnostics.runtimeLog(
                    "CARLIFE-DIAG run=$sourceRun event=ENTRY result=$entryResult route=$entryRoute",
                )
                log("CARLIFE: 已附着现有 CarWith 七口会话，无需再打开入口")
                if (launchStrategy == CarLifeLaunchStrategy.COMPATIBLE) startAttachedFirstFrameWatch()
            }
            ConnectionDiagnostics.runtimeLog(
                "CARLIFE-DIAG run=$sourceRun event=LISTENERS_CONNECTED ${activeConnector.diagnosticSummary()} " +
                    "notification=[${notificationProbeSummary()}]",
            )
            log("CARLIFE: 七路连接完成，开始协议握手")
            stateMachine.onConnected().forEach(::sendAction)
            startChannelReader(CarLifeChannel.VIDEO)
            startChannelReader(CarLifeChannel.MEDIA)
            startChannelReader(CarLifeChannel.TTS)
            startChannelReader(CarLifeChannel.VOICE)
            startChannelReader(CarLifeChannel.DATA)
            readCommandLoop(activeConnector)
        } catch (error: Exception) {
            if (running.get()) {
                DiagnosticExportPolicy.exception("CARLIFE_CONNECT_AND_READ", error)
                fail(error)
            }
        } finally {
            // A peer close is already reported by the channel reader. Only
            // report an unexpected return from the command loop here.
            if (running.get() && stateMachine.state != CarLifeStateMachine.State.FAILED) {
                fail("CarLife 命令通道已结束")
            }
        }
    }

    /**
     * 第一次连接失败才请求入口。旧版没有公开入口时依次尝试 root 服务和设置页；
     * 不打开百度 CarLife 首页，因为它只启动七口，不能创建 CarWith 编码器。
     */
    private fun requestCompatibleEntry() {
        synchronized(entryRequestLock) {
            if (compatibleEntryRequested || !running.get()) return
            compatibleEntryRequested = true
        }
        entryLaunchedInBackground = !isAppInForeground()
        val carWithEntry = requestPublicEntry()
        if (!running.get() || Thread.currentThread().isInterrupted) return
        val legacyEntry =
            if (carWithEntry == CarLifeLauncher.EntryLaunchResult.REQUESTED) {
                CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED
            } else {
                requestLegacyEntry()
            }
        if (!running.get() || Thread.currentThread().isInterrupted) return
        val settingsEntry =
            if (
                carWithEntry == CarLifeLauncher.EntryLaunchResult.REQUESTED ||
                legacyEntry == CarLifeLauncher.EntryLaunchResult.REQUESTED
            ) {
                CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED
            } else {
                requestSettingsEntry()
            }
        if (legacyEntry != CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED) {
            ConnectionDiagnostics.runtimeLog(
                "CARLIFE-DIAG run=$sourceRun event=ENTRY_LEGACY result=$legacyEntry " +
                    "from=$carWithEntry background=$entryLaunchedInBackground",
            )
        }
        if (settingsEntry != CarLifeLauncher.EntryLaunchResult.NOT_REQUESTED) {
            ConnectionDiagnostics.runtimeLog(
                "CARLIFE-DIAG run=$sourceRun event=ENTRY_SETTINGS result=$settingsEntry " +
                    "from=$carWithEntry legacy=$legacyEntry background=$entryLaunchedInBackground",
            )
        }
        val plan = planCompatibleEntry(carWithEntry, legacyEntry, settingsEntry)
        entryResult = plan.result
        entryRoute = plan.route
        ConnectionDiagnostics.runtimeLog(
            "CARLIFE-DIAG run=$sourceRun event=ENTRY result=$entryResult route=$entryRoute " +
                "background=$entryLaunchedInBackground notification=[${notificationProbeSummary()}]",
        )
        when (plan.route) {
            EntryRoute.CARWITH_ENTRY ->
                log("CARLIFE: 已请求 CarWith 公开入口，等待七路端口")
            EntryRoute.LEGACY_ROOT_SERVICE ->
                log("CARLIFE: 已通过 root 启动旧版 CarWith CarLife 服务，等待七路端口")
            EntryRoute.CARWITH_SETTINGS ->
                log("CARLIFE: 当前版本需要原厂设置页启动连接；请在 CarWith 中选择百度 CarLife 无线连接")
            EntryRoute.ATTACHED, EntryRoute.NONE ->
                log("CARLIFE: 无法自动打开 CarWith 入口，继续等待已有七口")
        }
        if (entryLaunchedInBackground && plan.route != EntryRoute.LEGACY_ROOT_SERVICE) {
            log("CARLIFE: 本应用当前不在前台，系统可能拦截 CarWith 入口（小米需允许「后台弹出界面」）")
        }
    }

    /**
     * 七口就绪不代表 CarWith 已建虚拟屏和编码器（例如只开了百度 CarLife 首页，见
     * [CarLifeLauncher.requestCarWithSettings]）。附着后限时内没有视频帧，就按兼容顺序补请求一次入口。
     */
    private fun startAttachedFirstFrameWatch() {
        attachWatchThread =
            thread(name = "carlife-attach-watch", isDaemon = true) {
                try {
                    Thread.sleep(attachedFirstFrameWaitMs)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!running.get() || stats.snapshot().frames > 0) return@thread
                ConnectionDiagnostics.runtimeLog(
                    "CARLIFE-DIAG run=$sourceRun event=ATTACHED_NO_VIDEO waitMs=$attachedFirstFrameWaitMs",
                )
                log("CARLIFE: 已附着七口但 ${attachedFirstFrameWaitMs / 1000} s 内无视频帧，CarWith 投射可能未就绪，补请求入口")
                requestCompatibleEntry()
            }
    }

    private fun readCommandLoop(activeConnector: CarLifeChannelConnector) {
        val socket =
            activeConnector.socket(CarLifeChannel.COMMAND)
                ?: throw EOFException("CarLife command channel is missing")
        val input = socket.getInputStream()
        while (running.get()) {
            val frame = readCarLifeSocket(input, CarLifeChannel.COMMAND.port)
            commandFramesReceived++
            handleCommand(frame)
            if (stateMachine.state == CarLifeStateMachine.State.FAILED) {
                fail("CarLife 握手失败（未发送伪造成功结果）")
                return
            }
        }
    }

    private fun handleCommand(frame: CarLifeFrame) {
        val result = commandRouter.route(frame)
        result.log?.let { message -> log(message) }
        result.actions.forEach(::sendAction)
        if (result.returnToCar) onReturnToCar()
        if (frame.kind == CarLifeProtocol.MD_PROTOCOL_MATCH && !result.failed) startHeartbeat()
        if (result.encryptionReady) {
            log("CARLIFE: AES 已就绪，请求视频初始化")
            val (width, height) = requestedVideoSize ?: (CarLifeProtocol.DEFAULT_WIDTH to CarLifeProtocol.DEFAULT_HEIGHT)
            log("CARLIFE: 请求编码尺寸 ${width}x$height（以 VIDEO_INIT_DONE 返回为准）")
            stateMachine.requestVideoInit(width, height).forEach(::sendAction)
            startHeartbeat()
        }
        if (frame.kind == CarLifeProtocol.VIDEO_ENCODER_INIT_DONE &&
            stateMachine.state != CarLifeStateMachine.State.FAILED
        ) {
            // The router already decrypted and validated the negotiated format.
            val format = stateMachine.videoFormat ?: return
            if (touchState.releasePoint() != null) {
                sendTouchAction(CarLifeTouchAction.UP, videoWidth, videoHeight)
            }
            videoWidth = format.width
            videoHeight = format.height
        }
    }

    private fun startChannelReader(channel: CarLifeChannel) {
        val activeConnector = connector ?: return
        thread(name = "carlife-read-${channel.name.lowercase()}", isDaemon = true) {
            val socket = activeConnector.socket(channel) ?: return@thread
            try {
                val input = socket.getInputStream()
                while (running.get()) {
                    val frame = readCarLifeSocket(input, channel.port)
                    if (channel == CarLifeChannel.VIDEO) {
                        handleVideo(frame)
                    } else if (channel != CarLifeChannel.COMMAND) {
                        handleAudio(frame)
                    }
                }
            } catch (_: EOFException) {
                connectionClosed(channel)
            } catch (error: Exception) {
                if (running.get()) {
                    DiagnosticExportPolicy.exception("CARLIFE_READ_${channel.name}", error)
                    log("CARLIFE: ${channel.name} 读取失败：${error.message}")
                }
                connectionClosed(channel)
            }
        }
    }

    internal fun handleVideo(frame: CarLifeFrame) {
        if (frame.kind != CarLifeProtocol.VIDEO_FRAME) return
        val cipher = stateMachine.payloadCipher
        val payload =
            if (cipher != null && frame.payload.isNotEmpty()) {
                runCatching { cipher.decrypt(frame.payload) }
                    .getOrElse {
                        fail("CarLife 视频解密失败")
                        return
                    }
            } else {
                frame.payload
            }
        videoParser.push(frame.timestamp.toLong() and 0xFFFF_FFFFL, payload).forEach { accessUnit ->
            stats.record(accessUnit)
            accessUnit.dimensions()?.let { (width, height) ->
                videoWidth = width
                videoHeight = height
            }
            val snapshot = stats.snapshot()
            if (snapshot.frames == 1L || accessUnit.keyFrame) {
                log("CARLIFE: 视频帧 #${snapshot.frames} ${videoInfo()} ${snapshot.summary()}")
            }
            onAccessUnit(accessUnit)
        }
    }

    internal fun handleAudio(frame: CarLifeFrame) {
        val channel = CarLifeChannel.fromPort(frame.channel) ?: return
        val parser = audioParsers[channel] ?: return
        if (seenAudioKinds.add(channel to frame.kind)) {
            log("CARLIFE: ${channel.name} 首次收到 kind=0x${frame.kind.toString(16)} ${frame.payload.size}B")
        }
        val payload =
            runCatching {
                // This session connects specifically to com.baidu.carlife.xiaomi.
                CarLifeAudioPayload.decode(frame, stateMachine.payloadCipher, CarLifeAudioPayload.Mode.XIAOMI_MIX)
            }.getOrElse {
                parser.reset()
                log("CARLIFE: ${channel.name} 音频解密失败")
                return
            }
        val event = parser.parse(channel, frame.copy(payload = payload))
        if (event == null) {
            // PCM 在没有有效 INIT 时、INIT 字段不合法时、未知 kind 时都会被静默丢弃。
            val count = unparsedAudio.merge(channel, 1L, Long::plus) ?: 1L
            if (count == 1L || count % UNPARSED_AUDIO_LOG_EVERY == 0L) {
                log(
                    "CARLIFE: ${channel.name} 音频帧未解析 kind=0x${frame.kind.toString(16)} ${payload.size}B" +
                        "（累计 $count；PCM 须先有有效 INIT）${initFieldsSummary(frame.kind, payload)}",
                )
            }
            return
        }
        when (event) {
            is CarLifeAudioEvent.Init ->
                log(
                    "CARLIFE: ${channel.name} 音频初始化 " +
                        "${event.sampleRate}Hz ${event.channels}ch ${event.bitsPerSample}bit",
                )

            is CarLifeAudioEvent.Pcm -> Unit
            is CarLifeAudioEvent.Stop -> log("CARLIFE: ${channel.name} 音频停止 0x${frame.kind.toString(16)}")
        }
        onAudioEvent(channel, event)
    }

    private fun sendAction(action: CarLifeStateMachine.Action) {
        val socket = connector?.socket(CarLifeChannel.COMMAND)
        if (socket == null) {
            fail("CarLife 命令通道未连接")
            return
        }
        try {
            val cipher = stateMachine.payloadCipher
            val body =
                if (cipher != null) {
                    cipher.encrypt(action.payload)
                } else {
                    action.payload
                }
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write(CarLifeProtocol.encodeCommand(action.kind, body))
                    flush()
                }
            }
            log("CARLIFE: TX 0x${action.kind.toString(16)} ${action.payload.size}B")
        } catch (error: Exception) {
            DiagnosticExportPolicy.exception("CARLIFE_SEND_COMMAND", error)
            fail("CarLife 命令发送失败：${error.message}")
        }
    }

    private fun sendCommand(
        kind: Int,
        payload: ByteArray,
    ): Boolean {
        val socket = connector?.socket(CarLifeChannel.COMMAND) ?: return false
        return runCatching {
            val cipher = stateMachine.payloadCipher
            val body = if (cipher != null) cipher.encrypt(payload) else payload
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write(CarLifeProtocol.encodeCommand(kind, body))
                    flush()
                }
            }
            true
        }.getOrElse {
            connectionClosed(CarLifeChannel.COMMAND)
            false
        }
    }

    private fun startHeartbeat() {
        if (heartbeatThread != null) return
        heartbeatThread =
            thread(name = "carlife-video-heartbeat", isDaemon = true) {
                try {
                    while (running.get()) {
                        Thread.sleep(VIDEO_HEARTBEAT_INTERVAL_MS)
                        if (!running.get()) break
                        sendVideoHeartbeat()
                    }
                } catch (_: InterruptedException) {
                    // stop() interrupts the sleeping worker; cancellation is not a session failure.
                    Thread.currentThread().interrupt()
                }
            }
    }

    private fun sendVideoHeartbeat() {
        val socket = connector?.socket(CarLifeChannel.VIDEO) ?: return
        val timestamp = (System.currentTimeMillis() / 1000L).toInt()
        val cipher = stateMachine.payloadCipher
        val payload =
            if (cipher == null) {
                ByteArray(0)
            } else {
                cipher.encrypt(ByteArray(0))
            }
        runCatching {
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write(CarLifeProtocol.encodeVideo(CarLifeProtocol.VIDEO_HEARTBEAT, timestamp, payload))
                    flush()
                }
            }
        }.onFailure { connectionClosed(CarLifeChannel.VIDEO) }
    }

    private fun sendTouchAction(
        action: CarLifeTouchAction,
        x: Int,
        y: Int,
    ): Boolean {
        if (stateMachine.state != CarLifeStateMachine.State.VIDEO_RUNNING) return false
        val socket = connector?.socket(CarLifeChannel.TOUCH) ?: return false
        val kind =
            when (action) {
                CarLifeTouchAction.DOWN -> CarLifeProtocol.TOUCH_DOWN
                CarLifeTouchAction.MOVE -> CarLifeProtocol.TOUCH_MOVE
                CarLifeTouchAction.UP -> CarLifeProtocol.TOUCH_UP
            }
        return runCatching {
            val cipher = stateMachine.payloadCipher
            val payload = CarLifeProtocol.touchPayload(x, y)
            val body = if (cipher != null) cipher.encrypt(payload) else payload
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write(CarLifeProtocol.encodeCommand(kind, body))
                    flush()
                }
            }
            true
        }.getOrElse {
            connectionClosed(CarLifeChannel.TOUCH)
            false
        }
    }

    /** 车机硬按键 → CarWith（0x68008）；未进入视频阶段或发送失败返回 false。 */
    fun sendHardKey(keyCode: Int): Boolean {
        if (stateMachine.state != CarLifeStateMachine.State.VIDEO_RUNNING) return false
        val socket = connector?.socket(CarLifeChannel.TOUCH) ?: return false
        return runCatching {
            val payload = CarLifeProtocol.hardKeyPayload(keyCode)
            val body = stateMachine.payloadCipher?.encrypt(payload) ?: payload
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write(CarLifeProtocol.encodeCommand(CarLifeProtocol.TOUCH_CAR_HARD_KEY_CODE, body))
                    flush()
                }
            }
            true
        }.getOrElse {
            connectionClosed(CarLifeChannel.TOUCH)
            false
        }
    }

    private fun releaseActiveTouch() {
        val (x, y) = touchState.releasePoint() ?: return
        sendTouchAction(CarLifeTouchAction.UP, x, y)
    }

    /** INIT 解析失败时附上三个 protobuf 字段，便于判断是格式不合法还是解密不对。 */
    private fun initFieldsSummary(
        kind: Int,
        payload: ByteArray,
    ): String {
        if (kind != CarLifeProtocol.MEDIA_INIT && kind != CarLifeProtocol.NAVI_TTS_INIT && kind != CarLifeProtocol.VR_AUDIO_INIT) return ""
        val fields = (1..3).map { field -> runCatching { CarLifeAudioParser.firstIntField(payload, field) }.getOrNull() }
        return " INIT 字段 rate=${fields[0]} channels=${fields[1]} format=${fields[2]}"
    }

    private fun connectionClosed(channel: CarLifeChannel) {
        if (!running.get()) return
        if (channel == CarLifeChannel.COMMAND || channel == CarLifeChannel.VIDEO) {
            fail("CarLife 关键通道 ${channel.name} 已断开")
        } else {
            log("CARLIFE: 非关键通道 ${channel.name} 已断开")
        }
    }

    private fun fail(error: Exception) {
        fail("CarLife 客户端失败", error)
    }

    private fun fail(
        message: String,
        error: Exception? = null,
    ) {
        if (!endReported) {
            val code = classifyTerminalFailure(error)
            endDiagnosticCode = code
            entryBlockedSuspected = code == DiagnosticCode.CARWITH_ENTRY_BLOCKED
            endReported = true
            if (endedAtMs == null) endedAtMs = monotonicMs()
            val finalMessage =
                if (error == null) {
                    clientFailureMessage(message, code)
                } else {
                    clientFailureMessage(error, code)
                }
            ConnectionDiagnostics.runtimeLog("CARLIFE-DIAG event=FAILED ${diagnosticSummary()}")
            log("CARLIFE: $finalMessage")
            onEnded(finalMessage)
        }
        stop()
    }

    private fun classifyTerminalFailure(error: Exception?): DiagnosticCode {
        val listenerProgress = connector?.progressSnapshot()
        return CarWithEntryDiagnosis.classify(
            CarWithEntryDiagnosis.Evidence(
                listenersTimedOut = error is CarLifeListenerTimeoutException,
                listenersConnectedOnce = listenerProgress?.allConnectedOnce == true,
                commandFramesReceived = commandFramesReceived,
                entryLaunchedInBackground = entryLaunchedInBackground,
                notification = notificationSnapshot(),
                notificationBaseline = notificationBaseline,
            ),
        )
    }

    private fun notificationProbeSummary(): String =
        runCatching {
            notificationSnapshot().diagnosticSummary(SystemClock.elapsedRealtime(), notificationBaseline)
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }

    /** 连接前已有七口，或实际请求到的入口及回退层级。 */
    enum class EntryRoute { NONE, ATTACHED, CARWITH_ENTRY, LEGACY_ROOT_SERVICE, CARWITH_SETTINGS }

    internal companion object {
        private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

        const val UNPARSED_AUDIO_LOG_EVERY = 2_000L

        data class EntryPlan(
            val result: CarLifeLauncher.EntryLaunchResult,
            val route: EntryRoute,
        )

        /**
         * 选入口：CarWith 自己的公开入口优先，其次 root 启动旧版内部服务，
         * 最后打开原厂设置页让用户完成无线连接。百度 CarLife 首页不在此列。
         */
        fun planCompatibleEntry(
            carWithEntry: CarLifeLauncher.EntryLaunchResult,
            legacyEntry: CarLifeLauncher.EntryLaunchResult,
            settingsEntry: CarLifeLauncher.EntryLaunchResult,
        ): EntryPlan =
            when {
                carWithEntry == CarLifeLauncher.EntryLaunchResult.REQUESTED ->
                    EntryPlan(carWithEntry, EntryRoute.CARWITH_ENTRY)
                legacyEntry == CarLifeLauncher.EntryLaunchResult.REQUESTED ->
                    EntryPlan(legacyEntry, EntryRoute.LEGACY_ROOT_SERVICE)
                settingsEntry == CarLifeLauncher.EntryLaunchResult.REQUESTED ->
                    EntryPlan(settingsEntry, EntryRoute.CARWITH_SETTINGS)
                else -> EntryPlan(settingsEntry, EntryRoute.NONE)
            }

        fun clientFailureMessage(
            error: Exception,
            code: DiagnosticCode,
        ): String = clientFailureMessage("CarLife 客户端失败：${error.message ?: error.javaClass.simpleName}", code)

        fun clientFailureMessage(
            base: String,
            code: DiagnosticCode,
        ): String =
            when (code) {
                DiagnosticCode.CARWITH_ENTRY_BLOCKED ->
                    "$ENTRY_BLOCKED_HINT（小米需允许「后台弹出界面」，或保持 mcarplay 在前台后重连）；$base"
                DiagnosticCode.CARWITH_ENTRY_NOT_OBSERVED ->
                    "$ENTRY_NOT_OBSERVED_HINT；$base"
                DiagnosticCode.CARWITH_ENTRY_STATUS_UNKNOWN ->
                    "$ENTRY_STATUS_UNKNOWN_HINT；$base"
                DiagnosticCode.CARWITH_LISTENERS_PENDING ->
                    "$LISTENERS_PENDING_HINT；$base"
                DiagnosticCode.CARWITH_PROTOCOL_SILENT ->
                    "$PROTOCOL_SILENT_HINT；$base"
                else -> base
            }

        const val ENTRY_BLOCKED_HINT = "CarWith 入口在后台发起，可能被系统拦截"
        const val ENTRY_NOT_OBSERVED_HINT = "七路未就绪，且通知使用权开启时未观察到 CarWith 通知"
        const val ENTRY_STATUS_UNKNOWN_HINT = "七路未就绪；通知使用权不足，无法判断 CarWith 入口是否执行"
        const val LISTENERS_PENDING_HINT = "已观察到 CarWith 通知，但七路端口未就绪"
        const val PROTOCOL_SILENT_HINT = "七路已连接，但未收到 CarLife 协议响应"

        const val CONNECT_TIMEOUT_MS = 800
        const val LISTENER_WAIT_MS = 25_000L

        /** 附着已有七口后等待首个视频帧的时长；超时才补请求入口。 */
        const val ATTACHED_FIRST_FRAME_WAIT_MS = 8_000L
        const val VIDEO_HEARTBEAT_INTERVAL_MS = 1_000L
    }
}
