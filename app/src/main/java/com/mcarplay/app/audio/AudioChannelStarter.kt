package com.mcarplay.app.audio

import com.mcarplay.app.compat.ReceiverProbePlan
import java.util.concurrent.atomic.AtomicBoolean

/** 音频启动控制流；端口只在原控制点活读，构造不触碰会话或媒体资源。 */
internal class AudioChannelStarter<C : Any, K : Any, P : Any, S : Any, B : Any>(
    private val session: AudioSessionPort<C, K, S, B>,
    private val clients: AudioClientPort<C>,
    private val source: AudioSourcePort<K, P, S, B>,
    private val memory: AudioStartMemory<K>,
    private val detection: AudioDetectionPort<C>,
    private val diagnostics: AudioStartDiagnostics,
    private val active: ActiveAudioSlots,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
) {
    private val audioStartGate = AudioStartGate()

    fun maybeStartAudioNow(context: K) {
        val client =
            session.currentClient ?: run {
                log("MODE: 已启用声音（当前无会话，下次连接时接入）")
                return
            }
        if (clients.isStopping(client)) {
            log("AUDIO: 会话正在结束——跳过动态开启声音")
            return
        }
        val secret = session.secret
        if (secret == null) {
            log("MODE: 明文会话（未配对）无共享密钥——不补启音频")
            return
        }
        if (session.sender?.let(source::isRunning) == true) return
        log("MODE: 会话进行中，动态补启音频通道…")
        startAudioAndRecord(context, client, secret, session.host, "AUDIO: 动态加入音频流——检查会话激活")
    }

    /** 启动音频并在成功后补 RECORD；连接、动态补启和仲裁重试三条入口共用。 */
    fun startAudioAndRecord(
        context: K,
        client: C,
        secret: ByteArray?,
        host: String?,
        logLine: String,
        recordAfterSetup: Boolean = true,
    ): Boolean {
        val started = startAudioChannel(context, client, secret, host)
        if (started) {
            if (recordAfterSetup) {
                log(logLine)
                clients.record(client)
            } else {
                log("AUDIO: 初始音频流已加入——沿用已激活会话的 RECORD")
            }
        }
        return started
    }

    /**
     * claim 因车机约束被拒后重试一次音频通道。
     * 由 `consumeAudioClaimRetry` 原子消费 pending claim 后触发（changeModes 归还
     * 与 requestUI 重进两条入口共用）；仍受音频开关、会话代次和发送器状态约束。
     */
    fun retryAudioAfterClaimReturned(client: C) {
        // 只把状态检查放在短临界区里；SETUP 最长数秒，不能占着会话锁（disconnect 是
        // @Synchronized，UI 线程点“断开”会跟着卡住）。
        val ready =
            session.withControllerLock {
                if (client !== session.currentClient || clients.isStopping(client) || !session.enabled) return@withControllerLock null
                if (session.sender?.let(source::isRunning) == true) return@withControllerLock null
                val context = session.context ?: return@withControllerLock null
                val secret = session.secret ?: return@withControllerLock null
                Triple(context, secret, session.host)
            } ?: return
        startAudioAndRecord(ready.first, client, ready.second, ready.third, "AUDIO: 车机归还主音频后音频流已建立——检查会话激活")
    }

    fun startAudioChannel(
        context: K,
        client: C,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        // SETUP 协商可持续数秒，期间 session.sender 尚未赋值：连接建立、动态补启、requestUI
        // 重进和 changeModes 归还重试都可能在赋值前通过状态检查。没有闸门时会并发建立
        // 多条流，后写入的 session.sender 覆盖先前的，另一条流仍启动却失去引用（双流 + 泄漏）。
        if (!audioStartGate.tryAcquire()) {
            log("AUDIO: 已有音频通道正在建立——跳过本次重复启动")
            return false
        }
        return try {
            startAudioChannelLocked(context, client, sharedSecret, host)
        } finally {
            audioStartGate.release()
        }
    }

    /** [startAudioChannel] 的单飞临界区；进入前已持有 [audioStartGate]。 */
    private fun startAudioChannelLocked(
        context: K,
        client: C,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        if (session.sender?.let(source::isRunning) == true) {
            log("AUDIO: 已有音频发送器在运行——跳过重复启动")
            return false
        }
        val connectionGeneration = clients.generation(client)
        if (!clients.isSessionActive(client) || !clients.isConnectionCurrent(client, connectionGeneration)) return false
        val diagnosticAttempt = diagnostics.currentAttemptId()
        if (!session.enabled) {
            log("AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频")
            return false
        }
        if (sharedSecret == null) {
            // 明文会话（et=NONE）没有共享密钥可派生 DataStream 输出密钥：
            // 推出去的 RTP 车机无法解密。宁可不推，也不要制造「有包无声」的假象。
            log("AUDIO: 明文会话（未配对）无共享密钥——跳过音频通道")
            return false
        }
        if (host == null) {
            log("AUDIO: 无接收端可用地址，跳过音频通道")
            return false
        }
        val projection = source.projection()
        val useNativeCarLifeAudio = source.nativeAvailable(context)
        if (projection == null && !useNativeCarLifeAudio) {
            log(
                "AUDIO: 缺少投屏权限（播放捕获依赖 MediaProjection）——音频通道未启动。" +
                    "现在点「申请投屏权限」也可即时补启（无需重连）；注意「断开连接」会停服务并连带失效投屏授权",
            )
            return false
        }
        var startingSender: S? = null
        var startingBridge: B? = null
        return try {
            // 车机身份 → 只用于「本车机哪些音频档位踩过雷」的持久化记忆。
            val detecting = detection.collecting(client)
            val identity = if (detecting) "" else clients.receiverIdentity(client)
            // 音频封装参数已定案（见 AudioTuning），不再提供切换
            val tuning = AudioTuning.RAW_SPF
            var entry =
                if (identity.isBlank()) {
                    AudioProfileMemory.Entry()
                } else {
                    memory.load(
                        source.applicationContext(context),
                        identity,
                    )
                }
            if (entry.disabled) {
                // 以前「全部档位都导致拆链」会自动退成只推视频，并要用户去弹窗里手点
                // 「清除记忆」。现在音频通路已换成 AAC-LC 且实测能稳定跑满 147s，
                // 旧结论不再可信：用户既然主动选了「开启声音」，就直接清记忆重试。
                memory.reset(source.applicationContext(context), identity)
                entry = AudioProfileMemory.Entry()
                log("AUDIO: 本车机（$identity）有「全部档位拆链」的旧记忆，已自动清除并重试")
            }
            val candidates = clients.candidates(client, preferCompat = session.preferCompat, allowBaseline = session.baselineAllowed())
            val baseline = AudioProfileMemory.order(candidates, entry)
            val preferred = detection.preferredAudio(client)
            val ordered = ReceiverProbePlan.audio(baseline, preferred)
            if (ordered.isEmpty()) {
                log("AUDIO: 没有可用音频档位（候选=${candidates.map { it.label }}）——仅推视频")
                return false
            }
            log(
                "AUDIO: 音频档位候选=${ordered.map { it.label }}" +
                    "（本车机记忆：${AudioProfileMemory.describe(entry)}；" +
                    "封装=${tuning.detail}）",
            )
            // 真 iPhone 在 Main audio setting up 之前先报 mainAudio controller；车机持有
            // 或借用主音频时直接 SETUP 会被 VW4131 以 400 拒绝，不能误判成三档格式都不兼容。
            val audioClaim = clients.claim(client, connectionGeneration)
            if (!audioClaim.claimed) {
                // 车机以高于 userInitiated 的约束持有/借用主音频（如倒车提示音）：不抢、不发 SETUP，
                // 否则三档 400 会被写成格式失败记忆；等 changeModes 归还后再重试。
                log("AUDIO: 主音频资源未接管（${audioClaim.note}）——本次不发送 SETUP，等车机归还主音频后重试")
                return false
            }
            log(
                "AUDIO: 音频流建立前主音频资源" +
                    if (audioClaim.announced) "已报归手机" else "切换未确认（继续尝试 SETUP）",
            )
            // SETUP 失败不会扰动会话（车机只是不接这条流的请求），所以本会话内就把候选
            // 试完，不必等下一次连接。这一点对当前场景很关键：102/AAC-LC 与 100/PCM 是
            // 两条完全不同的通路，前者不被接受时应当立刻退到后者，而不是让用户白连一次。
            var chosen: AudioProfile? = null
            var streamInfo: AudioSetup? = null
            for (candidate in ordered) {
                if (!clients.isConnectionCurrent(client, connectionGeneration)) return false
                if (!clients.phoneOwnsAudio(client)) {
                    log("AUDIO: 主音频资源在协商期间转为车机占用——停止候选轮换，不记为格式失败")
                    return false
                }
                val result = clients.setup(client, sharedSecret, candidate, tuning, expectedEpoch = connectionGeneration)
                if (result != null) {
                    chosen = candidate
                    streamInfo = result
                    break
                }
                if (!clients.phoneOwnsAudio(client)) {
                    log("AUDIO: 主音频 SETUP 被拒时资源已归车机——不记为格式失败")
                    return false
                }
                // SETUP 被拒不等于「车机不兼容」：可能只是资源仲裁/网络抖动，
                // 不能据此把整台车机的音频判死（置 disabled 只认「拆链」证据）。
                // 只有 modesChanged 已确认手机持有主音频时，失败才可作为格式证据。
                if (audioClaim.announced && identity.isNotBlank()) {
                    memory.recordRejected(
                        source.applicationContext(context),
                        identity,
                        candidate.key,
                        ordered.map { it.key }.toSet(),
                        markExhausted = false,
                    )
                }
                log("AUDIO: 档位 ${candidate.label} 协商失败——本会话继续试下一档")
            }
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                log("AUDIO: 全部候选档位协商失败（共 ${ordered.size} 档）——本次仅推视频")
                return false
            }
            val sender =
                source.createSender(
                    AudioSenderSpec(
                        host = host,
                        dataPort = audio.dataPort,
                        streamKey = audio.streamKey,
                        sampleRate = audio.sampleRate,
                        codec = profile.codec,
                        streamType = profile.streamType,
                        adtsFrames = tuning.adts,
                        controlPort = audio.controlPort,
                    ),
                    source.applicationContext(context),
                    { line -> log(line) },
                    { diagnosticAttempt?.let { diagnostics.audioPacket(it) } },
                )
            startingSender = sender
            val bridge =
                if (useNativeCarLifeAudio) {
                    source.createBridge(sender, audio.sampleRate, source.applicationContext(context)) { line -> log(line) }
                } else {
                    null
                }
            startingBridge = bridge
            val published =
                session.withControllerLock {
                    if (!clients.isConnectionCurrent(client, connectionGeneration) || session.currentClient !== client) {
                        false
                    } else {
                        session.sender = sender
                        session.bridge = bridge
                        active.profile = profile
                        active.receiverKey = identity
                        active.startedAtMs = nowMs()
                        true
                    }
                }
            // 保留基线发布前早退：此处不追加局部 stop。
            if (!published) return false
            detection.audioSelected(client, profile.key)
            diagnosticAttempt?.let {
                diagnostics.note(it, "AUDIO_SELECTED ${profile.label} sampleRate=${audio.sampleRate}")
            }
            val started =
                if (bridge != null) {
                    source.startExternal(sender).also { if (it) source.startBridge(bridge) }
                } else {
                    projection != null && source.startCapture(sender, projection)
                }
            if (!started || !clients.isConnectionCurrent(client, connectionGeneration) || session.currentClient !== client) {
                bridge?.let(source::stopBridge)
                source.stopSender(sender)
                session.withControllerLock {
                    if (session.sender === sender) {
                        session.sender = null
                        active.clear()
                    }
                    if (session.bridge === bridge && bridge != null) session.bridge = null
                }
                false
            } else {
                true
            }
        } catch (error: Exception) {
            log("AUDIO: 通道启动异常 - ${error.javaClass.simpleName}: ${error.message}")
            startingBridge?.let(source::stopBridge)
            startingSender?.let(source::stopSender)
            session.withControllerLock {
                if (startingSender != null && session.sender === startingSender) {
                    session.sender = null
                    active.clear()
                }
                if (startingBridge != null && session.bridge === startingBridge) session.bridge = null
            }
            false
        }
    }

    fun audioCandidateKeys(identity: String): Set<String> =
        runCatching {
            val client = session.currentClient ?: return@runCatching emptySet()
            val preferred =
                clients
                    .candidates(
                        client,
                        preferCompat = session.preferCompat,
                        allowBaseline = session.baselineAllowed(),
                    ).map { it.key }
            val alternate =
                clients
                    .candidates(
                        client,
                        preferCompat = !session.preferCompat,
                        allowBaseline = session.baselineAllowed(),
                    ).map { it.key }
            (preferred + alternate).toSet()
        }.getOrDefault(emptySet())
}

