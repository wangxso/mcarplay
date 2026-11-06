package com.mcarplay.app.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.mcarplay.app.airplay.AirPlayCrypto
import com.mcarplay.app.airplay.BinaryPlist
import com.mcarplay.app.airplay.StreamFrameCipher
import com.mcarplay.app.net.TcpLiveness
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay 主屏视频发送端（Phase 2-C2）。
 *
 * 线格式（对照 DiPlay ScreenStream.kt）：
 *  - 每条消息 = 128 字节头 + body；头部 = [0..3] u32LE body 长度、[4] opcode、
 *    [8..15] NTP 32.32 LE 发送时间；整头作帧加密 AAD，其余字节为零
 *  - opcode 1 = VideoConfig：明文 avcC 记录（looksLikeAvcC 判定）
 *  - opcode 0 = VideoFrame：ChaCha20-Poly1305 密封的「4 字节 BE 长度前缀 NAL 序列」，
 *    nonce = nonce64(帧计数)，计数按帧顺序从 0 递增
 *
 * 视频源：MediaCodec Surface 输入（H.264 硬编码），内容由画面源渲染
 * （隐形屏模式的 DualDisplayComposer 输入 Surface）。
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    /**
     * 帧加密方式（catplay `ScreenFrameCodec` 三种形态）：
     *  - ChaCha：已配对（pair-verify 派生流密钥）
     *  - AesCtr：未配对会话（`et=NONE`，AES-128-CTR + 全零会话密钥）
     */
    private val frameCipher: StreamFrameCipher,
    /** H.264 4:2:0 要求偶数尺寸——奇数高度会让编码器在首帧输入时进错误态（125% 缩放实测踩坑）。 */
    width: Int,
    height: Int,
    /** 目标帧率（30/60）：编码器元数据 + 60fps 提码率；实际节奏由合成器驱动。 */
    private val targetFps: Int = 30,
    private val log: (String) -> Unit,
    /** 推流自然结束（对端断开等）时回调——CarPlay 会话真实结束的信号。 */
    private val onEnded: (() -> Unit)? = null,
    /**
     * 屏幕流内 keepAlive 帧是否携带统计体（车机 /info 的 `keepAliveSendStatsAsBody`）。
     * 奥迪 MMI 3315 声明 true → opcode=5 + bplist 统计体；否则 opcode=2 空帧。
     * 真 iPhone/catplay 在视频持续推流时**仍然每秒发一次**——它不是视频流量的备份，
     * 而是接收端屏幕上独立的存活信号（2026-10-19 奥迪 3315 两轮实测：缺它时
     * RECORD 后 10.2s 整轮被 reset，视频/feedback/事件通道全都健康）。
     */
    private val keepAliveWithStats: Boolean = false,
    /**
     * Optional external H.264 source. When set, no MediaCodec encoder is
     * created; access units are framed and encrypted exactly like encoded
     * frames from the local Surface path.
     */
    private val externalVideoSource: ExternalVideoSource? = null,
    private val onExternalFrameSent: ((Int) -> Unit)? = null,
    /** 车机 /info displays[0].maxFPS（0 = 未知）；只约束转码时的编码帧率。 */
    private val receiverMaxFps: Int = 0,
    /**
     * 主屏此刻是否归手机。归车机时暂停全部视频帧；归还后等新的 IDR 再恢复。
     * 默认 true = 调用方未提供资源仲裁状态时不改变原行为。
     */
    private val phoneOwnsScreen: () -> Boolean = { true },
    /** 主屏归属导致的屏幕视频暂停/恢复；用于让诊断层区分“主动暂停”和“传输停滞”。 */
    private val onScreenOutputSuspended: (Boolean) -> Unit = {},
    private val uptimeMs: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    @Volatile
    private var width: Int = (width / 2) * 2

    @Volatile
    private var height: Int = (height / 2) * 2

    val videoWidth: Int get() = width
    val videoHeight: Int get() = height

    /**
     * CarWith 源尺寸是否与上次 [setVideoDimensions] 记录的不同。转码后 [videoWidth]/[videoHeight]
     * 固定为车机流尺寸，拿源尺寸比它会每帧都误判为格式变化（2026-11-01 审查）。
     */
    fun isNewSourceSize(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean = (sourceWidth / 2) * 2 != this.sourceWidth || (sourceHeight / 2) * 2 != this.sourceHeight

    /** 与车机协商的流尺寸（屏幕 SETUP 时定型）；转码时的输出尺寸。 */
    private val streamWidth: Int = (width / 2) * 2
    private val streamHeight: Int = (height / 2) * 2

    /** CarWith 实际 SPS 尺寸（0 = 尚未收到）。 */
    @Volatile
    private var sourceWidth = 0

    @Volatile
    private var sourceHeight = 0

    /** 透传失败后的手机端转码（null = 透传）；只切换一次，失败不再重试。 */
    @Volatile
    private var transcoder: CarWithTranscoder? = null

    /** 转码只尝试一次：事件线程与控制动作线程都可能触发，原子认领。 */
    private val transcodeClaimed = AtomicBoolean(false)
    private val keyFrameStorm = KeyFrameStorm()
    private val sustainedStormNoted = AtomicBoolean(false)

    /** 透传码流特征与车机要帧时序（诊断）；会话结束时汇总一行。 */
    private val streamStats = PassthroughStreamStats()
    private val streamStatsLogged = AtomicBoolean(false)
    private val screenOutputGate = ScreenOutputGate()
    private val screenSuspendedReported = AtomicBoolean(false)
    private val screenOwnershipErrorLogged = AtomicBoolean(false)
    private var lastStreamStatsLogAt = 0L

    /** 车机经屏幕数据口回传的字节数（正常为 0；非 0 或被车机关闭都值得记录）。 */
    private val inboundBytes =
        java.util.concurrent.atomic
            .AtomicLong()

    /** 最近一次车机 `forceKeyFrame` 距上一个已发 IDR 的毫秒数（null = 尚无 IDR）。 */
    @Volatile
    var lastReceiverRequestIdrGapMs: Long? = null
        private set

    /** 透传线程与转码编码线程都会在退出时收尾：onEnded 只通知一次。 */
    private val endedNotified = AtomicBoolean(false)

    /** 透传线程与转码编码线程可能同时写数据口：所有网络写入串行。 */
    private val writeLock = Any()

    /**
     * 转码资源发布与 [stop] 互斥：stop 之后才初始化完的编码器 / EGL 由初始化方自己释放。
     * 发布时会在持有本锁的情况下等 [writeLock]，而写线程可能阻塞在 socket 上，
     * 所以 stop 必须先关 socket、再取本锁（否则断开按钮会卡住主线程）。
     */
    private val lifecycleLock = Any()

    /** 正在把 CarWith 画面缩放转码（而非原样透传）。 */
    val isTranscoding: Boolean get() = transcoder != null

    /** 正在原样透传外部 H.264（关键帧请求只能转给 CarWith，无法保证立即生效）。 */
    val isExternalPassthrough: Boolean get() = externalVideoSource != null && transcoder == null

    @Volatile
    private var socket: Socket? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** 有序拆会话时停止新帧，暂留数据 socket，直到 TEARDOWN 后由 stop() 关闭。 */
    private val quiescing = AtomicBoolean(false)
    private var frameCounter = 0L
    private var configSent = false

    /** AES-CTR 密钥流实例：状态必须跨帧连续（每帧新建会导致对端解出乱码）。 */
    private val aesStream: AirPlayCrypto.Aes128CtrStream? =
        (frameCipher as? StreamFrameCipher.AesCtr)?.let { AirPlayCrypto.Aes128CtrStream(it.key, it.iv) }

    /** 上一次屏幕流 keepAlive 的时刻（uptimeMillis；0 = 尚未发过）。 */
    private var lastKeepAliveAt = 0L

    /** 两次 keepAlive 之间的帧数/字节数（统计体用，catplay ScreenSenderStats 同义）。 */
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L
    private var cachedVideoConfig: ByteArray? = null

    /** 主屏拿回后，下一个 CarWith IDR 前重发 VideoConfig 并内联 SPS/PPS（见 [reprimeOnNextKeyFrame]）。 */
    private val reprimePending = AtomicBoolean(false)
    private val encoderReprimePending = AtomicBoolean(false)
    private val externalQueue = java.util.concurrent.LinkedBlockingQueue<ExternalVideoFrame>(64)

    @Volatile
    private var framesSent = 0L

    /** 推流是否活跃（静默降噪守卫判断用：start 失败时保持 false）。 */
    val isRunning: Boolean get() = running.get()

    /** 已发送帧总数（英雄卡实时帧率 = 1s 轮询取差值）。 */
    val framesSentTotal: Long get() = framesSent

    internal val screenFramesSkippedTotal: Long
        get() = synchronized(screenOutputGate) { screenOutputGate.skippedFramesTotal }

    /** 转码恢复后需要重发编码器 VideoConfig；测试用。 */
    internal val isEncoderReprimePending: Boolean
        get() = encoderReprimePending.get()

    /** 透传恢复后需要重发 CarWith VideoConfig；测试用。 */
    internal val isPassthroughReprimePending: Boolean
        get() = reprimePending.get()

    /** 转码路径：门控已确认暂停时不再解码/编码；读取失败按暂停处理，与 [admitScreenFrame] 一致。 */
    private fun screenFeedPaused(): Boolean {
        val owns =
            try {
                phoneOwnsScreen()
            } catch (_: Exception) {
                false
            }
        if (owns) return false
        return synchronized(screenOutputGate) { screenOutputGate.isSuspended }
    }

    /**
     * 提前建立屏幕数据端口 TCP 连接（幂等）。
     *
     * 车机看门狗（2026-10-17 奥迪实测）：屏幕流 SETUP 之后 ~2s 内数据端口必须连上，
     * 否则直接 reset 控制通道拆掉整轮会话。音频 SETUP 本身要 ~2.1s，若排在它后面，
     * 端口连接必然迟到——所以协商音频前先调本方法。catplay 亦然（视频 SETUP 后立即
     * finish_tx 连流，再协商音频）。
     */
    fun openDataPort(): Boolean {
        if (stopped.get() || quiescing.get()) return false
        val existing = socket
        if (existing != null && !existing.isClosed) return true
        return try {
            val client = Socket()
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            // 推流是长连接：车机熄火/走出热点时链路常不发 RST，写入会阻塞在满窗口上，
            // 编码器线程随之卡死（表现为「画面停在最后一帧」）。
            TcpLiveness.configure(client, "视频数据口", log)
            val published =
                synchronized(lifecycleLock) {
                    if (stopped.get() || quiescing.get()) {
                        false
                    } else {
                        socket = client
                        true
                    }
                }
            if (!published) {
                client.close()
                return false
            }
            log(
                "VIDEO: 数据端口已连通 $host:$dataPort（等 RECORD 后启动编码器） " +
                    "sndBuf=${runCatching { client.sendBufferSize }.getOrNull()} " +
                    "rcvBuf=${runCatching { client.receiveBufferSize }.getOrNull()}",
            )
            startInboundMonitor(client)
            true
        } catch (error: Exception) {
            log("VIDEO: 数据端口连接失败 - ${error.message}")
            false
        }
    }

    /**
     * 屏幕数据口是单向的（手机 → 车机），但车机若回传数据或主动关闭，透传线程只能等到写失败才知道。
     * 单独读一遍：记下回传的字节与车机关闭时刻（帕萨特无画面时，用来区分「车机收了但解不出」与「车机已断」）。
     */
    private fun startInboundMonitor(client: Socket) {
        Thread({
            val buffer = ByteArray(2048)
            try {
                val input = client.getInputStream()
                while (!client.isClosed) {
                    val count =
                        try {
                            input.read(buffer)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                    if (count < 0) {
                        if (socket === client) log("VIDEO: 车机关闭了屏幕数据口（EOF，此前回传 ${inboundBytes.get()}B，已发 $framesSent 帧）")
                        return@Thread
                    }
                    val total = inboundBytes.addAndGet(count.toLong())
                    if (total == count.toLong() || total / 4096 != (total - count) / 4096) {
                        log(
                            "VIDEO: 车机经屏幕数据口回传 ${count}B（累计 ${total}B）前 32 字节=" +
                                buffer.take(minOf(count, 32)).joinToString(" ") { "%02x".format(it) },
                        )
                    }
                }
            } catch (error: Exception) {
                if (socket === client) log("VIDEO: 屏幕数据口读取结束 - ${error.message}（已发 $framesSent 帧）")
            }
        }, "video-inbound").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 启动编码与推流。编码器输入 Surface 通过 [onSurfaceReady] 交给调用方渲染
     * （隐形屏模式下由 DualDisplayComposer 的合成画布填充）。
     */
    fun start(onSurfaceReady: (Surface) -> Unit): Boolean {
        if (stopped.get() || quiescing.get()) return false
        if (running.get()) return true
        return try {
            if (!openDataPort()) {
                if (!quiescing.get()) stop()
                return false
            }
            if (externalVideoSource != null) {
                synchronized(lifecycleLock) {
                    if (stopped.get() || quiescing.get()) return false
                    running.set(true)
                    lastKeepAliveAt = android.os.SystemClock.uptimeMillis()
                }
                Thread({ drainExternalVideo() }, "video-external-drain").apply {
                    isDaemon = true
                    start()
                }
                reportScreenSuspended(false, force = true)
                log("VIDEO: 外部 CarWith H.264 输入已启动 ${width}x$height@$targetFps")
                return true
            }
            val (codec, surface) = createEncoder(width, height, targetFps)
            val published =
                synchronized(lifecycleLock) {
                    if (stopped.get() || quiescing.get()) {
                        false
                    } else {
                        encoder = codec
                        inputSurface = surface
                        running.set(true)
                        lastKeepAliveAt = android.os.SystemClock.uptimeMillis()
                        true
                    }
                }
            if (!published) {
                releaseVideoResources(null, codec, surface)
                return false
            }

            onSurfaceReady(surface)

            Thread({ drainEncoder() }, "video-drain").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("VIDEO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 建 H.264 硬编码器（Surface 输入）。本地画面源与转码共用：level 4.0 固定、
     * 被拒时回退自动选择的约束两者一致。
     */
    private fun createEncoder(
        encodeWidth: Int,
        encodeHeight: Int,
        fps: Int,
        /**
         * true = 用 KEY_MAX_FPS_TO_ENCODER 实际限帧（转码：源帧率不受控，车机声明了 maxFPS）。
         * KEY_FRAME_RATE 只是码率提示，不会丢帧。
         */
        capEncoderFps: Boolean = false,
        /**
         * true = 要求 Baseline profile（转码：与 CarWith 透传流同为 Baseline、无 CABAC）。帕萨特 2026-11-01 22:46：
         * 手机编码器默认出 High(100)+CABAC，切换后车机仍要关键帧、13 s 后关闭屏幕数据口。
         */
        baselineProfile: Boolean = false,
    ): Pair<MediaCodec, Surface> {
        val bitrate =
            (
                ((encodeWidth * encodeHeight * 6L).toInt()).coerceIn(2_000_000, 10_000_000) *
                    if (fps >= 60) 1.5f else 1f
            ).toInt().coerceAtMost(15_000_000)
        log("VIDEO: 启动编码器 ${encodeWidth}x$encodeHeight@$fps ${bitrate / 1_000_000}Mbps（端口已连通）")

        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (capEncoderFps) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                if (baselineProfile) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                // 固定 H.264 level 4.0（SPS level_idc=40，真 iPhone 同款流形态）。
                // 不固定时编码器按「分辨率+帧率」自动选：默认 30fps 下 1412x654
                // 合法落入 level 3.2——奥迪 DELPHI 解码器对该 level 静默不渲染
                // （TCP 照收、会话活着、车机黑屏，2026-10-22 奥迪 0628 真机日志
                // 定谳：同机 level=40 全部成功、level=32 首例黑屏）。60fps 时编码
                // 器本来就选 4.0，此设置对它无变化。
                runCatching {
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                    )
                }
            }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // 个别编码器不接受 level 指定（configure 抛异常=视频全灭），降级为不指定重试
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (levelRejected: Exception) {
            log("VIDEO: 指定 level 4.0${if (baselineProfile) " / Baseline" else ""} 被编码器拒绝（${levelRejected.message}）——回退自动选择（部分车机可能黑屏）")
            val fallback =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (capEncoderFps) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                }
            codec.configure(fallback, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = codec.createInputSurface()
        codec.start()
        return codec to surface
    }

    fun stop() {
        stopped.set(true)
        running.set(false)
        externalQueue.clear()
        // 先关 socket：解开阻塞在写入上的线程（它持有 writeLock，转码发布方持有 lifecycleLock 等它）
        val closing = socket
        socket = null
        runCatching { closing?.close() }
        val active: CarWithTranscoder?
        val codec: MediaCodec?
        val surface: Surface?
        synchronized(lifecycleLock) {
            running.set(false)
            active = transcoder
            codec = encoder
            surface = inputSurface
            transcoder = null
            encoder = null
            inputSurface = null
        }
        releaseVideoResources(active, codec, surface)
        if (externalVideoSource != null && streamStatsLogged.compareAndSet(false, true)) {
            val gate = synchronized(screenOutputGate) { screenOutputGate.summary() }
            log("VIDEO: 透传码流统计（会话结束）${streamStats.summary()}\n  ↳ 屏幕输出门控：$gate")
        }
    }

    /** 不等待写锁或关闭 socket：阻塞中的写仍可由随后的 stop() 解开。 */
    fun quiesceForOrderlyStop() {
        quiescing.set(true)
        externalQueue.clear()
    }

    /** 先停转码（停止往编码器 Surface 渲染），再释放编码器与其输入 Surface。 */
    private fun releaseVideoResources(
        pipeline: CarWithTranscoder?,
        codec: MediaCodec?,
        surface: Surface?,
    ) {
        pipeline?.let { runCatching { it.stop() } }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
    }

    /**
     * 立即编码一个关键帧（车机 `/command` 的 `forceKeyFrame` 请求）。
     *
     * 车机解不出画面时会反复要 I 帧；我们本身每秒一个，但**要帧请求必须立即响应**——
     * 否则车机可能一直卡在「等下一个 I 帧」的状态，表现就是「连上了但没画面」
     * （宝马 iD9 2026-10-20 实测：会话刚起时收到一次 forceKeyFrame）。
     *
     * [fromReceiver] = false 表示我们自己补的 I 帧（如 requestUI 后），不计入要帧风暴与诊断统计。
     */
    fun requestKeyFrame(fromReceiver: Boolean = true): Boolean {
        if (quiescing.get()) return false
        if (fromReceiver && externalVideoSource != null) {
            lastReceiverRequestIdrGapMs =
                streamStats.onReceiverKeyFrameRequest(android.os.SystemClock.uptimeMillis(), transcoding = transcoder != null)
        }
        if (externalVideoSource != null && transcoder == null) {
            val storm =
                if (fromReceiver && !transcodeClaimed.get()) {
                    keyFrameStorm.record(android.os.SystemClock.uptimeMillis())
                } else {
                    KeyFrameStorm.Level.NONE
                }
            val sizeMismatch = TranscodePolicy.needsTranscode(sourceWidth, sourceHeight, streamWidth, streamHeight)
            if (storm == KeyFrameStorm.Level.SUSTAINED && !sizeMismatch && sustainedStormNoted.compareAndSet(false, true)) {
                log(
                    "VIDEO: 车机持续要关键帧，但 CarWith 与车机流尺寸一致——多为主屏此刻不归手机（见资源仲裁 modesChanged），" +
                        "不切换转码（帕萨特 2026-11-01：同尺寸透传在主屏归手机时可正常出画）",
                )
            }
            if (TranscodePolicy.shouldTranscode(storm, sizeMismatch) &&
                transcodeClaimed.compareAndSet(false, true)
            ) {
                // 建编码器 + EGL 最长数秒：不能占用车机事件读取线程（2026-11-01 审查），
                // 后台只起这一次；准备期间关键帧请求仍转给 CarWith
                Thread({ startTranscoding(storm) }, "video-transcode-start").apply {
                    isDaemon = true
                    start()
                }
            }
            if (transcoder == null) return externalVideoSource.requestKeyFrame()
        }
        val codec = encoder ?: return false
        return try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            true
        } catch (error: Exception) {
            log("VIDEO: 请求关键帧失败 - ${error.message}")
            false
        }
    }

    /**
     * 车机把主屏还给手机（用户点 CarPlay 图标）后调用：下一个 CarWith IDR 前重发 VideoConfig，
     * 并在该 IDR 里内联 SPS/PPS。
     *
     * 帕萨特 2025 2026-11-01 23:22：开场拿回主屏能出画；用户回原车主界面（changeModes take）后再点图标，
     * 手机报回「主屏=手机」，车机既不出画也不再要关键帧。CarWith 只有首个 IDR 带 SPS/PPS，
     * 参数集只在开流时随 VideoConfig 发过一次——车机回原车界面若拆掉了解码器，之后的 IDR 无从解码。
     */
    fun reprimeOnNextKeyFrame() {
        if (externalVideoSource != null && transcoder == null) reprimePending.set(true)
    }

    /**
     * 按主屏归属决定本帧是否发送。
     *
     * 暂停期间连 IDR 也不发：车机正在显示原车界面或倒车影像，任何视频帧都属于无意义流量。
     * 归还后必须等新的 IDR，因为暂停前的参考帧已经无法接续。
     */
    internal fun admitScreenFrame(
        keyFrame: Boolean,
        passthrough: Boolean,
    ): Boolean {
        // 读取失败时按“车机持有”暂停：宁可晚一帧恢复，也不要把画面继续推给正在显示
        // 原车界面/倒车影像的车机（失败方向必须保守）。
        val ownsScreen =
            try {
                phoneOwnsScreen()
            } catch (error: Exception) {
                if (screenOwnershipErrorLogged.compareAndSet(false, true)) {
                    log("VIDEO: 主屏归属读取失败，按车机持有处理（暂停发帧）- ${error.message}")
                }
                false
            }
        val decision =
            synchronized(screenOutputGate) {
                screenOutputGate.admit(ownsScreen, keyFrame, uptimeMs())
            }
        when (decision.transition) {
            ScreenOutputGate.Transition.PAUSED -> {
                reportScreenSuspended(true)
                log("VIDEO: 主屏归车机——暂停发送全部视频帧（keepAlive 继续；归还后从新的 IDR 恢复）")
            }

            ScreenOutputGate.Transition.RESUMING -> {
                log(
                    "VIDEO: 主屏已归还——等待新的 IDR 恢复（本次暂停已跳过 ${decision.skippedThisPause} 帧）",
                )
                prepareScreenResume(passthrough)
            }

            ScreenOutputGate.Transition.RESUMED -> {
                reportScreenSuspended(false)
                log("VIDEO: 主屏输出恢复——首个 IDR 已放行（本次暂停共跳过 ${decision.skippedThisPause} 帧）")
                prepareScreenResume(passthrough)
            }

            null -> Unit
        }
        if (decision.requestKeyFrame) {
            val requested = requestKeyFrame(fromReceiver = false)
            if (decision.transition == null) {
                log(
                    "VIDEO: 等待 IDR 超时——重试关键帧请求（第 ${decision.keyFrameRequestAttempt} 次，" +
                        "结果=${if (requested) "已提交" else "未提交"}）",
                )
            }
        }
        return decision.send
    }

    private fun reportScreenSuspended(
        suspended: Boolean,
        force: Boolean = false,
    ) {
        if (!force && screenSuspendedReported.getAndSet(suspended) == suspended) return
        screenSuspendedReported.set(suspended)
        runCatching { onScreenOutputSuspended(suspended) }
    }

    /** 暂停可能持续到车机拆掉解码器，恢复时重新声明参数集。 */
    private fun prepareScreenResume(passthrough: Boolean) {
        if (passthrough) {
            reprimePending.set(true)
        } else {
            encoderReprimePending.set(true)
        }
    }

    /** 转码 / 本地编码路径恢复时重发编码器 VideoConfig。 */
    private fun resendVideoConfig(avcC: ByteArray) {
        val output = socket?.getOutputStream() ?: return
        synchronized(writeLock) {
            if (quiescing.get()) return
            output.write(buildVideoConfigHeader(avcC.size, width, height))
            output.write(avcC)
            output.flush()
            log("VIDEO: 主屏输出恢复——重发 VideoConfig avcC=${avcC.size}B ${width}x$height")
        }
    }

    // ---- 编码输出 → 协议封装发送 ----

    /**
     * Submit one complete Annex B access unit from CarWith.
     *
     * Returns false when the bounded queue is full; callers should drop the
     * packet rather than block CarLife's video reader.
     */
    fun submitExternalFrame(frame: ExternalVideoFrame): Boolean =
        if (externalVideoSource == null || !running.get() || quiescing.get()) {
            false
        } else {
            externalQueue.offer(frame).also { accepted ->
                if (!accepted) {
                    val drops = streamStats.onQueueDrop()
                    if (drops <= 3 || drops % 50 == 0L) {
                        log("VIDEO: 透传队列已满（${externalQueue.size} 帧），丢弃 CarWith 帧，累计 $drops 次（随后等下一个 IDR）")
                    }
                }
            }
        }

    /** Align the CarPlay canvas to the SPS dimensions from CarWith. */
    fun setVideoDimensions(
        newWidth: Int,
        newHeight: Int,
    ) {
        if (newWidth <= 0 || newHeight <= 0) return
        sourceWidth = (newWidth / 2) * 2
        sourceHeight = (newHeight / 2) * 2
        // 转码中画布固定为车机流尺寸；CarWith 尺寸变化交给解码器自适应
        if (transcoder != null) return
        width = sourceWidth
        height = sourceHeight
        cachedVideoConfig = null
    }

    /**
     * 透传 → 转码（只尝试一次）。建编码器（车机流尺寸、帧率不超 maxFPS）与转码管线，
     * 之后 CarWith 帧改喂解码器，编码输出走与本地画面源相同的发送路径。
     * 失败则保持透传。
     */
    @Synchronized
    private fun startTranscoding(storm: KeyFrameStorm.Level) {
        if (!running.get() || quiescing.get()) return
        val fps = TranscodePolicy.cappedFps(targetFps, receiverMaxFps)
        val reason =
            if (TranscodePolicy.needsTranscode(sourceWidth, sourceHeight, streamWidth, streamHeight)) {
                "CarWith ${sourceWidth}x$sourceHeight 与车机流 ${streamWidth}x$streamHeight 不符，"
            } else {
                "CarWith ${sourceWidth}x$sourceHeight 与车机流尺寸一致，"
            }
        val cadence = if (storm == KeyFrameStorm.Level.SUSTAINED) "持续" else "密集"
        log(
            "VIDEO: $reason" + "且车机${cadence}要关键帧（透传解不出画）——切换为手机端转码 ${streamWidth}x$streamHeight@$fps" +
                "\n  ↳ 切换前透传统计：${streamStats.summary()}",
        )
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var pipeline: CarWithTranscoder? = null
        var published = false
        try {
            val created = createEncoder(streamWidth, streamHeight, fps, capEncoderFps = true, baselineProfile = true)
            codec = created.first
            surface = created.second
            pipeline =
                CarWithTranscoder(surface, streamWidth, streamHeight, sourceWidth, sourceHeight, log) {
                    externalVideoSource?.requestKeyFrame()
                }
            pipeline.start()
            // 初始化可能耗时数秒：期间断开的话 stop() 已收尾，资源不再发布到字段
            published =
                synchronized(lifecycleLock) {
                    if (!running.get() || quiescing.get()) return@synchronized false
                    synchronized(writeLock) {
                        width = streamWidth
                        height = streamHeight
                        configSent = false
                        cachedVideoConfig = null
                        encoder = codec
                        inputSurface = surface
                        transcoder = pipeline
                    }
                    true
                }
            if (!published) {
                log("VIDEO: 转码初始化期间会话已停止，释放转码资源")
                return
            }
            Thread({ drainEncoder() }, "video-transcode-drain").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            log("VIDEO: 转码启动失败，保持透传 - ${error.message}")
        } finally {
            if (!published) releaseVideoResources(pipeline, codec, surface)
        }
    }

    private fun drainExternalVideo() {
        val output = socket?.getOutputStream() ?: return
        try {
            while (running.get()) {
                val frame = externalQueue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                // 转码中：帧改喂解码器，数据口（含 keepAlive）由编码线程独占
                val pipeline = transcoder
                if (quiescing.get()) continue
                if (pipeline != null) {
                    // 门控已确认暂停后停止喂帧：解码/编码一场倒车影像的输入是纯功耗；
                    // 恢复时继续喂帧，由编码线程等新的 IDR 再放行输出。
                    if (frame != null && !screenFeedPaused()) pipeline.submit(frame)
                    continue
                }
                if (frame == null) {
                    maybeSendKeepAlive(output)
                    continue
                }
                var nalUnits = splitAnnexB(frame.data)
                if (nalUnits.isEmpty()) continue
                if (!admitScreenFrame(frame.keyFrame, passthrough = true)) {
                    maybeSendKeepAlive(output)
                    continue
                }
                if (frame.keyFrame && reprimePending.compareAndSet(true, false)) {
                    val inlined = withParameterSets(nalUnits, frame.sps, frame.pps)
                    log(
                        "VIDEO: 主屏拿回后重发 VideoConfig" +
                            if (inlined.size > nalUnits.size) "，本 IDR 补内联 SPS/PPS（来自解析器缓存）" else "（本 IDR 已带 SPS/PPS）",
                    )
                    nalUnits = inlined
                    cachedVideoConfig = null
                }
                val sps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
                val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
                if (sps != null && pps != null) {
                    val config = buildAvcC(sps, pps)
                    if (!config.contentEquals(cachedVideoConfig) && sendVideoConfigBytes(config, output)) {
                        log(
                            "VIDEO: CarWith 参数集 ${PassthroughStreamStats.describeParameterSets(sps, pps)}" +
                                "（发出时 level 补丁为 ${config[3].toInt() and 0xFF}）",
                        )
                    }
                }
                if (!configSent) continue
                val packed = packLengthPrefixed(nalUnits)
                val writeStart = android.os.SystemClock.uptimeMillis()
                if (!sendPlainFrame(packed, frame.keyFrame, output)) continue
                val now = android.os.SystemClock.uptimeMillis()
                streamStats.onFrameSent(nalUnits, frame.keyFrame, now, packed.size, now - writeStart, externalQueue.size)
                if (framesSent == STREAM_STATS_EARLY_FRAMES) {
                    lastStreamStatsLogAt = now
                    log("VIDEO: 透传码流统计（前 $framesSent 帧）${streamStats.summary()}")
                } else if (framesSent > STREAM_STATS_EARLY_FRAMES && now - lastStreamStatsLogAt >= STREAM_STATS_INTERVAL_MS) {
                    lastStreamStatsLogAt = now
                    log("VIDEO: 透传码流统计（第 $framesSent 帧）${streamStats.summary()}")
                }
                runCatching { onExternalFrameSent?.invoke(packed.size) }
                maybeSendKeepAlive(output)
            }
        } catch (error: Exception) {
            log("VIDEO: 外部视频推流中止 - ${error.message}")
        } finally {
            stop()
            if (endedNotified.compareAndSet(false, true)) runCatching { onEnded?.invoke() }
        }
    }

    private fun packLengthPrefixed(nalUnits: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nalUnits) {
            val value = if (nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == 7) patchSpsLevel(nal) else nal
            out.write((value.size ushr 24) and 0xFF)
            out.write((value.size ushr 16) and 0xFF)
            out.write((value.size ushr 8) and 0xFF)
            out.write(value.size and 0xFF)
            out.write(value)
        }
        return out.toByteArray()
    }

    private fun buildAvcC(
        rawSps: ByteArray,
        pps: ByteArray,
    ): ByteArray {
        val sps = patchSpsLevel(rawSps)
        return ByteArrayOutputStream(11 + sps.size + pps.size)
            .apply {
                write(1)
                write(sps[1].toInt())
                write(sps[2].toInt())
                write(sps[3].toInt())
                write(0xFF)
                write(0xE1)
                write((sps.size ushr 8) and 0xFF)
                write(sps.size and 0xFF)
                write(sps)
                write(1)
                write((pps.size ushr 8) and 0xFF)
                write(pps.size and 0xFF)
                write(pps)
            }.toByteArray()
    }

    /** 返回是否已发出（已切换转码时丢弃）。 */
    private fun sendVideoConfigBytes(
        avcC: ByteArray,
        output: java.io.OutputStream,
    ): Boolean =
        synchronized(writeLock) {
            // 已切换转码：透传线程在切换前取到的旧配置不得混进新流
            if (transcoder != null || quiescing.get()) return@synchronized false
            val header = buildVideoConfigHeader(avcC.size, width, height)
            output.write(header)
            output.write(avcC)
            output.flush()
            cachedVideoConfig = avcC
            configSent = true
            log(
                "VIDEO: ✓ CarWith VideoConfig 已发送 avcC=${avcC.size}B ${width}x$height" +
                    "\n  ↳ 头部前 24 字节=" + header.take(24).joinToString(" ") { "%02x".format(it) } +
                    "\n  ↳ avcC=" + avcC.joinToString(" ") { "%02x".format(it) },
            )
            true
        }

    /** 返回是否已发出（已切换转码时丢弃）。 */
    private fun sendPlainFrame(
        plain: ByteArray,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ): Boolean =
        synchronized(writeLock) {
            // 已切换转码：透传线程在切换前取到的旧格式帧不得跨配置发出
            if (transcoder != null || quiescing.get()) return@synchronized false
            val header =
                buildHeader(
                    OP_FRAME,
                    if (frameCipher is StreamFrameCipher.ChaCha) plain.size + TAG_SIZE else plain.size,
                )
            val sealed =
                when (val cipher = frameCipher) {
                    is StreamFrameCipher.ChaCha ->
                        AirPlayCrypto.chachaSeal(cipher.key, AirPlayCrypto.nonce64(frameCounter), plain, header)

                    is StreamFrameCipher.AesCtr -> {
                        val encrypted = plain.copyOf()
                        aesStream?.applyKeystream(encrypted)
                        encrypted
                    }
                }
            frameCounter++
            output.write(header)
            output.write(sealed)
            output.flush()
            framesSent++
            framesSinceKeepAlive++
            bytesSinceKeepAlive += plain.size.toLong()
            if (keyFrame || framesSent <= 3 || framesSent % 150 == 0L) {
                log("VIDEO: CarWith 帧 #$framesSent ${plain.size}B${if (keyFrame) " (key)" else ""} 已发送")
            }
            true
        }

    private fun drainEncoder() {
        val codec = encoder ?: return
        val output = socket?.getOutputStream() ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index =
                    try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (error: Exception) {
                        // 编码器进错误态（如非法尺寸首帧）：必须留痕——静默 break 曾让
                        // 「零帧推流 4 秒后断开」无从排查（接收端只见 peer EOF）
                        log("VIDEO: 编码器取帧异常，停止推流 - ${error.message}")
                        break
                    }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        maybeSendKeepAlive(output)
                        continue
                    }

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        log("VIDEO: 编码器输出格式 ${runCatching { codec.outputFormat.toString() }.getOrNull()}（${codec.name}）")
                        continue
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        val flags = bufferInfo.flags
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sendVideoConfig(buffer, bufferInfo.size, output)
                        } else if (bufferInfo.size > 0) {
                            val keyFrame = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val sent =
                                if (admitScreenFrame(keyFrame, passthrough = false)) {
                                    if (keyFrame && encoderReprimePending.compareAndSet(true, false)) {
                                        cachedVideoConfig?.let { resendVideoConfig(it) }
                                    }
                                    sendVideoFrame(buffer, bufferInfo.size, keyFrame, output)
                                } else {
                                    0
                                }
                            // 转码输出顶替了 CarWith 透传：诊断时间线的帧数 / 字节 / 时间戳照常更新，
                            // 否则 5 s 后会误报视频停滞（2026-11-01 审查）
                            if (sent > 0 && externalVideoSource != null) {
                                runCatching { onExternalFrameSent?.invoke(sent) }
                                val now = android.os.SystemClock.uptimeMillis()
                                streamStats.onTranscodedFrameSent(keyFrame, now)
                                if (now - lastStreamStatsLogAt >= STREAM_STATS_INTERVAL_MS) {
                                    lastStreamStatsLogAt = now
                                    log(
                                        "VIDEO: 转码统计（已发 $framesSent 帧）${transcoder?.statsLine() ?: ""}" +
                                            "\n  ↳ ${streamStats.summary()}",
                                    )
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        maybeSendKeepAlive(output)
                    }
                }
            }
        } catch (error: Exception) {
            // 视频数据口断开（如接收端屏幕关闭收摊会话）：只终止推流，绝不冒泡——
            // 后台线程未捕获异常会杀死整个进程（曾导致锁屏后 app 消失）
            log("VIDEO: 推流中止 - ${error.message}（等待接收端恢复后重连）")
        } finally {
            stop()
            // 会话真实结束信号（静默降噪解除 / 状态收尾）
            if (endedNotified.compareAndSet(false, true)) runCatching { onEnded?.invoke() }
        }
    }

    /**
     * 屏幕流 keepAlive 帧（catplay `ScreenTransmitSession::create_keep_alive`，1s 间隔）。
     *
     * 与 UDP keepAlive / RTSP feedback 是三条相互独立的存活信号；接收端（奥迪 3315）
     * 只看视频帧不够——缺这条时 RECORD 后约 10s 会 reset 整轮会话（2026-10-19 两轮
     * 实测）。帧是**明文**的（catplay 仅对 opcode=VideoFrame 加密），因此不消耗 ChaCha
     * 帧计数；带统计体时 opcode=5，并把 body 长度写进头部 params[14] 的后 4 字节
     * （f32 LE），与 catplay 逐字节一致。
     */
    private fun maybeSendKeepAlive(output: java.io.OutputStream) {
        synchronized(writeLock) { maybeSendKeepAliveLocked(output) }
    }

    private fun maybeSendKeepAliveLocked(output: java.io.OutputStream) {
        if (!running.get() || quiescing.get()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastKeepAliveAt < KEEPALIVE_INTERVAL_MS) return
        val elapsedNanos = (now - lastKeepAliveAt).coerceAtLeast(1L) * 1_000_000.0
        val frames = framesSinceKeepAlive
        val bytes = bytesSinceKeepAlive
        val framesPerSecond = (frames * 1_000_000_000.0 / elapsedNanos).toInt()
        val bytesPerSecond = bytes * 1_000_000_000.0 / elapsedNanos
        framesSinceKeepAlive = 0
        bytesSinceKeepAlive = 0
        lastKeepAliveAt = now
        keepAliveCount++
        val frame = buildKeepAliveFrame(keepAliveWithStats, frames, bytesPerSecond, framesPerSecond)
        output.write(frame)
        output.flush()
        if (keepAliveCount <= 3 || keepAliveCount % 30 == 0L) {
            log(
                "VIDEO: keepAlive #$keepAliveCount " +
                    "opcode=${if (keepAliveWithStats) OP_KEEP_ALIVE_WITH_BODY else OP_KEEP_ALIVE} " +
                    "body=${frame.size - HEADER_LEN}B fps=$framesPerSecond",
            )
        }
    }

    private fun sendVideoConfig(
        buffer: java.nio.ByteBuffer,
        size: Int,
        output: java.io.OutputStream,
    ): Unit =
        synchronized(writeLock) {
            if (quiescing.get()) return
            val annexB = ByteArray(size)
            buffer.get(annexB)
            val nalUnits = splitAnnexB(annexB)
            val rawSps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
            val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
            if (rawSps == null || pps == null) {
                log("VIDEO: csd 缺少 SPS/PPS（${nalUnits.size} 个 NAL），跳过 config")
                return
            }
            val body = buildAvcC(rawSps, pps)
            // 头部必须与 catplay `ScreenFrame::config` 一致，否则部分车机（Audi/DELPHI 实测：
            // 连接与触摸都正常、车机屏全黑）会把后续加密帧按明文 NAL 解析 → 解码失败：
            //  - small_param[1] |= ScreenFlag::Encrypted(0x04)：声明「视频帧是加密的」
            //  - params[1] = (width, height) 两个 f32 LE：车机据此建立解码器画布
            //  - params[0] 归零（catplay 的 config 不带 PTS；只有 VideoFrame 才写 params[0]）
            // 旧实现三项都没写——DiPlay/WinPlay 只读 body、不校验头，所以测试台一直正常，
            // 真车（Audi 4851）却黑屏（2026-10-19 用户日志）。
            val header = buildVideoConfigHeader(body.size, width, height)
            output.write(header)
            output.write(body)
            output.flush()
            configSent = true
            log(
                "VIDEO: ✓ VideoConfig 已发送 avcC=${body.size}B（SPS ${rawSps.size}B/PPS ${pps.size}B，" +
                    "profile=${rawSps[1].toInt() and 0xFF} level=${rawSps[3].toInt() and 0xFF} " +
                    "flags=0x%02x ${width}x$height）".format(header[6].toInt() and 0xFF) +
                    "\n  ↳ 头部前 24 字节=" + header.take(24).joinToString(" ") { "%02x".format(it) } +
                    "\n  ↳ 参数集 ${PassthroughStreamStats.describeParameterSets(rawSps, pps)}",
            )
            cachedVideoConfig = body
        }

    /** 返回已发出的明文字节数（0 = 丢弃）。 */
    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ): Int =
        synchronized(writeLock) {
            if (quiescing.get()) return 0
            if (!configSent) {
                log("VIDEO: 丢弃一帧（config 尚未发送）")
                return 0
            }
            val annexB = ByteArray(size)
            buffer.get(annexB)
            val nalUnits = splitAnnexB(annexB)
            if (nalUnits.isEmpty()) return 0

            // 关键帧内嵌的 SPS 同步打 level 补丁：车机会用帧内 SPS 更新解码器参数，
            // 只改 csd 不改帧内 SPS 的话，收到第一个关键帧就又变回低 level 了
            val packed = ByteArrayOutputStream(size)
            for (nal in nalUnits) {
                val out =
                    if (nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == 7) patchSpsLevel(nal) else nal
                packed.write((out.size ushr 24) and 0xFF)
                packed.write((out.size ushr 16) and 0xFF)
                packed.write((out.size ushr 8) and 0xFF)
                packed.write(out.size and 0xFF)
                packed.write(out)
            }
            val plain = packed.toByteArray()

            // 加密方式按会话的 et 选择（catplay ScreenFrameCodec）：
            //  - ChaCha：header 作 AAD，body 长度含 16B tag；
            //  - AES-128-CTR：无 tag、不改长度，密钥流跨帧连续。
            // keepAlive/config 帧在两种模式下都不加密。头只能构造一次——ChaCha 的 AAD
            // 必须是最终发出的那 128 字节（重复构造会带不同时间戳，对端解密必然失败）。
            val header =
                buildHeader(
                    OP_FRAME,
                    if (frameCipher is StreamFrameCipher.ChaCha) plain.size + TAG_SIZE else plain.size,
                )
            val sealed =
                when (val cipher = frameCipher) {
                    is StreamFrameCipher.ChaCha ->
                        AirPlayCrypto.chachaSeal(cipher.key, AirPlayCrypto.nonce64(frameCounter), plain, header)
                    is StreamFrameCipher.AesCtr -> {
                        val encrypted = plain.copyOf()
                        aesStream?.applyKeystream(encrypted)
                        encrypted
                    }
                }
            frameCounter++
            output.write(header)
            output.write(sealed)
            output.flush()
            framesSent++
            framesSinceKeepAlive++
            bytesSinceKeepAlive += plain.size.toLong()
            if (keyFrame || framesSent <= 3 || framesSent % 150 == 0L) {
                log("VIDEO: 帧 #$framesSent ${plain.size}B${if (keyFrame) " (key)" else ""} 已发送")
            }
            plain.size
        }

    /** 128 字节消息头：u32LE body 长度、opcode、NTP 32.32 LE 时间戳，其余为零。 */
    private fun buildHeader(
        opcode: Int,
        bodyLength: Int,
    ): ByteArray {
        val header = ByteArray(HEADER_LEN)
        header[0] = (bodyLength and 0xFF).toByte()
        header[1] = ((bodyLength ushr 8) and 0xFF).toByte()
        header[2] = ((bodyLength ushr 16) and 0xFF).toByte()
        header[3] = ((bodyLength ushr 24) and 0xFF).toByte()
        header[4] = opcode.toByte()
        val nanos = System.nanoTime()
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ntp = (seconds shl 32) or fraction
        for (index in 0 until 8) {
            header[8 + index] = ((ntp ushr (8 * index)) and 0xFF).toByte()
        }
        return header
    }

    private fun writeMessage(
        opcode: Int,
        body: ByteArray,
        declaredLength: Int,
        output: java.io.OutputStream,
    ) {
        output.write(buildHeader(opcode, declaredLength))
        output.write(body)
        output.flush()
    }

    /** 按 00 00 00 01 / 00 00 01 起始码切分 AnnexB 码流为 NAL 列表。 */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> = AnnexB.split(bytes)

    companion object {
        /** IDR 缺 SPS 或 PPS 时用缓存补在最前（AUD 之后）；缓存缺一个就原样返回。 */
        internal fun withParameterSets(
            nalUnits: List<ByteArray>,
            sps: ByteArray?,
            pps: ByteArray?,
        ): List<ByteArray> {
            fun has(type: Int) = nalUnits.any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == type }
            if (has(7) && has(8)) return nalUnits
            if (sps == null || pps == null) return nalUnits
            val lead = nalUnits.takeWhile { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 9 }
            val rest =
                nalUnits.drop(lead.size).filterNot { it.isNotEmpty() && (it[0].toInt() and 0x1F).let { t -> t == 7 || t == 8 } }
            return lead + listOf(sps, pps) + rest
        }

        private const val HEADER_LEN = 128
        private const val OP_FRAME = 0
        private const val OP_CONFIG = 1
        private const val OP_KEEP_ALIVE = 2
        private const val OP_KEEP_ALIVE_WITH_BODY = 5
        private const val TAG_SIZE = 16

        /** 透传前这么多帧后先记一次码流统计（会话可能在车机无画面时被用户中途停掉）。 */
        private const val STREAM_STATS_EARLY_FRAMES = 300L

        /** 之后每隔这么久再记一次（用户可能随时停止或熄火，结束时的汇总不一定写得出来）。 */
        private const val STREAM_STATS_INTERVAL_MS = 30_000L

        /**
         * 头部位标志（catplay `ScreenFlag`）：写进 `small_param[1]`（头部偏移 6）。
         * `Encrypted = 1<<2`：视频帧是 ChaCha 密封的；VideoConfig 里必须声明，
         * 否则车机按明文 NAL 解析 → 解码失败黑屏（会话与触摸仍正常）。
         */
        private const val SCREEN_FLAG_ENCRYPTED = 0x04

        /** VideoConfig 头部 `params[1]` 的偏移：8 + 1×8，两个 f32 LE = 宽、高。 */
        private const val CONFIG_WIDTH_HEIGHT_OFFSET = 16

        /** 屏幕流 keepAlive 间隔（catplay `DEFAULT_KEEP_ALIVE` = 1s）。 */
        private const val KEEPALIVE_INTERVAL_MS = 1_000L

        /**
         * 构造屏幕流 keepAlive 帧（明文头 + 可选 bplist 统计体）。
         *
         * 拆成纯函数是为了单测：帧格式一旦写错（opcode/body 长度字段/统计体键名），
         * 接收端会静默忽略而不报错——现象仍是 10 秒后被拆链，排查成本极高。
         */
        internal fun buildKeepAliveFrame(
            withStats: Boolean,
            frames: Int,
            bytesPerSecond: Double,
            framesPerSecond: Int,
        ): ByteArray {
            val body =
                if (withStats) {
                    // 字段名对照 catplay ScreenSenderStats（serde camelCase）
                    BinaryPlist.encode(
                        linkedMapOf(
                            "txUsageAvg" to bytesPerSecond,
                            "encoderCurrentFPS" to framesPerSecond.toLong(),
                            "sentFramesAvg" to frames.toLong(),
                            "queuedFramesAvg" to 0L,
                            "lossAvg" to 0.0,
                        ),
                    )
                } else {
                    ByteArray(0)
                }
            val header = ByteArray(HEADER_LEN)
            // 头部布局：u32LE body 长度、u8 opcode、3 字节小参数、15 × 8 字节参数
            header[0] = (body.size and 0xFF).toByte()
            header[1] = ((body.size ushr 8) and 0xFF).toByte()
            header[2] = ((body.size ushr 16) and 0xFF).toByte()
            header[3] = ((body.size ushr 24) and 0xFF).toByte()
            header[4] = (if (body.isEmpty()) OP_KEEP_ALIVE else OP_KEEP_ALIVE_WITH_BODY).toByte()
            if (body.isNotEmpty()) {
                // params[14]（偏移 8+14*8=120）：前 4 字节 0.0f，后 4 字节 body 长度 f32 LE
                writeLittleEndianFloat(header, 120, 0f)
                writeLittleEndianFloat(header, 124, body.size.toFloat())
            }
            return if (body.isEmpty()) header else header + body
        }

        /**
         * VideoConfig 帧头（catplay `ScreenFrame::config` 逐字节对齐）：
         *  - `small_param[1]`（偏移 6）置 Encrypted(0x04) —— 声明后续视频帧是密封的
         *  - `params[1]`（偏移 16）= 宽、高两个 f32 LE —— 车机据此建解码器画布
         *  - `params[0]`（偏移 8..16）保持 0 —— config 不带 PTS
         *
         * 拆成纯函数是为了单测：真车（Audi 4851）缺这两项时**会话不断、触摸正常、屏全黑**，
         * 日志里看不出任何错误，极难排查。
         */
        internal fun buildVideoConfigHeader(
            bodySize: Int,
            width: Int,
            height: Int,
        ): ByteArray {
            val header = ByteArray(HEADER_LEN)
            header[0] = (bodySize and 0xFF).toByte()
            header[1] = ((bodySize ushr 8) and 0xFF).toByte()
            header[2] = ((bodySize ushr 16) and 0xFF).toByte()
            header[3] = ((bodySize ushr 24) and 0xFF).toByte()
            header[4] = OP_CONFIG.toByte()
            header[6] = SCREEN_FLAG_ENCRYPTED.toByte()
            for (index in 0 until 4) {
                val widthBits = width.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + index] = ((widthBits ushr (8 * index)) and 0xFF).toByte()
                val heightBits = height.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + 4 + index] = ((heightBits ushr (8 * index)) and 0xFF).toByte()
            }
            return header
        }

        private fun writeLittleEndianFloat(
            target: ByteArray,
            offset: Int,
            value: Float,
        ) {
            val bits = value.toRawBits()
            for (index in 0 until 4) {
                target[offset + index] = ((bits ushr (8 * index)) and 0xFF).toByte()
            }
        }
    }
}

