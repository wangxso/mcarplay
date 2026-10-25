package com.mcarplay.app.carlife

import android.app.Application
import java.io.EOFException
import java.net.Socket
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
    private val launchStrategy: CarLifeLaunchStrategy = CarLifeLaunchStrategy.EXPORTED_ENTRY,
    private val connectorFactory: (String, (Int) -> Socket) -> CarLifeChannelConnector =
        { _, connect ->
            CarLifeChannelConnector(connect, log = log)
        },
) {
    private val appContext: Application = application
    internal val lease = CarLifeSessionLease()
    private val running = AtomicBoolean(false)
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
    private val touchState = CarLifeTouchState()
    private var endReported = false
    private var sourceThread: Thread? = null
    private var heartbeatThread: Thread? = null

    @Volatile
    private var connector: CarLifeChannelConnector? = null

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

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        if (!CarLifeLauncher.isAvailable(appContext)) {
            fail("未找到 CarWith（${CarLifeLauncher.CARWITH_PACKAGE}）或 CarLife 组件")
            return false
        }
        if (launchStrategy == CarLifeLaunchStrategy.EXPORTED_ENTRY) {
            if (!CarLifeLauncher.launchPublicEntry(appContext)) {
                fail("当前 CarWith 版本不允许打开兼容入口，请检查 CarWith 设置")
                return false
            }
            log("CARLIFE: 已通过应用 Intent 请求 CarWith 公开入口，等待七路端口")
        } else {
            log("CARLIFE: 附着已有 CarWith 会话，等待七路端口")
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
        releaseActiveTouch()
        connector?.close()
        connector = null
        sourceThread?.interrupt()
        sourceThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        stateMachine.close()
        log("CARLIFE: 七路连接已关闭")
    }

    private fun connectAndRead() {
        val host = "127.0.0.1"
        val activeConnector =
            connectorFactory(host) { port ->
                connectCarLifeSocket(host, port, CONNECT_TIMEOUT_MS)
            }
        connector = activeConnector
        try {
            activeConnector.connectAll(LISTENER_WAIT_MS)
            if (!running.get()) return
            log("CARLIFE: 七路连接完成，开始协议握手")
            stateMachine.onConnected().forEach(::sendAction)
            startChannelReader(CarLifeChannel.VIDEO)
            startChannelReader(CarLifeChannel.MEDIA)
            startChannelReader(CarLifeChannel.TTS)
            startChannelReader(CarLifeChannel.VOICE)
            startChannelReader(CarLifeChannel.DATA)
            readCommandLoop(activeConnector)
        } catch (error: Exception) {
            if (running.get()) fail("CarLife 客户端失败：${error.message ?: error.javaClass.simpleName}")
        } finally {
            // A peer close is already reported by the channel reader. Only
            // report an unexpected return from the command loop here.
            if (running.get() && stateMachine.state != CarLifeStateMachine.State.FAILED) {
                fail("CarLife 命令通道已结束")
            }
        }
    }

    private fun readCommandLoop(activeConnector: CarLifeChannelConnector) {
        val socket =
            activeConnector.socket(CarLifeChannel.COMMAND)
                ?: throw EOFException("CarLife command channel is missing")
        val input = socket.getInputStream()
        while (running.get()) {
            val frame = readCarLifeSocket(input, CarLifeChannel.COMMAND.port)
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
        if (frame.kind == CarLifeProtocol.MD_PROTOCOL_MATCH && !result.failed) startHeartbeat()
        if (result.encryptionReady) {
            log("CARLIFE: AES 已就绪，请求视频初始化")
            stateMachine.requestVideoInit().forEach(::sendAction)
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
        val payload =
            runCatching {
                // This session connects specifically to com.baidu.carlife.xiaomi.
                CarLifeAudioPayload.decode(frame, stateMachine.payloadCipher, CarLifeAudioPayload.Mode.XIAOMI_MIX)
            }.getOrElse {
                parser.reset()
                log("CARLIFE: ${channel.name} 音频解密失败")
                return
            }
        val event = parser.parse(channel, frame.copy(payload = payload)) ?: return
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
                while (running.get()) {
                    Thread.sleep(VIDEO_HEARTBEAT_INTERVAL_MS)
                    if (!running.get()) break
                    sendVideoHeartbeat()
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

    private fun releaseActiveTouch() {
        val (x, y) = touchState.releasePoint() ?: return
        sendTouchAction(CarLifeTouchAction.UP, x, y)
    }

    private fun connectionClosed(channel: CarLifeChannel) {
        if (!running.get()) return
        if (channel == CarLifeChannel.COMMAND || channel == CarLifeChannel.VIDEO) {
            fail("CarLife 关键通道 ${channel.name} 已断开")
        } else {
            log("CARLIFE: 非关键通道 ${channel.name} 已断开")
        }
    }

    private fun fail(message: String) {
        if (!endReported) {
            endReported = true
            log("CARLIFE: $message")
            onEnded(message)
        }
        stop()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 800
        const val LISTENER_WAIT_MS = 25_000L
        const val VIDEO_HEARTBEAT_INTERVAL_MS = 1_000L
    }
}