internal class AudioStartGate {
    private val inFlight = AtomicBoolean(false)

    /** 获得启动权返回 true；已有启动在进行中返回 false。 */
    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    /** 释放启动权；必须放在 finally 里。 */
    fun release() = inFlight.set(false)
}

internal interface AudioSessionPort<C : Any, K : Any, S : Any, B : Any> {
    val currentClient: C?
    val context: K?
    val secret: ByteArray?
    val host: String?
    val enabled: Boolean
    val preferCompat: Boolean
    var sender: S?
    var bridge: B?

    fun baselineAllowed(): Boolean

    fun <T> withControllerLock(block: () -> T): T
}

internal interface AudioClientPort<C : Any> {
    fun generation(client: C): Long

    fun isConnectionCurrent(
        client: C,
        generation: Long,
    ): Boolean

    fun isSessionActive(client: C): Boolean = true

    fun isStopping(client: C): Boolean

    fun receiverIdentity(client: C): String

    fun candidates(
        client: C,
        preferCompat: Boolean,
        allowBaseline: Boolean,
    ): List<AudioProfile>

    fun claim(
        client: C,
        generation: Long,
    ): AudioClaim

    fun phoneOwnsAudio(client: C): Boolean

    fun setup(
        client: C,
        secret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning,
        expectedEpoch: Long,
    ): AudioSetup?