/** H.264 level_idc 目标值：4.0（0x28）。真 iPhone 同款流形态。 */
private const val TARGET_LEVEL_IDC = 40

/**
 * 把 SPS 的 level_idc 补丁到 4.0（3.1→4.0 是放宽约束，安全）。
 *
 * 为什么不改用 MediaFormat.KEY_LEVEL：实测（MagicOS/Android 17）编码器**静默忽略**
 * 该键——不抛异常、SPS 里仍是 3.1。只能后处理。
 *
 * 为什么安全：① level_idc 在 SPS 的固定偏移（nal[3]），单字节改值不改长度；
 * ② 3.1→4.0 只放宽 maxMBPS/maxFS/maxBR 上限，解码器按 4.0 分配的资源对 3.1 编码
 * 的流只多不少；③ 0x28 非零且不会引入 emulation prevention 序列（00 00 0x）。
 * 若 SPS 本来就 ≥4.0（如 60fps 时自动选 4.2），保持原值不降级。
 *
 * 动机：奥迪 DELPHI 解码器对 level 3.1/3.2 的流静默不渲染（TCP 照收、会话正常、
 * 车机黑屏；2026-10-22 奥迪 0628 真机日志：同机 level=40 全部成功、level=32 首例黑屏）。
 */
internal fun patchSpsLevel(sps: ByteArray): ByteArray {
    if (sps.size < 4) return sps
    val current = sps[3].toInt() and 0xFF
    if (current >= TARGET_LEVEL_IDC) return sps
    val patched = sps.copyOf()
    patched[3] = TARGET_LEVEL_IDC.toByte()
    return patched
}