    fun record(client: C): Boolean
}

internal data class AudioClaim(
    val note: String,
    val claimed: Boolean,
    val announced: Boolean,
)

internal data class AudioSetup(
    val streamConnectionId: Long,
    val dataPort: Int,
    val controlPort: Int,
    val streamKey: ByteArray,
    val sampleRate: Int,
)

internal data class AudioSenderSpec(
    val host: String,
    val dataPort: Int,
    val streamKey: ByteArray,
    val sampleRate: Int,
    val codec: AudioCodec,
    val streamType: Long,
    val adtsFrames: Boolean,
    val controlPort: Int,
)

internal interface AudioSourcePort<K : Any, P : Any, S : Any, B : Any> {
    fun projection(): P?

    fun nativeAvailable(context: K): Boolean

    fun applicationContext(context: K): K

    fun isRunning(sender: S): Boolean

    fun createSender(
        spec: AudioSenderSpec,
        context: K,
        log: (String) -> Unit,
        onPacketSent: () -> Unit,
    ): S

    fun createBridge(
        sender: S,
        sampleRate: Int,
        context: K,
        log: (String) -> Unit,
    ): B

    fun startCapture(
        sender: S,
        projection: P,
    ): Boolean

    fun startExternal(sender: S): Boolean

    fun startBridge(bridge: B)

    fun stopSender(sender: S)

    fun stopBridge(bridge: B)
}

internal interface AudioStartMemory<K : Any> {
    fun load(
        context: K,
        identity: String,
    ): AudioProfileMemory.Entry

    fun reset(
        context: K,
        identity: String,
    )

    fun recordRejected(
        context: K,
        identity: String,
        key: String,
        allCandidates: Set<String>,
        markExhausted: Boolean,
    )
}

internal interface AudioDetectionPort<C : Any> {
    fun collecting(client: C): Boolean

    fun preferredAudio(client: C): String?

    fun audioSelected(
        client: C,
        key: String,
    )
}

internal interface AudioStartDiagnostics {
    fun currentAttemptId(): String?

    fun note(
        attempt: String,
        line: String,
    )

    fun audioPacket(attempt: String)
}

/** 三槽的逐次访问契约；没有跨槽锁或原子快照。 */
internal interface ActiveAudioSlots {
    var profile: AudioProfile?
    var receiverKey: String
    var startedAtMs: Long

    fun snapshot(): AudioTeardownState = AudioTeardownState(profile, startedAtMs, receiverKey)

    fun clear() {
        profile = null
        startedAtMs = 0L
    }
}
