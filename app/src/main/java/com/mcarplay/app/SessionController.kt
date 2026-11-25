package com.mcarplay.app

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.airplay.DisplaySurfaceReport
import com.mcarplay.app.airplay.PairVerifyClient
import com.mcarplay.app.audio.AudioChannelStarter
import com.mcarplay.app.audio.AudioChannelState
import com.mcarplay.app.audio.AudioClaim
import com.mcarplay.app.audio.AudioClientPort
import com.mcarplay.app.audio.AudioDetectionPort
import com.mcarplay.app.audio.AudioDuckingPrefs
import com.mcarplay.app.audio.AudioProfile
import com.mcarplay.app.audio.AudioProfileMemory
import com.mcarplay.app.audio.AudioSender
import com.mcarplay.app.audio.AudioSenderSpec
import com.mcarplay.app.audio.AudioSessionPort
import com.mcarplay.app.audio.AudioSetup
import com.mcarplay.app.audio.AudioSourcePort
import com.mcarplay.app.audio.AudioStartDiagnostics
import com.mcarplay.app.audio.AudioStartMemory
import com.mcarplay.app.audio.AudioTeardown
import com.mcarplay.app.audio.AudioTeardownOutput
import com.mcarplay.app.audio.AudioTeardownState
import com.mcarplay.app.audio.AudioTuning
import com.mcarplay.app.audio.CarLifeAudioBridge
import com.mcarplay.app.audio.Mib3LinkWatchdog
import com.mcarplay.app.bt.AutoConnectPrefs
import com.mcarplay.app.bt.AutoReconnectGuard
import com.mcarplay.app.bt.BluetoothDiagnostics
import com.mcarplay.app.bt.BtProfiles
import com.mcarplay.app.bt.BtSessionGate
import com.mcarplay.app.bt.Iap2BluetoothClient
import com.mcarplay.app.bt.Iap2BluetoothServer
import com.mcarplay.app.bt.TargetDevicePrefs
import com.mcarplay.app.bt.countsAsAutoReconnectFailure
import com.mcarplay.app.bt.peerUnknownOrMatches
import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeJoinGate
import com.mcarplay.app.carlife.CarLifeLaunchStrategy
import com.mcarplay.app.carlife.CarLifeLauncher
import com.mcarplay.app.carlife.CarLifeProtocol
import com.mcarplay.app.carlife.CarLifeSession
import com.mcarplay.app.carlife.CarLifeSessionRegistry
import com.mcarplay.app.compat.CompatibilityPrefs
import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.compat.ReceiverDetectionRuntime
import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.compat.ScreenReturnPolicy
import com.mcarplay.app.compat.ScreenReturnStrategy
import com.mcarplay.app.compat.VehiclePresetRuntime
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import com.mcarplay.app.diagnostics.ConnectionMilestone
import com.mcarplay.app.diagnostics.ConnectionStage
import com.mcarplay.app.diagnostics.ConnectionStagePolicy
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.diagnostics.DiagnosticRedactor
import com.mcarplay.app.diagnostics.StreamEnvironment
import com.mcarplay.app.i18n.AppLanguage
import com.mcarplay.app.iap2.Iap2MessageIds
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.iap2.WifiCredentialCache
import com.mcarplay.app.iap2.WifiCredentialSource
import com.mcarplay.app.iap2.rememberEndpoint
import com.mcarplay.app.input.HidNavigation
import com.mcarplay.app.media.AutoPlaySuppressor
import com.mcarplay.app.media.MediaSessionsState
import com.mcarplay.app.net.AirPlayFeatures
import com.mcarplay.app.net.CarPlayCtrlAdvertiser
import com.mcarplay.app.net.CarPlayServiceBrowser
import com.mcarplay.app.net.CarWifiHandoffRequest
import com.mcarplay.app.net.CarWifiHandoffSource
import com.mcarplay.app.net.CarWifiInternetPreference
import com.mcarplay.app.net.CarWifiJoiner
import com.mcarplay.app.net.CarWifiLink
import com.mcarplay.app.net.CarWifiPendingEndpointPort
import com.mcarplay.app.net.CarWifiPhoneModeDecision
import com.mcarplay.app.net.CarWifiShellPort
import com.mcarplay.app.net.CarWifiShellResult
import com.mcarplay.app.net.CarWifiState
import com.mcarplay.app.net.CarWifiUiPort
import com.mcarplay.app.net.CtrlInviteGate
import com.mcarplay.app.net.DiscoveredEndpoint
import com.mcarplay.app.net.EndpointHosts
import com.mcarplay.app.net.EndpointResolver
import com.mcarplay.app.net.InviteView
import com.mcarplay.app.net.StreamingInviteReconnect
import com.mcarplay.app.net.WifiCarPlayConnector
import com.mcarplay.app.service.CarPlayService
import com.mcarplay.app.service.ScreenLockMonitor
import com.mcarplay.app.service.StreamingWifiLock
import com.mcarplay.app.session.EventCommandRouter
import com.mcarplay.app.session.LegacyAttemptScope
import com.mcarplay.app.session.LegacySessionUiPort
import com.mcarplay.app.session.SessionUiPort
import com.mcarplay.app.session.attemptHandoffCancelled
import com.mcarplay.app.settings.DisplayPrefs
import com.mcarplay.app.settings.InternetNetwork
import com.mcarplay.app.settings.InternetNetworkPolicy
import com.mcarplay.app.settings.InternetNetworkPrefs
import com.mcarplay.app.settings.MediaPrefs
import com.mcarplay.app.settings.PhoneModeSystemWifiDecision
import com.mcarplay.app.shizuku.CarInputClient
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.shizuku.ShizukuGate
import com.mcarplay.app.ui.ScreenDimmer
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DebugFileLog
import com.mcarplay.app.video.ExternalVideoSource
import com.mcarplay.app.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程级会话中枢：**日志缓冲**与**会话管线所有权**的唯一持有者。
 *
 * 背景（2026-10-11 用户实测发现三个连带问题）：投屏管线运行在 MainActivity 派生线程里，
 * 划掉界面（Activity 销毁）后进程被前台服务保活、管线继续跑；但重进应用创建的是**新
 * Activity 实例**——
 *  1. 老线程的日志写进老实例（已销毁）的 UI → 新界面"日志断联"（文件日志实际未断）
 *  2. 「断开连接」操作新实例的新对象 → 老管线无人持有，断不掉
 *  3. 重开连接会建第二套对象 → 双会话风险
 *
 * 本对象把这些状态提升为进程级单例：Activity 只是"挂屏"（订阅日志、转发按钮），
 * 销毁/重建对其无影响。会话逻辑线程通过 [appContext] 运行，不依赖任何 Activity。
 */
object SessionController {
    // ---- 日志缓冲（进程级；Activity 订阅渲染）----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当前挂屏 Activity 的渲染回调（收到**本条新增日志行**，含时间戳前缀）；Activity onDestroy 时清空。 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** Controls UI buffering/rendering only; bounded private file diagnostics remain enabled. */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** 加入确认弹窗的应答等待上限（无人应答按中止处理）。 */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /**
     * 本轮接收端的蓝牙地址：自动重连熔断按它对接收端隔离。
     * 来自端点（0x5703/0x4301 所在控制链路）或 ACL 广播；未知时为 null，此时不计数也不拦。
     */
    @Volatile
    private var activePeerAddress: String? = null

    /**
     * [address] 是否属于本轮车机；任一方地址未知时按兼容处理（true）。
     * 自动收摊、迟到链路断开用它避免把另一台车机的动作算到当前会话上。
     */
    fun matchesActivePeer(address: String?): Boolean = peerUnknownOrMatches(activePeerAddress, address)

    /**
     * 空端点（车机声明会发 0x4301 但窗口内未到）的等待上限。
     * 奥迪 MMI 9310 实测 0x4301 晚到 2 分 35 秒——保活窗口必须显著长于它；
     * 超时拆链让 BT 重连循环接管（与旧「8s 拆链」的区别只在窗口长度）。
     */
    private const val PENDING_START_SESSION_WAIT_MS = 5 * 60_000L

    /** 车机经 ctrl 邀请证明「同网」但 SSID 不可读时的绑定键/日志标签（不是真实 SSID）。 */
    internal const val INVITED_WIFI_LABEL = "(车机所在 WLAN)"

    /** SETUP 被车机关通道后，按邀请自动重连的次数上限与每次等待上限。 */
    private const val MAX_INVITE_RETRIES = 2

    /** 推流期间每隔这么久记一次 Wi-Fi/温控/电量（首帧时先记一次）。 */
    private const val ENVIRONMENT_SAMPLE_MS = 60_000L
    private const val INVITE_RETRY_WAIT_MS = 60_000L

    /**
     * 等车机 ctrl 邀请的上限（事件驱动，收到即走）。
     * catplay 以邀请为连接触发点；实测邀请在 ctrl 重注册后 ~1.0-1.3s 到达。
     * 车机不发邀请时（旧机型）由「已发现 _airplay._tcp」提前放行，最坏也只等这么久。
     * 必须盖住 [CtrlInviteGate] 的整条路径：广播可见 3 s → 重公告 → 重新可见（约 1 s）→ 再宽限 3 s。
     * 原来的 4 s 在广播可见 2.6 s 时就超时（帕萨特 2026-11-01 23:02:38），邀请在开始等待 6 s 后才到。
     */
    private const val CTRL_INVITE_WAIT_MS = 9_000L

    /** ctrl 邀请用于“当前网络归属”判定的新鲜度；旧邀请不得复活上一轮。 */
    private const val CTRL_INVITE_FRESH_MS = 30_000L

    /** 让出与拿回两条 modesChanged 的间隔：给车机 HMI 处理一次归属切换的时间。 */
    private const val SCREEN_RETAKE_GAP_MS = 200L

    /** 拿回主屏后多久检查车机是否有触控/要帧。 */
    private const val TAKE_BACK_CHECK_MS = 3_000L

    /** 端点不完整时等 mDNS 缓存命中：绑网时已开始发现，通常几百毫秒内命中。 */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /**
     * 明文 SETUP 被拒后「加密升级」补配对的上限（catplay handle_setup_result 路径）。
     * 只在实际被拒时才走到这里，正常车机零成本；不支持配对的接收端会在
     * pair-setup 收到 404/405/455 后立刻放弃，不会等满这个窗口。
     */
    private const val PAIRING_UPGRADE_WINDOW_MS = 30_000L

    /**
     * 明文会话初始 SETUP 的重试窗口。pre-HomeKit 车机的 AirPlay 端口常在
     * 「用户点车机上的手机图标」后才开放；端口晚开 1~2 秒不该让整轮失败。
     */
    private const val INITIAL_SETUP_WINDOW_MS = 30_000L

    /**
     * `requestNetwork(WIFI)` 等 onAvailable 的上限（秒）。
     * 用户正在系统设置里手动加入热点时，回调要等关联+DHCP 完成才来，实测可达 10s+。
     * 旧值 5s 会在成功前 116ms 误判失败并放弃整轮会话（见 bindCarWifiAndRegister）。
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * `cmd wifi connect-network` 之后等系统真正关联上热点的时间窗。
     * 命令本身只把「连接」投给 WifiService 就返回（exit=0 不代表已连上），
     * 断开旧网 → 扫描 → 关联 → DHCP 实测 2~10s，5GHz 热点偏慢时更久。
     */
    private const val CAR_WIFI_JOIN_WAIT_MS = 25_000L

    /** `cmd wifi set-wifi-enabled enabled` 之后等 WLAN 真正开启的时间窗。 */
    private const val WIFI_ENABLE_WAIT_MS = 10_000L

    /**
     * 等用户确认「连接车机热点」系统弹窗的上限（应用专属网络）。
     * 系统确认框可能停留很久；用户取消连接时 [connectCancelled] 会即时退出。
     */
    // 40s 总窗 = 20s 首段 + 20s 重发段（连接器内部分段）。车机 15s 拆 BT 看门狗
    // 决定了等待必须远短于旧值 120s——旧值下首轮卡满 120s 时车机早已拆链重邀
    // 并被僵尸轮次堵门（2026-10-22 奥迪 3315 真机定谳）。
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 40_000L

    /** Activity onCreate 时调用：登记 applicationContext 并创建会话对象（幂等）。 */
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        ReceiverDetectionRuntime.initialize(app)
        VehiclePresetRuntime.load(app)
        ReceiverDetectionRuntime.onBudgetExpired = { epoch ->
            Bg.run("detection-budget") {
                synchronized(this) {
                    // disconnect 第一步即 blockNewHandoffs()：所有权已无条件释放，无需再 release。
                    if (ReceiverDetectionRuntime.isEpoch(epoch)) disconnect(app, DiagnosticCode.DETECTION_TIMEOUT)
                }
            }
        }
        ensureSessionObjects(context.applicationContext)
        // 主线程调用 shell 的告警（历史上两次真机 ANR 都源于此；HKS 下 logcat 不可读）
        Shell.violationSink = ::appendLog
        // HID 原始日志开关（键位校准）：静态标志从偏好初始化，进程冷启动也要生效
        HidNavigation.rawLogEnabled = DisplayPrefs.hidRawLog(appContext!!)
    }

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染（增量行直送）。任意线程可调。 */
    fun appendLog(message: String) {
        // NUL 会写进日志文件：文本编辑器/文件管理器打不开（Read 工具也会判为二进制），
        // 用户反馈日志时等于白导。0x4301 的定长地址字段曾原样打印出多个 NUL
        // （2026-10-18 实测 216 个），这里替换成可见占位符——顺带让「字符串里有脏字节」
        // 在日志上直接可见，而不是隐形成 InetSocketAddress 的解析失败。
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * 派发系统媒体键（AudioManager 全局路由给**当前活跃媒体会话**，无需权限）。
     * 桌面音乐卡的按钮与车机/方向盘媒体键（Consumer HID）共用这一路径。
     */
    fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** 上车自动播放抑制状态；只在设置关闭时启用，见 [armAutoPlaySuppression]。 */
    private val autoPlaySuppressor = AutoPlaySuppressor()
    private var autoPlayListener: (() -> Unit)? = null
    private val autoPlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoPlayTimeout = Runnable { stopAutoPlayWatch() }

    /**
     * 「上车自动播放音乐」关闭时，在 CarWith 连接后的一小段窗口内抑制自动播放：
     *  - 前 [AutoPlaySuppressor.KEY_WINDOW_MILLIS] 内丢弃车机的播放键；
     *  - [AutoPlaySuppressor.WATCH_WINDOW_MILLIS] 内音乐自行开始时暂停一次。
     * 连接前已经在播放的音乐不处理；用户随后在车机上按播放会先解除抑制。
     */
    private fun armAutoPlaySuppression() {
        stopAutoPlayWatch()
        val context = appContext ?: return
        if (MediaPrefs.autoPlayOnConnect(context)) return
        if (MediaSessionsState.nowPlaying?.playing == true) {
            appendLog("MEDIA: 上车自动播放已关闭——连接前已在播放，保持现状")
            return
        }
        autoPlaySuppressor.arm(alreadyPlaying = false, nowMs = SystemClock.uptimeMillis())
        val listener = {
            if (MediaSessionsState.nowPlaying?.playing == true &&
                autoPlaySuppressor.shouldPauseStartedPlayback(SystemClock.uptimeMillis())
            ) {
                appendLog("MEDIA: 上车自动播放已关闭——暂停连接后自行开始的音乐")
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        }
        autoPlayListener = listener
        MediaSessionsState.addListener(listener)
        autoPlayHandler.postDelayed(autoPlayTimeout, AutoPlaySuppressor.WATCH_WINDOW_MILLIS + 1_000L)
        appendLog(
            "MEDIA: 上车自动播放已关闭——${AutoPlaySuppressor.KEY_WINDOW_MILLIS / 1000}s 内忽略车机自动播放键，" +
                if (MediaSessionsState.accessGranted) {
                    "${AutoPlaySuppressor.WATCH_WINDOW_MILLIS / 1000}s 内暂停自行开始的音乐"
                } else {
                    "通知使用权未授予，无法识别自行开始的音乐"
                },
        )
    }

    /**
     * 开关打开时的主动起播。CarWith 的音乐走原生 PCM，不产生系统媒体会话/通知，普通媒体键
     * 无法恢复；这里在 CarWith **视频就绪后**用「媒体开始」硬按键起播一次。
     * 无法确认媒体卡是否可见，因此不自动注入触摸。若已经收到 MEDIA PCM（正在播放）则不动。
     * 开关关闭时仍走 [armAutoPlaySuppression]。
     */
    private fun armAutoPlayBehavior(session: CarLifeSession) {
        val context = appContext ?: return
        if (!MediaPrefs.autoPlayOnConnect(context)) {
            armAutoPlaySuppression()
            return
        }
        if (!audioEnabledForSession) {
            appendLog("AUTO-PLAY: 本次连接未开启车机侧声音，不主动起播")
            return
        }
        Bg.run("auto-play-start") {
            try {
                val readyDeadline = SystemClock.uptimeMillis() + AUTO_PLAY_READY_TIMEOUT_MS
                Thread.sleep(2_000L)
                if (currentCarLifeSession !== session) return@run
                // CarWith 的硬按键/触摸在 VIDEO_RUNNING 之前会被静默拒绝；冷启动可能远慢于 2s。
                if (!awaitVideoRunning(session, readyDeadline)) {
                    appendLog("AUTO-PLAY: CarWith 视频未就绪（等待超时），跳过自动起播")
                    return@run
                }
                var bridge = nativeAudioBridge
                while (bridge == null && SystemClock.uptimeMillis() < readyDeadline) {
                    Thread.sleep(500L)
                    if (currentCarLifeSession !== session) return@run
                    bridge = nativeAudioBridge
                }
                if (bridge == null) {
                    // 此入口只用于 CarWith；桥未建立可能是音频协商失败，不能误播手机其他媒体。
                    appendLog("AUTO-PLAY: CarWith 音频桥未就绪，跳过自动起播")
                    return@run
                }
                if (bridge.lastMediaPcmAtMs > 0L) {
                    appendLog("AUTO-PLAY: CarWith 媒体已在播放，跳过")
                    return@run
                }
                // 1) 先发 CarWith「媒体开始」硬按键；只有 CarWith 媒体处于可恢复状态时才生效。
                if (session.sendHardKey(CarLifeProtocol.HARD_KEY_MEDIA_START)) {
                    appendLog("AUTO-PLAY: 已发送 CarWith 媒体开始键，等待媒体 PCM")
                    if (awaitMediaPcm(session, bridge, 6_000L)) {
                        appendLog("AUTO-PLAY: CarWith 媒体已起播（媒体开始键）")
                        return@run
                    }
                }
                appendLog("AUTO-PLAY: 未能自动起播（可在车机上手动点播放键）")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** 等待 CarWith 媒体 PCM 出现；true = 已开始播放。 */
    private fun awaitMediaPcm(
        session: CarLifeSession,
        bridge: CarLifeAudioBridge,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (currentCarLifeSession !== session) return false
            if (bridge.lastMediaPcmAtMs > 0L) return true
            Thread.sleep(200L)
        }
        return bridge.lastMediaPcmAtMs > 0L && currentCarLifeSession === session
    }

    /** CarWith 冷启动可能慢于 2s；等视频阶段最多 25s（与连接等待上限一致）。 */
    private const val AUTO_PLAY_READY_TIMEOUT_MS = 25_000L

    /** 等待 CarWith 进入视频阶段；硬按键/触摸在此之前会被静默拒绝。 */
    private fun awaitVideoRunning(
        session: CarLifeSession,
        deadlineMs: Long,
    ): Boolean {
        var logged = false
        while (SystemClock.uptimeMillis() < deadlineMs) {
            if (currentCarLifeSession !== session) return false
            if (session.isVideoRunning()) return true
            if (!logged) {
                appendLog("AUTO-PLAY: 等待 CarWith 视频就绪（最多 25s）")
                logged = true
            }
            Thread.sleep(200L)
        }
        return session.isVideoRunning() && currentCarLifeSession === session
    }

    private fun stopAutoPlayWatch() {
        autoPlayListener?.let(MediaSessionsState::removeListener)
        autoPlayListener = null
        autoPlaySuppressor.disarm()
        autoPlayHandler.removeCallbacks(autoPlayTimeout)
    }

    /**
     * 车机/方向盘媒体键的统一出口（AirPlay `hidSendReport` 与 iAP2 0x6802 共用）。
     *
     * 上一首/下一首在 CarWith 出画时作为 CarLife 硬按键交给 CarWith，由它决定控制哪个
     * 播放器（与原厂 CarLife 车机一致）；CarWith 未在出画或其余按键（播放/暂停/停止/快进）
     * 走系统媒体键，AudioManager 派发给当前活跃的媒体会话——锁屏时同样生效。
     *
     * @param source 日志用来源（通路 + 按键名）
     */
    private fun routeCarMediaKey(
        keyCode: Int,
        source: String,
    ) {
        val isPlayKey = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        if (isPlayKey && autoPlaySuppressor.shouldDropPlayKey(SystemClock.uptimeMillis())) {
            appendLog("EVENT: 车机媒体键（$source）→ 已忽略（设置「上车自动播放音乐」已关闭）")
            return
        }
        // 用户/车机主动按播放：解除观察窗口，别把它当成“自行开始”再暂停。
        if (isPlayKey) autoPlaySuppressor.disarm()
        val hardKey = CarLifeProtocol.hardKeyFor(keyCode)
        if (hardKey != null && currentCarLifeSession?.sendHardKey(hardKey) == true) {
            appendLog("EVENT: 车机媒体键（$source）→ CarWith 硬按键 $hardKey")
            return
        }
        dispatchMediaKey(keyCode)
        appendLog("EVENT: 车机媒体键（$source）→ 系统媒体键 keyCode=$keyCode")
    }

    /**
     * 旋钮/四向拨杆 → 方向键。CarWith 流没有可注入方向键的应用屏（原先的虚拟屏桌面已移除），
     * 这里只记日志，便于看到车机确实发来了导航类 HID。
     */
    private fun injectNavigationKey(keyCode: Int) {
        appendLog("HID: 导航键 ${HidNavigation.nameOf(keyCode)} 无处注入（CarWith 流不承载方向键）——忽略")
    }

    /**
     * 清空进程级日志缓冲（UI 刷新是调用方的事：显式 `renderConsole(logText())`）。
     * 历史上这里靠 `logListener?.invoke("")` 通知 UI——那是「每行全量 setText」时代的
     * 语义；改为增量 appendConsole 后空串等于追加空行，UI 原地不动（「清除按钮点了
     * 没反应」根因，2026-10-16）。不要恢复该调用：空串与真实日志行无法区分。
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    private var wifiConnector: WifiCarPlayConnector? = null

    internal val carWifiState = CarWifiState()

    /**
     * 当前已绑定到哪个车机热点（bindCarWifiAndRegister 成功后写入）。
     * 端点交接时若已绑定到同一 SSID，跳过二次 bindCurrentWifi——它内部先
     * release() 解绑，会打断刚注册好的 ctrl mDNS 广播并白等系统重连。
     */
    private var boundCarSsid: String?
        get() = carWifiState.boundCarSsid
        set(value) {
            carWifiState.boundCarSsid = value
        }

    /** 本次进程内已就「是否清除系统保存热点」询问过的 SSID（避免重试时重复弹窗）。 */
    private val forgetPromptHandled: MutableSet<String>
        get() = carWifiState.forgetPromptHandled

    private var phoneModeBlockedSsid: String?
        get() = carWifiState.phoneModeBlockedSsid
        set(value) {
            carWifiState.phoneModeBlockedSsid = value
        }

    /** WiFi 绑定互斥：auto-join 与端点交接两条路径可能同时绑定同一热点。 */
    private val wifiHandoffGuard = HandoffWifiGuard()
    private val wifiBindLock = wifiHandoffGuard.lock

    /** 仅进程内保存的车机凭据；键为目标实例 + SSID，避免同名热点串用密码。 */
    private val wifiCredentialCache = WifiCredentialCache()

    /** 当前连接是否已识别为艾瑞泽 8 指纹；只影响该车型专属提示。 */
    @Volatile
    private var currentReceiverMatchesArrizo8 = false

    private val bluetoothDiagnostics = BluetoothDiagnostics(ConnectionDiagnostics::runtimeLog)

    fun bluetoothDiagnosticSummary(): String = bluetoothDiagnostics.summary()

    /** DCL 双检锁的可发布字段（无 volatile 的双检锁是坏发布——虽然当前仅主线程调用，别留隐患）。 */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * 常驻 `_airplay._tcp` 发现器（对照 catplay 的常驻 Bonjour cache）。
     * 加入热点即开始后台发现，交接端点时地址多半已就绪，不再现查现等。
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- 「显示」运行时生效值（会话建立时快照；英雄卡展示运行时值而非偏好值）----

    /** 本会话流分辨率缩放百分比（按车机分辨率的百分比生成；100=原尺寸）。 */
    @Volatile
    var activeScalePercent: Int = 100

    /** 本会话目标帧率（合成节奏上限；「显示」设置运行中切换可实时生效）。 */
    @Volatile
    var activeTargetFps: Int = 30

    @Volatile
    var audioSender: AudioSender? = null

    private val audioChannelState = AudioChannelState()

    // 启动器与拆链归因共享同一个状态容器；真实叶子保持在编排层装配。
    private val audioChannelStarter = createAudioChannelStarter()

    private val audioTeardown =
        AudioTeardown(
            probeMillis = AUDIO_PROFILE_PROBE_MS,
            state = {
                AudioTeardownState(
                    profile = audioChannelState.profile,
                    startedAtMs = audioChannelState.startedAtMs,
                    receiverKey = audioChannelState.receiverKey,
                )
            },
            clearState = { clearActiveAudioState() },
            watchdog = {
                currentClient?.let { client ->
                    Mib3LinkWatchdog(
                        armed = client.mib3LinkArmed,
                        controlSessionDone = client.mib3ControlSessionDone,
                    )
                }
            },
            nowMs = { SystemClock.uptimeMillis() },
            context = { appContext?.applicationContext },
            allCandidateKeys = ::audioCandidateKeys,
            output =
                object : AudioTeardownOutput {
                    override fun linkTeardown(livedMillis: Long) {
                        appendLog(audioLinkTeardownNote(livedMillis))
                    }

                    override fun working(
                        profile: AudioProfile,
                        livedMillis: Long,
                    ) {
                        appendLog(
                            "AUDIO: 档位 ${profile.label} 存活 ${livedMillis / 1000}s——记为本车机可用档位",
                        )
                    }

                    override fun rejected(
                        profile: AudioProfile,
                        livedMillis: Long,
                        allDisabled: Boolean,
                    ) {
                        appendLog(
                            "AUDIO: ⚠ 档位 ${profile.label} 起流 ${audioLivedLabel(livedMillis)} 后整条会话被车机拆掉——" +
                                "判定为本车机不兼容档位（已记入名单）。" +
                                if (allDisabled) {
                                    "全部档位均不兼容：下次连接将自动只推视频（车机侧无声音）。"
                                } else {
                                    "下次点「启动连接」会自动换用下一档；也可直接选「开启声音（兼容档）」。"
                                },
                        )
                    }

                    override fun hint(allDisabled: Boolean) {
                        ui.hint {
                            if (allDisabled) {
                                t(R.string.sc_audio_all_incompatible)
                            } else {
                                t(R.string.sc_audio_profile_rejected)
                            }
                        }
                    }
                },
        )

    @Volatile
    var currentClient: AirPlayClient? = null

    private val carLifeSessions = CarLifeSessionRegistry()

    /** Formal CarPlay session currently bridging Xiaomi CarWith. */
    @Volatile
    private var formalCarLifeSession: CarLifeSession? = null

    /** Standalone CarWith self-test session (never submitted to CarPlay). */
    @Volatile
    private var diagnosticCarLifeSession: CarLifeSession? = null

    private val currentCarLifeSession: CarLifeSession?
        get() = formalCarLifeSession

    /** 正式 CarLife 源本轮的输出去向；null = 发送端尚未启动，帧直接丢弃。 */
    private class CarLifeOutput(
        val attempt: String,
        val sender: VideoSender,
    ) {
        val gate = CarLifeJoinGate()
    }

    @Volatile
    private var carLifeOutput: CarLifeOutput? = null

    @Volatile
    private var screenStreamRecovery: (() -> ScreenRecoveryResult)? = null

    private enum class ScreenRecoveryResult { RESTORED, ABORTED, FAILED }

    @Volatile
    private var disconnectCompletion: java.util.concurrent.CountDownLatch? = null

    private val disconnectTail = DisconnectTail(NamedTasks { name, task -> Bg.run(name, task) })

    private val reconnectAttribution =
        ReconnectAttribution(
            context = { appContext },
            snapshot = {
                ConnectionDiagnostics.timeline.snapshot()?.let { snapshot ->
                    ReconnectAttemptSnapshot(
                        id = snapshot.id,
                        videoFrames = snapshot.videoFrames,
                        elapsedMs = snapshot.elapsedMs,
                    )
                }
            },
            appendLog = ::appendLog,
        )

    private val teardownCoordination =
        TeardownCoordination(
            ownerMonitor = SessionController,
            state =
                object : CompletionState {
                    override var completion: java.util.concurrent.CountDownLatch?
                        get() = disconnectCompletion
                        set(value) {
                            disconnectCompletion = value
                        }

                    override val userStopGeneration: Long
                        get() = this@SessionController.userStopGeneration

                    override fun setSessionActive(active: Boolean) {
                        BtSessionGate.sessionActive = active
                    }

                    override fun allowNewHandoffs() {
                        handoffOwnership.allowNewHandoffs()
                    }
                },
            tasks = NamedTasks { name, task -> Bg.run(name, task) },
        )

    private val disconnectLive: DisconnectLive =
        object : DisconnectLive {
            override fun log(message: String) = appendLog(message)

            override fun detachBt(reason: String) = detachActiveBtSessions(eaStopReason = reason)

            override fun stopBtServer() {
                bluetoothServer?.stop()
            }

            override fun stopBtClient() {
                bluetoothClient?.stop()
            }

            override fun finishBtDiagnostics() = bluetoothDiagnostics.finish()

            override fun withWifiLock(body: () -> Unit) = wifiHandoffGuard.cleanup(body)

            override fun stopCtrlAdvertiser() {
                ctrlAdvertiser?.stop()
            }

            override fun stopEndpointBrowser() {
                endpointBrowser?.stop()
            }

            override fun cleanupShellFallback() = cleanupShellFallbackNetwork()

            override fun completeOuter(completed: java.util.concurrent.CountDownLatch) {
                teardownCoordination.complete(completed) {
                    appendLog("SESSION: 旧会话 TEARDOWN 与蓝牙/WiFi 清理完成")
                }
            }
        }

    private var userStopGeneration = 0L

    /** 最近一次 ctrl 邀请的原子快照；换目标/换网后旧邀请不能继续免除确认。 */
    @Volatile
    private var ctrlInviteEvidence: CtrlInviteEvidence<Network>? = null

    private val activeReceiverObservations = ActiveReceiverObservations()

    internal fun isDisconnecting(): Boolean = disconnectCompletion != null

    internal fun isFormalCarLifeActive(): Boolean = currentCarLifeSession?.isRunning == true

    val runningDiagnosticCarLifeSession: CarLifeSession?
        get() = diagnosticCarLifeSession?.takeIf { it.isRunning }

    internal fun claimCarLifeSession(
        owner: CarLifeSessionRegistry.Owner,
        session: CarLifeSession,
    ): Boolean =
        synchronized(carLifeSessions) {
            if (!carLifeSessions.acquire(owner, session.lease)) return@synchronized false
            when (owner) {
                CarLifeSessionRegistry.Owner.FORMAL -> formalCarLifeSession = session
                CarLifeSessionRegistry.Owner.DIAGNOSTIC -> diagnosticCarLifeSession = session
            }
            true
        }

    internal fun releaseCarLifeSession(session: CarLifeSession): Boolean =
        synchronized(carLifeSessions) {
            if (!carLifeSessions.release(session.lease)) return@synchronized false
            if (formalCarLifeSession === session) formalCarLifeSession = null
            if (diagnosticCarLifeSession === session) diagnosticCarLifeSession = null
            true
        }

    /** 当前会话的共享密钥与接收端地址：投影授权晚于连接时，动态补启音频用。 */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** 本次会话是否在车机侧播放声音（由「启动连接」弹窗选择）。 */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /**
     * 「开启声音（兼容档）」：候选里把非 media 档（compatibility 等）排前面。
     * 2026-10-19 Honda HFT 实测——media 档只声明 44.1k，该车机音频通路只吃得下 48k，
     * 走默认档会「上线几秒后整车机拆链」。
     */
    @Volatile
    var audioPreferCompat: Boolean = false

    /** Active bridge from CarWith's native media/TTS channels into CarPlay. */
    @Volatile
    private var nativeAudioBridge: CarLifeAudioBridge? = null

    /**
     * 音频「存活窗口」（ms）：超过它才认为该档位可用。
     * 依据 2026-10-19 实测：拆链发生在音频起流后 6.5s；取 20s 留足车机音频通路初始化
     * 与首个欠载周期的余量。
     */
    private const val AUDIO_PROFILE_PROBE_MS = 20_000L

    /** 会话建立互斥（端点交接 → 配对 → 推流全程只允许一条）。 */
    val sessionStarting = AtomicBoolean(false)

    /** 只允许交付了当前端点的 RFCOMM 链路取消这次交接。 */
    private val handoffOwnership = SessionHandoffOwnership(sessionStarting)

    /** 连接阶段收到 ctrl 邀请时，至多启动一次“绑定当前网络”的交接。 */
    private val inviteHandoffInProgress = AtomicBoolean(false)

    /**
     * 交接流程已在跑、但 AirPlayClient 尚未创建时收到的晚到端点（0x4301）。
     * 配对线程起步时只取用**同一 RFCOMM observation** 的端点——A 的交接期间先到的
     * B 端点必须单独暂存，不能混进 A 的客户端，也不能在 A 释放后无人消费。
     */
    private val pendingEndpointUpdate =
        PendingHandoffUpdate<Iap2SenderSession.CarPlayEndpoint>({ sessionStarting.get() }) { it.observation }

    /**
     * 已交付但还没有网络信息的本轮端点；ctrl 邀请可据此立即触发交接。
     * 对象身份同时是等待任务的令牌：被替换或清空即表示该等待已失效。
     */
    private val pendingStartSessionEndpoint = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>(null)

    /** 主界面最近一次被盖住的时刻（uptimeMillis）；null = 主界面在前台或尚未显示。 */
    @Volatile
    private var uiPausedAtMs: Long? = null

    /** 主界面已不可见（onStop）：被浮窗盖住时不会走到这里。 */
    @Volatile
    private var uiStopped: Boolean = false

    fun onMainUiVisibilityChanged(resumed: Boolean) {
        uiPausedAtMs = if (resumed) null else android.os.SystemClock.uptimeMillis()
        if (resumed) uiStopped = false
    }

    fun onMainUiStopped() {
        uiStopped = true
    }

    /** 用户取消连接：配对线程在关键检查点看到后中止（mDNS/配对等待无法中途打断，粒度秒级）。 */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * 当前连接阶段的一句话提示（英雄卡副标题用，详细过程看日志）。
     * 由各阶段显式设置；null 时 UI 显示默认文案。
     */
    @Volatile
    var phaseHint: String? = null

    /** 取消「连接中」：停链路 + 置取消标志，卡在错误网络/等待回连时用户可主动脱身。 */
    @Synchronized
    fun cancelConnecting(context: Context) {
        appendLog("SESSION: 用户取消连接——正在停止蓝牙/WiFi 链路")
        // 用户已明确中止：上一轮弹窗的答案不能留给下一次连接复用。
        wifiPromptBroker.clear()
        // disconnect 第一步 blockNewHandoffs() 即强制释放互斥：配对流程线程可能阻塞在
        // 不可中断的连接/mDNS 等待上（如手机不在热点网段时连热点 IP），finally 遥遥无期——
        // 用户显式取消即解锁，否则英雄卡永久「正在连接」（旧流程产出已被 retireSession 清理，无冲突）
        disconnect(context)
        ui.phase(t(R.string.sc_cancelled))
    }

    @Synchronized
    fun retryReceiverDetection(context: Context): Boolean {
        val target = TargetDevicePrefs.get(context) ?: return false
        if (ReceiverDetectionRuntime.binding(target.address) != ReceiverDetectionRuntime.detection.snapshot()?.binding) return false
        if (videoSender?.isRunning == true || sessionStarting.get() || !ReceiverDetectionRuntime.retryBluetooth()) return false
        disconnect(context, DiagnosticCode.SUPERSEDED, preserveDetection = true)
        startConnection(context)
        return true
    }

    /**
     * 发送端 BT 链路断开（保活泵退出）。配对进行中（未推流）时本轮已注定失败——
     * 立即释放「会话处理中」互斥并唤醒卡住的等待（车机 15s 拆链 + 我们 120s 加入
     * 等待的窗口错配曾让僵尸轮次堵门：车机重连带来的新端点被「已有会话在处理中」
     * 拒绝，只剩 keep-alive 干等到用户放弃，2026-10-22 奥迪 3315 真机定谳）。
     *
     * 置 [connectCancelled] 让卡在 WiFi 加入等分片检查的配对线程在 ≤200ms 内退场；
     **不停 BT 组件**（车机大概率立刻回连监听）。新轮次起步处会清掉本标志——
     * 能走到那里说明 BT 组件仍在运行（用户取消路径 disconnect 会停组件、被端点
     * 守卫拦截），清除是安全的。
     */
    fun onSenderLinkClosed(
        observation: ReceiverObservation? = null,
        peerAddress: String? = null,
    ) {
        if (bluetoothDisconnectAction() == ReceiverCompatibility.BluetoothLossAction.KEEP_NETWORK_SESSION) return
        // BT 客户端与服务端可能同时各有一条链路：迟到的非本轮链路断开不能释放本轮互斥，
        // 也不能把失败记到当前车机头上。
        val attemptPeer = activePeerAddress
        if (!peerUnknownOrMatches(peerAddress, attemptPeer)) {
            appendLog(">>> 非本轮控制链路（$peerAddress）断开——忽略（本轮 ${attemptPeer ?: "未知"}）")
            return
        }
        val closedHint = t(R.string.sc_bt_link_closed_while_joining)
        var clientToClose: AirPlayClient? = null
        // 已关闭的 RFCOMM 链路不会再有 0x4301：删除它的暂存条目；
        // 其他仍 active 的 observation 必须保留（A 断开不代表 B 的端点失效）。
        if (observation != null) {
            pendingEndpointUpdate.retainIf { it.observation != observation }
        }
        // 用户断开 / 正在清理时的链路关闭不是车机故障，不计入熔断
        val teardownInProgress = connectCancelled || disconnectCompletion != null
        // 空端点候选会先写入 pendingStartSessionEndpoint 再释放 handoff ownership；
        // 必须在清理前记住它，否则这条链路的真实失败会被熔断过滤掉。
        val pendingStart = pendingStartSessionEndpoint.get()
        val ownsPendingStartSession = pendingStart != null && pendingStart.observation == observation
        // cancelIfOwnedBy 在“已被取消”时也会释放所有权但返回 false；先记住是否 owner，
        // 以免漏掉对仍 active 的其他 observation 的唤醒。
        val ownedHandoff = handoffOwnership.owns(observation)
        val ownedHandoffEnded =
            handoffOwnership.cancelIfOwnedBy(observation, isAlreadyCancelled = { connectCancelled }) {
                // 所有权状态、取消标志与待处理状态必须在同一锁内转换，
                // 否则新 begin 的 onAcquired 清掉 connectCancelled 后会被这里重新置位。
                connectCancelled = true
                ConnectionDiagnostics.issueCurrent(DiagnosticCode.BT_LINK_CLOSED)
                pendingStartSessionEndpoint.set(null)
                ui.phase(closedHint)
                clientToClose =
                    currentClient?.takeIf { it.pairingInProgress }?.also { client ->
                        client.cancelled = true
                    }
            }
        if (ownedHandoffEnded) {
            appendLog(">>> 本轮蓝牙链路断开——释放「会话处理中」，等待车机重连端点接手")
            clientToClose?.let { client ->
                Bg.run("bt-link-closed-pairing") { runCatching { client.close() } }
            }
        } else {
            // 空端点不占交接所有权：它所属的链路断开后，晚到 0x4301 已不可能经它到达，
            // 也不能再让 ctrl 邀请以这条死链路的身份发起交接（否则新链路的真实端点会被拒）。
            if (clearPendingStartSessionOf(observation)) {
                appendLog(">>> 空端点所属蓝牙链路已断开——放弃该等待端点，等待车机重连")
            } else if (!connectCancelled && observation != null) {
                appendLog(">>> 非当前交接链路的关闭通知——保留当前入网/配对")
            }
        }
        // 单条 RFCOMM 链路失效不是用户断开：A 已取消，但 B 仍 active，应让它接手；
        // cancelIfOwnedBy 置位的 connectCancelled 会由下一轮 begin 清除。
        // 曾经是 owner（即使取消回调因“已取消”未执行）也要释放后唤醒其他 active 链路。
        if ((ownedHandoffEnded || ownedHandoff) && !isDisconnecting()) {
            wakePendingHandoffs(observation, allowAfterCancel = true)
        }
        if (teardownInProgress) return
        // 自动重连熔断按接收端地址累计；同一 peer 可能同时有客户端/服务端两条 RFCOMM
        // 链路。只有当前交接 owner（或已交付当前会话的链路）的关闭才是失败——
        // 另一条链路关闭不能把健康交接记成 BT_LINK_CLOSED，否则连续三次就误熔断。
        val ownsCurrentSession = currentClient?.currentEndpoint?.observation == observation
        if (!countsLinkCloseForAutoReconnect(ownedHandoffEnded, ownedHandoff, ownsCurrentSession, ownsPendingStartSession)) {
            appendLog(">>> 非当前交接链路的关闭通知——不计入自动重连熔断")
            return
        }
        val active =
            ConnectionDiagnostics.timeline
                .snapshot()
                ?.takeIf { it.status == "waiting" || it.status == "streaming" }
                ?: return
        if (recordAutoReconnectOutcome(active.id, DiagnosticCode.BT_LINK_CLOSED, peerAddress ?: attemptPeer)) {
            appContext?.let { disconnect(it, DiagnosticCode.BT_LINK_CLOSED) }
        }
    }

    /** 只清除属于 [observation] 这条链路的空端点；别的链路的等待态保持不动。 */
    private fun clearPendingStartSessionOf(observation: ReceiverObservation?): Boolean {
        if (observation == null) return false
        val pending = pendingStartSessionEndpoint.get() ?: return false
        if (pending.observation != observation) return false
        return pendingStartSessionEndpoint.compareAndSet(pending, null)
    }

    /**
     * 本进程已知的车机热点 SSID：优先取已绑定的那个，其次取最近一次凭据的 SSID。
     *
     * 供给 [Iap2SenderSession] 做「本轮既无 0x5703 也无 0x4301」时的兜底：只要知道
     * 车机热点是哪个，就不必拆链——按当前网络走 mDNS/网关，晚到的 0x4301 仍会热更新。
     * 2026-10-22 奥迪 3315 日志：第 2 轮起手机就已在车机热点上，却因「无凭据」每轮
     * 8 秒自杀一次，把车机侧邀请窗口反复清零（真 iPhone 实录要约 21 秒才邀请）。
     */

    private fun knownCarHotspotSsid(): String? = usableKnownCarSsid(boundCarSsid, lastCarWifiSsid)

    /**
     * 本轮车机是否发过**新鲜** ctrl 邀请。
     *
     * 邀请只在当前网络可达时出现，可证明手机与车机同网；但上一轮遗留的旧时刻不能
     * 用来复活新轮次，所以同时检查时间窗。
     */
    private fun receiverHasInvited(observation: ReceiverObservation? = activeReceiverObservations.single()): Boolean =
        verifiedInviteNetwork(observation) != null

    private fun verifiedInviteNetwork(observation: ReceiverObservation? = activeReceiverObservations.single()): Network? {
        val connector = wifiConnector ?: return null
        val selected = currentTargetBinding()
        if (selected == null &&
            (
                observation == null ||
                    !ReceiverDetectionRuntime.isCurrent(observation)
            )
        ) {
            return null
        }
        return ctrlInviteEvidence?.verifiedNetwork(
            nowMillis = SystemClock.uptimeMillis(),
            freshnessMillis = CTRL_INVITE_FRESH_MS,
            currentTarget = selected ?: observation?.binding,
            currentNetwork = connector.currentWifiNetwork(),
            currentObservation = observation,
            requireObservation = selected == null,
            networkForAddress = connector::wifiNetworkWithLocalAddress,
        )
    }

    private fun hasRecentUnverifiedInvite(): Boolean {
        val at = ctrlAdvertiser?.lastInviteAtMillis ?: 0L
        return at > 0L && SystemClock.uptimeMillis() - at in 0..CTRL_INVITE_FRESH_MS
    }

    private fun onReceiverObservation(
        observation: ReceiverObservation?,
        started: Boolean,
    ) {
        if (started) activeReceiverObservations.begin(observation) else activeReceiverObservations.end(observation)
    }

    private fun currentTargetBinding(): String? =
        appContext?.let { context ->
            TargetDevicePrefs.get(context)?.address?.let(ReceiverDetectionRuntime::binding)
        }

    /**
     * 凭据缓存键：优先下发凭据/端点的那条链路所属车机实例，缺失时才退回已选目标。
     * 0x5703 与 0x4301 必须走同一规则，否则车机回连（对端 ≠ 已选目标）时写入与查询的键不一致。
     */
    private fun wifiCacheBinding(observation: ReceiverObservation?): String? = observation?.binding ?: currentTargetBinding()

    private fun rememberAccessoryWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        targetBinding: String?,
    ) {
        if (!wifiCredentialCache.remember(targetBinding, wifi)) {
            appendLog("WIFI: 完整凭据未进入缓存（目标实例或 SSID 不可用）——仅本轮内存使用")
        }
    }

    /** 0x4301 端点（含晚到）也要在进入配对前刷新目标+SSID 缓存。 */
    private fun rememberEndpointWifi(endpoint: Iap2SenderSession.CarPlayEndpoint) {
        if (endpoint.ssid.isBlank() || endpoint.ssid == INVITED_WIFI_LABEL) return
        // SSID 与密码成对替换：仅 SSID（KNOWN_SSID）时密码为 null，必须清掉上一轮，
        // 否则手动 WLAN 兜底按钮会把上一台车的密码复制给用户。
        val wifi = endpoint.accessoryWifiOrNull()
        carWifiState.remember(endpoint.ssid, wifi?.passphrase)
        if (wifi == null) return
        if (!wifiCredentialCache.rememberEndpoint(
                wifiCacheBinding(endpoint.observation),
                endpoint,
            )
        ) {
            appendLog("WIFI: 0x4301 完整凭据未进入缓存（目标实例或 SSID 不可用）——仅本轮内存使用")
        }
    }

    /**
     * 与 [ReceiverCompatibility] 共用同一份艾瑞泽 8 身份判定（规范化规则一致）。空端点没有 0x4301
     * 的 srcvers，此时沿用识别阶段自报的 srcvers——等待晚到 0x4301 时也要能给出该车型提示。
     */
    private fun Iap2SenderSession.CarPlayEndpoint.isArrizo8Fingerprint(): Boolean =
        ReceiverCompatibility
            .resolve(receiverSignals.copy(srcvers = sourceVersion ?: receiverSignals.srcvers))
            .arrizo8ConnectWindowHint

    /** 仅艾瑞泽 8 指纹显示 30s 车机窗口提示；其它车型只显示通用说明。 */
    fun currentReceiverUsesArrizo8Hint(): Boolean = currentReceiverMatchesArrizo8

    /**
     * 最近一次 0x5703 的热点密码（仅内存，不落盘）。
     * 「车机热点 → 去连接」按钮复制到剪贴板用（手动加入兜底场景）。
     */
    val lastWifiPsk: String? get() = carWifiState.passphrase

    /**
     * 最近一次从车机拿到的热点 SSID（0x5703 或 0x4301，两条路径都写）。
     *
     * 与 [boundCarSsid] 的区别：boundCarSsid 只在**绑定成功**后才有值，而这里在
     * 「拿到凭据」时就写——用于无新凭据时判断「这台车机应该连哪个热点」。
     * 与密码是同一份原子快照，`KNOWN_SSID`（只有名称）不会沿用上一台车的密码。
     */
    val lastCarWifiSsid: String? get() = carWifiState.ssid

    /** 挂屏 UI 钩子：SSID 变化时通知当前 Activity 回填输入框（可空）。 */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** 挂屏 UI 钩子：重要连接提示（WiFi 未开启/热点加入限制等）弹给用户看。 */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** 弹窗规格（加入热点确认等两按钮询问）。 */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
        /** 本次询问的标识：取消等待时只关这一次的弹窗，不误关后续轮次的提问。 */
        val requestId: Long = 0L,
    )

    /**
     * 挂屏 UI 钩子：两按钮确认弹窗。
     * onChoice：true=positive；false=negative；null=无人应答/被关闭（调用方兜底）。
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    /**
     * 同一轮连接内相同问题（且同一张网络）的弹窗单飞：0x5703 与 0x4301 两条后台链路
     * 可能同时走到「使用当前 Wi-Fi？」，只显示一次并复用答案。随新一轮连接清空。
     */
    private val wifiPromptBroker =
        WifiPromptBroker<DialogSpec, WifiNetworkToken<Network>>(
            tokensMatch = { shown, current -> shown.matches(current.network, current.ssid) },
        )

    /**
     * 关闭仍在等待的某一次询问（参数与 [DialogSpec.requestId] 相同）。
     *
     * [WifiPromptBroker] 只负责「停止等待」，不会关掉已经显示的 AlertDialog：取消连接或
     * 蓝牙拆链让等待提前返回后，弹窗仍留在屏上无人消费（用户会看到一个点了也没用的框）。
     * 等待方退出时必须同时撤框，因此按 requestId 单独登记。
     */
    @Volatile
    var dialogDismissSink: ((Long) -> Unit)? = null

    private val ui: SessionUiPort<DialogSpec> =
        LegacySessionUiPort(
            setPhase = { phaseHint = it },
            hintSink = { uiHintSink },
            ssidSink = { ssidSink },
            dialogSink = { dialogSink },
            dismissSink = { dialogDismissSink },
        )

    private val dialogRequestIds =
        java.util.concurrent.atomic
            .AtomicLong()

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // 应用专属网络被系统断开（车机熄火/离开范围）：明确告知用户，
                    // 而不是让英雄卡在「已连接」与失败之间静默摇摆
                    connector.onNetworkLost = { ssid ->
                        ConnectionDiagnostics.issueCurrent(DiagnosticCode.WIFI_LOST)
                        boundCarSsid = null
                        ctrlAdvertiser?.resetInviteCount()
                        ctrlInviteEvidence = null
                        endpointBrowser?.resetCache()
                        ui.phase(t(R.string.sc_hotspot_lost, ssid))
                        ui.hint { t(R.string.sc_hotspot_lost_hint, ssid) }
                        appendLog(">>> 车机热点已断开——网络绑定、发现缓存与旧 socket 一并失效")
                        val client = currentClient
                        if (client != null) {
                            client.cancelled = true
                            Bg.run("wifi-lost-close-client") { runCatching { client.close() } }
                        }
                    }
                }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // 目标车机动态读取：选择后下一轮连接尝试/下一次连入即生效，无需重启链路
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "车机回连") },
                    onLinkClosed = ::onSenderLinkClosed,
                    onPreEndpointFailure = ::onPreEndpointRoundFailed,
                    knownCarSsid = ::knownCarHotspotSsid,
                    receiverInvited = ::receiverHasInvited,
                    onObservation = ::onReceiverObservation,
                    unverifiedInvitePending = ::hasRecentUnverifiedInvite,
                    onGeneration = { g ->
                        appendLog("IAPLINK: 本条蓝牙会话链路代际 → $g（随端点传递到 AirPlay）")
                    },
                    targetProvider = targetProvider,
                    diagnostics = bluetoothDiagnostics,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "主动连车机") },
                    onLinkClosed = ::onSenderLinkClosed,
                    onPreEndpointFailure = ::onPreEndpointRoundFailed,
                    knownCarSsid = ::knownCarHotspotSsid,
                    receiverInvited = ::receiverHasInvited,
                    onObservation = ::onReceiverObservation,
                    unverifiedInvitePending = ::hasRecentUnverifiedInvite,
                    onGeneration = { g ->
                        appendLog("IAPLINK: 本条蓝牙会话链路代际 → $g（随端点传递到 AirPlay）")
                    },
                    targetProvider = targetProvider,
                    diagnostics = bluetoothDiagnostics,
                    onAdapterStateChanged = { state -> bluetoothServer?.onAdapterStateChanged(state) },
                    onAwaitingReceiverTap = { name ->
                        // 艾瑞泽 8 这类车机只能由车机端发起，需要用户在车机上操作（2026-11-02）
                        ui.phase(t(R.string.sc_tap_phone_on_receiver, name))
                        ui.hint { t(R.string.sc_tap_phone_on_receiver_hint, name) }
                    },
                )
            ctrlAdvertiser =
                CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }.also { advertiser ->
                    advertiser.onInvite = { localAddress ->
                        ConnectionDiagnostics.milestoneCurrent(ConnectionMilestone.CTRL_INVITED)
                        onCtrlInvite(localAddress)
                    }
                }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // 手机双归属（连车机热点 + 自己开热点共享给电脑）时 mDNS 会跨网
                        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把
                        // MacBook 当成车机连了 120s。非本网段一律不算车机。
                        // 地址判定统一走 EndpointHosts：IPv4 按 /24 前缀，链路本地 IPv6
                        // 天然只在本链路，全局 IPv6 按 /64 比对。旧实现只认 IPv4，会把
                        // 只广播 fe80:: 的奥迪 4xxx 直接丢掉（2026-10-19）。
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }

        // 上次进程若在物理息屏期间崩溃/被杀，面板会停在 OFF（UserService 是常驻 shell 进程，
        // 它持有的电源状态不随应用进程消失）。新进程启动时恢复一次：此刻还没有会话，
        // 恢复是安全的——否则用户拿到的是一块按不亮的黑屏（可按电源键两次救回）。
        Bg.run("screen-power-startup-restore") {
            if (ShizukuGate.state(context) != ShizukuGate.State.READY) return@run
            val deadline = android.os.SystemClock.uptimeMillis() + 3_000L
            while (android.os.SystemClock.uptimeMillis() < deadline && CarInputClient.service == null) {
                CarInputClient.ensure(context)
                Thread.sleep(150)
            }
            // 只恢复「已绑定服务」的情况：Shizuku 没起来时不必打扰用户（面板也不会是我们关的）
            if (CarInputClient.service != null && !CarInputClient.setPhysicalScreenPower(true)) {
                appendLog("SCREEN: 启动恢复物理屏失败（ROM 未授权 shell 电源控制）")
            }
        }
    }

    /** 状态行文本（Activity 渲染 statusText 用）。 */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** 会话是否活跃（推出流进程或 BT 任一在跑）。 */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /** ACL 广播与 RFCOMM 关闭共用策略，避免两条路径对网络交接做出相反判断。 */
    fun bluetoothDisconnectAction(): ReceiverCompatibility.BluetoothLossAction {
        if (ReceiverDetectionRuntime.awaitingProfileTrial) return ReceiverCompatibility.BluetoothLossAction.KEEP_NETWORK_SESSION
        val client = currentClient
        val policy = client?.receiverCompatibility() ?: ReceiverCompatibility.resolve(ReceiverProfile.Signals())
        return ReceiverCompatibility.bluetoothLossAction(
            policy,
            videoSender?.isRunning == true,
            client?.networkHandoffReady == true,
            client?.bluetoothReleaseRequested == true,
        )
    }

    private fun stopFormalCarLife() {
        carLifeOutput = null
        currentCarLifeSession?.let {
            it.stop()
            releaseCarLifeSession(it)
        }
        synchronized(carLifeSessions) {
            formalCarLifeSession = null
        }
    }

    /** 正式会话的 CarLife 源：输出按 [carLifeOutput] 路由，旧轮迟到回调被隔离。 */

    /**
     * 实验（issue #62，PR #78 评审 P2）：在 **VideoConfig 实际写出之后**判定面板几何是否生效。
     *
     * 只在发送成功的尺寸上判定，不能用「请求值」或「首个源帧到达时的画布」代替；同一尺寸只报一次，
     * 尺寸变化（源尺寸变化、切换转码、主屏恢复重发）时重新判定，避免一次过早结论覆盖后续事实。
     */
    private fun reportPanelExperiment(
        target: Pair<Int, Int>,
        actualWidth: Int,
        actualHeight: Int,
        lastReported: AtomicReference<Pair<Int, Int>?>,
    ) {
        val aligned = ((actualWidth / 2) * 2) to ((actualHeight / 2) * 2)
        if (lastReported.getAndSet(aligned) == aligned) return
        when (DisplaySurfaceReport.panelExperimentOutcome(target.first, target.second, actualWidth, actualHeight)) {
            DisplaySurfaceReport.PanelExperimentOutcome.EFFECTIVE ->
                appendLog(
                    "VIDEO: 实验面板几何已生效——实际发送 ${actualWidth}x$actualHeight" +
                        " = 目标 ${target.first}x${target.second}",
                )
            DisplaySurfaceReport.PanelExperimentOutcome.CARWITH_DIFFERENT ->
                appendLog(
                    "VIDEO: 实验面板几何未生效——目标 ${target.first}x${target.second}，" +
                        "实际发送 ${actualWidth}x$actualHeight" +
                        "（CarWith 未采纳请求或本机「显示」缩放非 100%）",
                )
            DisplaySurfaceReport.PanelExperimentOutcome.NOT_REQUESTED -> Unit
        }
    }

    private fun newFormalCarLifeSession(
        context: Context,
        requestedVideoSize: Pair<Int, Int>? = null,
        preferVehicleResolution: Boolean = false,
    ): CarLifeSession {
        lateinit var session: CarLifeSession
        session =
            CarLifeSession(
                application = context.applicationContext as android.app.Application,
                onAccessUnit = onAccessUnit@{ unit ->
                    if (currentCarLifeSession !== session) return@onAccessUnit
                    val output = carLifeOutput ?: return@onAccessUnit
                    val joinsBefore = output.gate.joins
                    val frame = output.gate.admit(unit) ?: return@onAccessUnit
                    if (output.gate.joins != joinsBefore) {
                        appendLog(
                            "CARLIFE: 入流门控第 ${output.gate.joins} 次接入（此前等 IDR 丢弃 ${output.gate.droppedBeforeLastJoin} 帧，" +
                                "补参数集=${output.gate.filledAtLastJoin.ifEmpty { "无（帧内已带齐）" }}）",
                        )
                    }
                    ConnectionDiagnostics.timeline.sourceFrame(output.attempt)
                    val sender = output.sender
                    val dimensions = frame.dimensions()
                    if (dimensions != null && sender.isNewSourceSize(dimensions.first, dimensions.second)) {
                        ConnectionDiagnostics.timeline.note(
                            output.attempt,
                            "CARWITH_VIDEO_FORMAT ${dimensions.first}x${dimensions.second}",
                        )
                        sender.setVideoDimensions(dimensions.first, dimensions.second)
                        appendLog("CARLIFE: 实际视频尺寸 ${dimensions.first}x${dimensions.second}")
                    }
                    if (!sender.submitExternalFrame(frame.toExternalVideoFrame())) output.gate.reset()
                },
                onEnded = { reason ->
                    // 旧源的迟到回调不能停掉新一轮的发送端
                    val output = carLifeOutput.takeIf { currentCarLifeSession === session }
                    if (output != null) {
                        ConnectionDiagnostics.timeline.finish(output.attempt, session.endDiagnosticCode, true, reason)
                        appendLog("CARLIFE: 会话结束 - $reason")
                        nativeAudioBridge?.stop()
                        output.sender.stop()
                    } else {
                        appendLog("CARLIFE: 未接入推流的旧会话结束 - $reason")
                    }
                    releaseCarLifeSession(session)
                },
                onAudioEvent = onAudioEvent@{ channel, event ->
                    if (currentCarLifeSession !== session) return@onAudioEvent
                    val attempt = carLifeOutput?.attempt
                    if (attempt != null && event is CarLifeAudioEvent.Init) {
                        ConnectionDiagnostics.timeline.note(
                            attempt,
                            "CARWITH_AUDIO_FORMAT channel=$channel rate=${event.sampleRate} " +
                                "channels=${event.channels} bits=${event.bitsPerSample}",
                        )
                    }
                    if (attempt != null && event is CarLifeAudioEvent.Pcm) {
                        ConnectionDiagnostics.timeline.media(attempt, false, event.littleEndian.size)
                    }
                    nativeAudioBridge?.submit(channel, event)
                },
                log = { line -> appendLog(line) },
                requestedVideoSize = requestedVideoSize,
                preferVehicleResolution = preferVehicleResolution,
                launchStrategy = CarLifeLaunchStrategy.COMPATIBLE,
                onReturnToCar = onReturnToCar@{
                    if (currentCarLifeSession !== session) return@onReturnToCar
                    val client = currentClient ?: return@onReturnToCar
                    val note = client.modes.onUserReturnedToCar()
                    enqueueModesAssert(client, "CarWith 返回车机（→ $note）", requestKeyFrame = false)
                },
            )
        return session
    }

    /**
     * 「启动连接」主体（权限检查由 Activity 完成后调用）：前台保活服务 + 双向蓝牙。
     * 幂等：已有会话时 BT 组件的 start() 自行短路，不会双开。
     */
    @Synchronized
    fun startConnection(
        context: Context,
        automatic: Boolean = false,
        /** 自动启动时可带的 ACL 对端地址；未选定目标车机时用它隔离熔断。 */
        automaticPeerAddress: String? = null,
    ) {
        if (automatic && AutoConnectPrefs.isManualStopCoolingDown(context)) {
            appendLog("AUTO: 手动断开后的 30 秒冷却期——丢弃已排队的自动连接")
            return
        }
        if (automatic) {
            val peer = automaticPeerAddress ?: TargetDevicePrefs.get(context)?.address
            reconnectAttribution.block(peer)?.let { block ->
                appendLog(
                    "AUTO: 连续 ${block.failures} 次会话在出画前被车机断开——暂停自动重连 ${block.remainingSeconds} 秒" +
                        "（可在 App 内手动连接）",
                )
                return
            }
        }
        val waitingForTeardown =
            teardownCoordination.deferStartIfStopping(
                retryOriginalEntry = { startConnection(context, automatic, automaticPeerAddress) },
                logTimeout = { appendLog("SESSION: 上一轮链路清理尚未结束，本次连接不启动") },
            )
        if (waitingForTeardown) {
            return
        }
        if (!automatic) {
            AutoConnectPrefs.clearManualStop(context)
            // 用户主动点「连接」= 明确的再试意愿：清空自动重连熔断，不拦手动路径
            clearReconnectGuard(context)
        }
        val alreadyRunning = isSessionRunning()
        if (!alreadyRunning) {
            // 新一轮从已知对端起步；手动连接不沿用上一台车机的地址，避免失败记到别人头上。
            val selected = TargetDevicePrefs.get(context)?.address
            activePeerAddress = if (automatic) automaticPeerAddress ?: selected else selected
        }
        val diagnosticAttempt =
            if (isSessionRunning()) {
                ConnectionDiagnostics.timeline.snapshot()?.id ?: ConnectionDiagnostics.begin("user_start")
            } else {
                ConnectionDiagnostics.begin("user_start")
            }
        if (ConnectionDiagnostics.timeline.snapshot()?.stage == ConnectionStage.PRECHECK) {
            ConnectionDiagnostics.timeline.stage(diagnosticAttempt, ConnectionStage.BLUETOOTH)
        }
        if (!alreadyRunning) {
            bluetoothDiagnostics.begin(diagnosticAttempt)
            ReceiverDetectionRuntime.connectionStarted(diagnosticAttempt)
        }
        connectCancelled = false
        ui.phase(t(R.string.sc_bt_ready))
        when (
            ShizukuGate
                .state(context)
        ) {
            ShizukuGate.State.READY ->
                appendLog("SHIZUKU: 就绪（触摸注入可用）")

            ShizukuGate.State.UNAUTHORIZED -> {
                appendLog("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗点允许后重连")
                ShizukuGate
                    .requestPermission()
            }

            ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」")

            ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: 未安装（隐形屏模式需要）")
        }
        CarPlayService
            .start(context)
        // 能力发布先于连接（catplay 的做法：CarPlayCtrlServer 在会话一开始就常驻广播
        // `_carplay-ctrl._tcp`，之后每 1s force_announce，收到车机邀请才建 AirPlay）。
        // 我们此前要等 0x5703 才注册，导致「车机发现手机」这一步总落在热点绑定之后，
        // 每轮多花 1~3 秒；现在启动连接即发布，换网时 ensureForNetwork 会自动重注册。
        // 会话进行中再次调用（如上车自动连接在车机蓝牙重连时触发）不得清零：邀请时刻与重试次数
        // 还在被当前会话使用（帕萨特 2026-11-01 21:45：RECORD 后蓝牙断开 4.5 s 又连上，计数被中途清零）
        if (!alreadyRunning) {
            // 上一轮弹窗的答案不代表用户这一次的选择：新一轮从干净状态开始。
            wifiPromptBroker.clear()
            ctrlAdvertiser?.resetInviteCount()
            ctrlInviteEvidence = null
            pendingStartSessionEndpoint.set(null)
            currentReceiverMatchesArrizo8 = false
            inviteRetryCount = 0
            lastInviteReconnectAtMillis = 0L
            forceKeyFrameCount = 0
            requestUiCount = 0
            takeBackFailures = 0
            touchLogCount = 0
        } else {
            appendLog("SESSION: 会话已在运行——沿用当前会话，不清零邀请与要帧计数")
        }
        // 后台发布：start() 首次要向 Shizuku 解析真实蓝牙 MAC（shell），不能在主线程做
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // 真车路径：手机主动连车机（SDP …caff 配件服务）
        // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
        bluetoothClient?.start()
        bluetoothServer?.start()
        // 诊断（奥迪实测：手机与车机「蓝牙已连接」时车机不开放 CarPlay）：
        // 连之前把目标设备的蓝牙配置文件状态打出来，失败时一眼看出是不是这个原因。
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: 连接前「${target.name}」的蓝牙配置文件状态=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "未连接"),
            )
        }
    }

    /**
     * 结束连接；自动蓝牙断开必须保留实际原因，不能记成用户手动停止。
     *
     * 用户断开且会话已激活时，后台按 iPhone 顺序拆会话（[AirPlayClient.stopLikeIphone]）；
     * 车机不回 TEARDOWN 时先离开车机热点，模仿 iPhone 直接离网（车机自行收尾）。
     * 新连接等这轮清理完成（[disconnectCompletion]）再启动。
     */
    @Synchronized
    fun disconnect(
        context: Context,
        reason: DiagnosticCode = DiagnosticCode.USER_STOP,
        preserveDetection: Boolean = false,
    ) {
        // 先原子关闭交接入口并释放所有权，再置取消标志；否则端点线程可能在
        // “看到 disconnectCompletion=null” 后重新 begin 并清掉 connectCancelled。
        handoffOwnership.blockNewHandoffs()
        connectCancelled = true
        // 先读本轮 attempt：retireSession 后时间线仍保留结果，但这里尽早固定标识
        val autoAttempt = ConnectionDiagnostics.timeline.snapshot()?.id
        if (reason == DiagnosticCode.USER_STOP) {
            userStopGeneration++
            AutoConnectPrefs.recordManualStop(context)
            // 用户主动断开：自动重连熔断一并清零
            clearReconnectGuard(context)
        }
        if (disconnectCompletion != null) return
        val completed = java.util.concurrent.CountDownLatch(1)
        disconnectCompletion = completed
        val retiringClient = currentClient
        val retiringWifi = wifiConnector
        val retiringVideo = videoSender
        val hadProjection = CarPlayService.mediaProjection != null
        try {
            ConnectionDiagnostics.finishCurrent(reason, false)
            recordAutoReconnectOutcome(autoAttempt, reason)
            if (preserveDetection) {
                ReceiverDetectionRuntime.retireConnection()
            } else {
                ReceiverDetectionRuntime.stop(reason.name, cancelled = reason == DiagnosticCode.USER_STOP)
            }
            pendingEndpointUpdate.clear()
            pendingStartSessionEndpoint.set(null)
            retireSession(closeClient = false, preserveScreenSocket = true)
            BtSessionGate.sessionActive = true
            // BT 控制会话只在这里（用户终态）退役，socket close 让保活读循环立刻退出：
            // 否则它阻塞到对端超时（实测 ~24s）才结束，用户取消后英雄卡持续转圈。
            // 配对中绝不能断——DiPlay 在隧道 iAP2 就绪前看到 RFCOMM 断开即判
            // bring-up failed 并拆整轮无线栈（含推流中的 AirPlay 会话，2026-10-16 实测）。
            ui.phase(null)
            CarPlayService
                .stop(context)
            boundCarSsid = null
            // shell 兜底存下的系统网络（connect-network）自动 forget 还原：无互联网热点
            // 被系统当默认网会挤掉移动数据。失败只记日志（用户可到 WLAN 手动删）。
        } finally {
            disconnectTail.enqueue(
                DisconnectSnapshot(
                    reason = reason,
                    client = retiringClient?.let { RetiringAirPlayClient(it) },
                    stopCapturedVideo = { retiringVideo?.stop() },
                    releaseCapturedWifi = { retiringWifi?.release() },
                    completed = completed,
                ),
                disconnectLive,
            )
        }
        val endAction = if (reason == DiagnosticCode.USER_STOP) "手动断开" else "自动断开（${reason.name}）"
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
            appendLog("已${endAction}并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
        } else {
            appendLog("已${endAction}并停止保活服务（常亮已恢复）")
        }
    }

    /**
     * Phase 1（拿到端点）之前就失败的 BT 轮次。
     *
     * [Iap2BluetoothServer]/[Iap2BluetoothClient] 的 `onLinkClosed` 只在已交付端点后触发
     * （语义是「已交付会话的链路断了」），因此协商超时/识别失败/车机在 Phase 1 拆链这类
     * 轮次此前只写一行 `SESSION 失败`：时间线不会结束，`elapsedMs` 一直累加到诊断报告里，
     * 熔断也完全看不到这些失败（bug #66：保时捷退化 0x1d01 反复协商重启，卡在
     * BLUETOOTH:WIFI_INFO 224s+，自动重连无限重试）。
     *
     * 只结束仍处于建立阶段的当前时间线，并在本轮仍是当前轮次、当前对端时记账；
     * 用户断开/正在收尾、以及已被新一轮取代的轮次一律不动。
     *
     * 两个出口都必须处理，否则熔断在 #66 的形态下永远到不了阈值：
     * - **未触发熔断时**立刻开一条新的 BLUETOOTH 时间线。客户端/服务端在线路断开后会在
     *   自己的循环里再起一轮 `runSession`，但那不会调用 [startConnection]；若沿用刚结束的
     *   attempt，之后每轮都因 status=failed 被 [settlesOnPreEndpointFailure] 拒绝，而
     *   `recordFastFailure` 又按 attempt 去重，于是连续失败永远只记 1 次。
     * - **触发熔断后**必须真正停掉 BT 客户端/服务端（与 [disconnect] 同路），否则只写一行
     *   日志、它们的循环仍在重启会话，冷却期形同虚设。此时**不得**再开新 attempt：
     *   [disconnect] 会取当前时间线 id 再记一次失败，把第 3 次改写成第 4 次。
     */
    private fun onPreEndpointRoundFailed(
        reason: String,
        peerAddress: String?,
    ) {
        if (isDisconnecting() || connectCancelled) return
        if (!peerUnknownOrMatches(peerAddress, activePeerAddress)) {
            appendLog(">>> 非本轮蓝牙链路（$peerAddress）在端点前结束——忽略（本轮 ${activePeerAddress ?: "未知"}）")
            return
        }
        val app = appContext ?: return
        val snapshot = ConnectionDiagnostics.timeline.snapshot() ?: return
        // 空端点（等晚到 0x4301）也停在 waiting+BLUETOOTH，但那是**有意**的等待，
        // 由 watchForPendingStartSession 自己收尾，不能被别的链路在这里结束。
        // 还有别的链路活着时同样不判定：无法证明这条失败属于正在等待的那一轮，
        // 宁可少收尾一次，也不能把别家的失败记到当前轮次头上（会误触发熔断）。
        // 必须用 hasAny() 而不是 single()!=null：只要有未知身份的链路、或剩下两条以上
        // 已知链路，single() 就返回 null，那正是「还有链路在协商/即将交付端点」的情形。
        // 目标形态（协商超时后车机才重连）里失败链路已从集合移除，hasAny() 为 false。
        if (!settlesOnPreEndpointFailure(
                status = snapshot.status,
                stage = snapshot.stage,
                waitingForLateEndpoint = pendingStartSessionEndpoint.get() != null,
                anotherLinkLive = activeReceiverObservations.hasAny(),
            )
        ) {
            return
        }
        ConnectionDiagnostics.timeline.finish(
            snapshot.id,
            DiagnosticCode.BT_LINK_CLOSED,
            true,
            "endpoint not delivered: ${DiagnosticRedactor.scrubAtSource(reason).take(120)}",
        )
        // 与其它失败出口共用同一套判据，只额外说明这是「端点交付前」的轮次：
        // 这类轮次从未进入界面交接，不受 60s 快速失败门限约束（见
        // [countsAsAutoReconnectFailure] 的 beforeEndpoint）。
        val ended = ConnectionDiagnostics.timeline.snapshot() ?: return
        if (!countsAsAutoReconnectFailure(DiagnosticCode.BT_LINK_CLOSED, ended.videoFrames, ended.elapsedMs, beforeEndpoint = true)) {
            return
        }
        val blocked = AutoReconnectGuard.recordFastFailure(app, peerAddress, snapshot.id)
        if (blocked == null) {
            // 未触发熔断：本轮时间线已结束，仍在运行的 BT 循环需要自己的 attempt（见上方 KDoc）。
            beginPreEndpointRetryAttempt()
            return
        }
        appendLog(
            "AUTO: 连续 ${blocked.failures} 轮在端点交付前失败——暂停自动重连 ${blocked.remainingSeconds} 秒" +
                "（可在 App 内手动连接）",
        )
        // 熔断必须真正生效：只写日志的话，仍在运行的 BT 循环会马上再拉一轮。
        // 与 onSenderLinkClosed 触发熔断时同样走 disconnect（BT_LINK_CLOSED 不是用户停止，
        // 不会清空熔断状态）。
        //
        // 这里**不能**先开新 attempt：disconnect 会取当前时间线的 id 再记一次失败
        // （disconnect → finishCurrent/recordAutoReconnectOutcome）。那样会把
        // failures 从 3 改成 4、再延长一次冷却期，并把 recordedAttempt 改写成一条
        // 几乎零时长的合成轮次，支撑报告也就看不到真正触发熔断的那一轮。
        appContext?.let { disconnect(it, DiagnosticCode.BT_LINK_CLOSED) }
    }

    /**
     * 未触发熔断、端点前失败的轮次结束后，为仍在运行的 BT 循环准备一条新的 BLUETOOTH 时间线。
     *
     * 只在「本轮尚未触发熔断」且确实还有 BT 组件在跑时调用：触发熔断后调用方会走
     * [disconnect]，它会按当前时间线 id 再记一次失败；此时若先造一条空 attempt，
     * 会把熔断次数与冷却期改写。真正收尾（用户断开/熔断）之后也不得凭空造出一条
     * 会一直累加 `elapsedMs` 的 waiting 记录。
     */
    private fun beginPreEndpointRetryAttempt() {
        if (isDisconnecting() || connectCancelled) return
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) return
        val attempt = ConnectionDiagnostics.begin("bt_retry")
        bluetoothDiagnostics.begin(attempt)
        ConnectionDiagnostics.timeline.stage(attempt, ConnectionStage.BLUETOOTH)
    }

    /**
     * 自动重连熔断的唯一记账点：只统计「零帧」轮次（车机在出画前拆链），成功出画重置。
     * 返回 true 表示**本轮刚触发熔断**——调用方应收掉 BT 监听与保活服务，避免车机一
     * ACL 连上就再拉一整轮（2026-11-12 宝马 MGU-86：2 分钟内 4 轮全在 RECORD 后秒断）。
     *
     * 所有不走 [disconnect] 的失败出口（SETUP/RECORD、配对、加入热点）也必须调用本函数：
     * 这些出口只结束时间线并关客户端，漏记会让快速失败无限循环。
     */
    private fun recordAutoReconnectOutcome(
        attempt: String?,
        code: DiagnosticCode?,
        address: String? = activePeerAddress,
    ): Boolean = reconnectAttribution.record(attempt, code, address)

    /** 首页展示用薄 facade；Context 与墙钟都由调用方显式传入。 */
    internal fun activeReconnectBlock(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): AutoReconnectGuard.Block? = reconnectAttribution.activeBlock(context, nowMs)

    /** 手动清空自动重连熔断的薄 facade。 */
    internal fun clearReconnectGuard(context: Context) {
        reconnectAttribution.clear(context)
    }

    // ---- 物理屏真息屏（同 scrcpy -S/--turn-screen-off）----

    /** 物理主屏是否被我们置为断电（只用于「要不要恢复」的判断，避免无谓的 AIDL 调用）。 */
    @Volatile
    private var phoneScreenOff = false

    /**
     * 电源切换串行化。enter/restore 都是 Bg 任务（cached 池不保证顺序），
     * 不加锁会出现「restore 先跑完、enter 后跑」→ 会话都结束了面板还被关掉。
     */
    private val screenPowerLock = Any()

    /**
     * 会话进入「真息屏」：把**物理主屏**硬件断电，系统仍保持唤醒，虚拟屏照常出帧。
     *
     * 与暗幕的区别：暗幕只是一层黑 View，触摸层仍通电（口袋误触、耗电）；
     * 这里是 SurfaceFlinger 级断电（触摸 IC 同步熄），观感等同锁屏，但**不锁屏**——
     * 车机侧的高德/QQ 音乐不会被 Keyguard 挂起。
     *
     * 失败只记日志：ROM 没给 shell 权限或取不到 display token 时，最坏退回暗幕。
     */
    private fun enterPhoneScreenOff() {
        val ctx = appContext ?: return
        // 没有在推流就不必关屏（设置面板无会话时切换也会走到这里）
        if (videoSender?.isRunning != true) return
        Bg.run("screen-power-off") {
            synchronized(screenPowerLock) {
                if (phoneScreenOff) return@synchronized
                // Bg 任务可能晚于会话结束才被执行：会话没了就绝不能再关屏
                if (videoSender?.isRunning != true) {
                    appendLog("SCREEN: 会话已结束——跳过物理息屏")
                    return@synchronized
                }
                // 手机若正在休眠：先唤醒让框架把物理屏置 ON；否则我们设 OFF 之后，框架的
                // 唤醒流程又会把面板点亮（两者抢同一个 SurfaceFlinger 电源状态）。
                val interactive =
                    runCatching {
                        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                    }.getOrDefault(true)
                if (!interactive) {
                    appendLog("SCREEN: 手机处于休眠——先唤醒再物理息屏（避免框架把面板重新点亮）")
                    Shell.exec("input keyevent KEYCODE_WAKEUP", 4_000L)
                    Thread.sleep(400)
                    // 无密码 / SmartLock 场景顺手划开锁屏；安全锁会失败，忽略即可
                    Shell.exec("wm dismiss-keyguard", 3_000L)
                }
                // 断电由暗幕承载（2026-10-24 用户实测定谳：面板断电后触摸 IC 仍工作，
                // 点击暗幕即唤醒；暗幕同时是口袋误触盾——没有它，兜里触摸会点到前台应用）。
                // enterDimNow = 上暗幕 + 断面板电 + 记账（panelOffByUs），与手动假息屏同路。
                if (ScreenDimmer.isAttached()) {
                    ScreenDimmer.enterDimNow()
                    phoneScreenOff = true
                    appendLog("SCREEN: 真息屏已进入（暗幕承载触摸检测——点暗幕唤醒，无需按电源键）")
                } else {
                    // 暗幕不可用（缺悬浮窗权限）降级裸断电：无触摸唤醒手段，只能按电源键
                    val ok = CarInputClient.setPhysicalScreenPower(false)
                    phoneScreenOff = ok
                    appendLog(
                        if (ok) {
                            "SCREEN: 物理屏已断电（暗幕不可用——无触摸唤醒手段，按电源键两次可恢复）"
                        } else {
                            "SCREEN: 物理屏断电失败（ROM 未给 shell 权限 / 取不到 display token）"
                        },
                    )
                }
            }
        }
    }

    /** 「导航时音乐音量」在会话中调整：交给正在运行的 CarWith 原生音频桥（没有则下次连接生效）。 */
    fun applyDuckDb(duckDb: Int) {
        nativeAudioBridge?.setDuckDb(duckDb.toFloat())
    }

    /**
     * 设置面板切「连接时真息屏」：会话中立即收放（没有会话时什么都不做）。
     *
     * 车机屏上就能操作，所以手机面板黑着也不影响切换；关掉会立刻把面板点亮。
     */
    fun applyScreenOffPreference(enabled: Boolean) {
        if (enabled) {
            enterPhoneScreenOff()
        } else {
            restorePhoneScreen(sleepIfCarSide = false)
        }
    }

    /**
     * 恢复物理屏点亮。幂等：没关过就什么都不做。
     *
     * @param sleepIfCarSide 车机侧断开（人在车外、手机在口袋）为 true：直接让手机休眠，
     *   不先点亮面板（否则口袋里的手机会先亮一下再熄）；用户手动断开走 false。
     */
    private fun restorePhoneScreen(sleepIfCarSide: Boolean) {
        Bg.run("screen-power-restore") {
            synchronized(screenPowerLock) {
                if (!phoneScreenOff) return@synchronized
                phoneScreenOff = false
                if (sleepIfCarSide) {
                    // 保持 SurfaceFlinger 的 OFF 状态，让框架正常休眠；下次唤醒时框架会把面板置 ON
                    Shell.exec("input keyevent KEYCODE_SLEEP", 4_000L)
                    appendLog("SCREEN: 车机侧断开——已让手机休眠（面板保持黑）")
                } else {
                    // 暗幕承载的断电态在这里收口：撤暗幕 + 恢复面板电（幂等）
                    ScreenDimmer.exitPanelOff()
                    val ok = CarInputClient.setPhysicalScreenPower(true)
                    appendLog("SCREEN: 物理屏已恢复点亮 ok=$ok")
                }
            }
        }
    }

    private val roundRetirement =
        RoundRetirement(
            object : RoundSlots<AirPlayClient, VideoSender, AudioSender, CarLifeAudioBridge, Context> {
                override var client: AirPlayClient?
                    get() = currentClient
                    set(value) {
                        currentClient = value
                    }

                override var sender: VideoSender?
                    get() = videoSender
                    set(value) {
                        videoSender = value
                    }

                override var audio: AudioSender?
                    get() = audioSender
                    set(value) {
                        audioSender = value
                    }

                override var bridge: CarLifeAudioBridge?
                    get() = nativeAudioBridge
                    set(value) {
                        nativeAudioBridge = value
                    }

                override val context: Context?
                    get() = appContext

                override fun clearRecovery() {
                    screenStreamRecovery = null
                }

                override fun clearSharedSecret() {
                    currentSharedSecret = null
                }

                override fun clearAudioHost() {
                    currentAudioHost = null
                }

                override fun markCancelled(client: AirPlayClient) {
                    client.cancelled = true
                }

                override fun setSessionInactive() {
                    // 注意：这里绝不能 detach BT 控制会话。本方法在每轮配对起步时也会执行，
                    // 而 DiPlay 在 WiFi 隧道就绪前看到 RFCOMM 断开即判 bring-up failed、
                    // closeWirelessStack 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），把已建立
                    // 甚至正在推流的 AirPlay 会话一起拆掉——「连上约 3 秒即断」循环的根因
                    // （2026-10-16 实测）。BT 退役只在用户终态做，见 disconnect()。
                    BtSessionGate.sessionActive = false
                }
            },
            object : RoundActions<AirPlayClient, VideoSender, AudioSender, CarLifeAudioBridge, Context> {
                override fun beginClient(client: AirPlayClient) {
                    client.beginOrderlyStop()
                }

                override fun stopAutoPlayWatch() {
                    this@SessionController.stopAutoPlayWatch()
                }

                override fun stopSender(sender: VideoSender) {
                    sender.stop()
                }

                override fun quiesceSender(sender: VideoSender) {
                    sender.quiesceForOrderlyStop()
                }

                override fun releaseWifiLock() {
                    StreamingWifiLock.release()
                }

                override fun stopScreenMonitor(context: Context) {
                    ScreenLockMonitor.stop(context)
                }

                override fun setDimmerInactive() {
                    // 暗幕/常亮随会话收掉（本方法在每轮配对起步也会执行，新一轮推流起来后会重新置位）
                    ScreenDimmer.setSessionActive(false)
                }

                override fun restorePhoneScreen(sleepIfCarSide: Boolean) {
                    // 物理屏兜底恢复：崩溃/异常路径若把面板留在 OFF，这里不会让它烂在黑屏里
                    this@SessionController.restorePhoneScreen(sleepIfCarSide)
                }

                override fun stopAudio(audio: AudioSender) {
                    audio.stop()
                }

                override fun stopBridge(bridge: CarLifeAudioBridge) {
                    bridge.stop()
                }

                override fun stopFormalCarLife() {
                    this@SessionController.stopFormalCarLife()
                }

                override fun closeClient(client: AirPlayClient) {
                    client.close()
                }
            },
        )

    private val videoEndedHandling =
        VideoEndedHandling(
            object : VideoEndSlots<VideoSender, Iap2BluetoothClient, CarLifeAudioBridge, Context> {
                override val currentSender: VideoSender?
                    get() = videoSender

                override val outputSender: VideoSender?
                    get() = carLifeOutput?.sender

                override val nativeBridge: CarLifeAudioBridge?
                    get() = nativeAudioBridge

                override val btClient: Iap2BluetoothClient?
                    get() = bluetoothClient

                override val context: Context?
                    get() = appContext

                override val forceKeyFrames: Int
                    get() = forceKeyFrameCount

                override val requestUi: Int
                    get() = requestUiCount

                override val touches: Int
                    get() = touchLogCount

                override val consumerReleases: Int
                    get() = consumerReleaseCount

                override fun clearCarLifeOutput() {
                    carLifeOutput = null
                }

                override fun clearNativeBridge() {
                    nativeAudioBridge = null
                }

                override fun setSessionInactive() {
                    BtSessionGate.sessionActive = false
                }

                override fun setDimmerInactive() {
                    ScreenDimmer.setSessionActive(false)
                }

                override fun resetEnvironmentSampleAt() {
                    lastEnvironmentSampleAt = 0L
                }
            },
            object : VideoEndActions<VideoSender, CarLifeSession, Iap2BluetoothClient, CarLifeAudioBridge, Context> {
                override fun log(message: String) {
                    appendLog(message)
                }

                override fun videoEndedTimeline(attempt: String) {
                    ConnectionDiagnostics.timeline.videoEnded(attempt)
                }

                override fun mediaEnded(observation: Any?) {
                    @Suppress("UNCHECKED_CAST")
                    ReceiverDetectionRuntime.mediaEnded(observation as? ReceiverObservation)
                }

                override fun stopCarLife(carLife: CarLifeSession) {
                    carLife.stop()
                }

                override fun releaseCarLife(carLife: CarLifeSession) {
                    releaseCarLifeSession(carLife)
                }

                override fun releaseWifiLock() {
                    StreamingWifiLock.release()
                }

                override fun stopScreenMonitor(context: Context) {
                    ScreenLockMonitor.stop(context)
                }

                override fun stopNativeBridge(bridge: CarLifeAudioBridge) {
                    bridge.stop()
                }

                override fun armBluetoothAfterSessionEnd(btClient: Iap2BluetoothClient) {
                    btClient.armAfterSessionEnd()
                }

                override fun restorePhoneScreen(sleepIfCarSide: Boolean) {
                    this@SessionController.restorePhoneScreen(sleepIfCarSide)
                }

                override fun cleanupShellFallbackNetwork() {
                    this@SessionController.cleanupShellFallbackNetwork()
                }

                override fun attributeAudioTeardown() {
                    this@SessionController.attributeAudioTeardown()
                }

                override fun recordAutoReconnectOutcome(
                    attempt: String?,
                    peer: String?,
                ): Boolean = this@SessionController.recordAutoReconnectOutcome(attempt, DiagnosticCode.VIDEO_CLOSED, peer)

                override fun disconnect(context: Context) {
                    this@SessionController.disconnect(context, DiagnosticCode.VIDEO_CLOSED)
                }
            },
        )

    /** 会话结束/新周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。 */
    fun retireSession(
        closeClient: Boolean = true,
        preserveScreenSocket: Boolean = false,
    ) {
        roundRetirement.retire(closeClient, preserveScreenSocket)
        // 「强制应用横屏」覆盖的复原**不在这里做**（2026-10-23 奥迪 3315 真机日志定谳）：
        // 本方法在每轮配对起步时也会执行，此时距离上一轮会话结束只有几秒——上一轮的
        // 桌面/地图应用（与新一轮是同一批包）多半还活着；而本会话的应用在 attach 里
        // 随即拉起。resetAll 的 am compat disable 若在此窗口执行，杀掉的正是**新一轮
        // 刚拉起的应用** → 虚拟屏只剩静态壁纸 → 车机收到的画面永远不变 → 不切换
        // CarPlay UI（用户看到「推流中但车机还在原界面」）。
        // 复原只在真实终态执行：disconnect()（用户断开）与推流 onEnded（车机拆链），
        // 两处各自调用 resetAllAsync。新一轮的 applyAll 与旧覆盖幂等（enable 覆盖
        // enable），无需先复原再写入。
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        audioChannelStarter.maybeStartAudioNow(context)
    }

    /** 启动音频并在成功后补 RECORD；连接、动态补启和仲裁重试三条入口共用。 */
    private fun startAudioAndRecord(
        context: Context,
        client: AirPlayClient,
        secret: ByteArray?,
        host: String?,
        logLine: String,
        recordAfterSetup: Boolean = true,
    ): Boolean = audioChannelStarter.startAudioAndRecord(context, client, secret, host, logLine, recordAfterSetup)

    private fun retryAudioAfterClaimReturned(client: AirPlayClient) {
        audioChannelStarter.retryAudioAfterClaimReturned(client)
    }

    /**
     * 0x5703 到达（拿到车机热点凭据）立即执行——真车 0x4301 的前置条件：
     * 车机在 WiFi 网络上发现手机的 `_carplay-ctrl._tcp`（TXT id=真实蓝牙 MAC）后
     * 主动发 `GET /ctrl-int/1/connect` 邀请，邀请被接受才发 0x4301。
     * 因此这里必须：绑车机 WiFi → 广播 ctrl；晚于 0x4300 则双方互等死锁（Audi 实测）。
     */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // 会话推流中收到的 0x5703 是冗余凭据（DiPlay 控制器周期重连会重发一轮
        // Phase 1，2026-10-15 实测「连上后自动断开」循环的推手之一）：此时再走绑定
        // 流程会 release+重绑进程网络（扰动正在推流的 socket）、force 重注册 ctrl
        // mDNS（DiPlay 视为手机重新上线、再拉一轮 RFCOMM）。只刷新密码缓存。
        if (videoSender?.isRunning == true) {
            if (wifi.ssid.isNotBlank()) {
                carWifiState.remember(wifi.ssid, wifi.passphrase)
                rememberAccessoryWifi(wifi, wifiCacheBinding(wifi.observation))
            }
            appendLog("0x5703 冗余凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        ui.ssidChanged { wifi.ssid }
        if (wifi.ssid.isNotBlank()) {
            carWifiState.remember(wifi.ssid, wifi.passphrase)
            rememberAccessoryWifi(wifi, wifiCacheBinding(wifi.observation))
        }
        ConnectionDiagnostics.milestoneCurrent(ConnectionMilestone.WIFI_CREDENTIALS, "source=0x5703")
        ui.phase(t(R.string.sc_got_credentials))
        appendLog(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // ctrl 广播已在 startConnection 时发布（catplay：连接前保持发布能力在线）。
        // 这里登记 SSID 并让注册对齐一次（手机若已手动连在车机热点上、Shizuku 不可用
        // 时这是唯一一次重注册机会）；加入热点后 bindCarWifiAndRegister 还会 force
        // 重注册，把组播落到车机所在网络（真车实测必需）。
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: 广播已在启动连接时发布，此处对齐到热点 ${wifi.ssid}")
        // 绑定与切换统一走自动加入流程：bindCurrentWifi 是「任意 WiFi 即成功」的盲绑，
        // 手机连着公司 WiFi 时会误判已在车机热点（ctrl 广播落在错误网络 → 双方互等死锁），
        // 必须先核对真实 SSID 再决定绑定或切换
        autoJoinCarWifi(wifi)
    }

    private val carWifiJoiner =
        CarWifiJoiner<Network, WifiNetworkToken<Network>>(
            link = { wifiConnector },
            state = carWifiState,
            bindingLock = wifiBindLock,
            globalCancelled = { connectCancelled },
            ui =
                object : CarWifiUiPort {
                    override fun phase(hint: String?) {
                        ui.phase(hint)
                    }

                    override fun hint(message: () -> String) {
                        ui.hint(message)
                    }
                },
            text = { id, args -> t(id, *args) },
            askChoice = { prompt, shownToken, isCancelled ->
                askWifiJoinChoice(
                    spec =
                        DialogSpec(
                            title = prompt.title,
                            message = prompt.message,
                            positive = prompt.positive,
                            negative = prompt.negative,
                        ),
                    shownNetwork = shownToken,
                    isCancelled = isCancelled,
                )
            },
            captureToken = ::currentNetworkToken,
            matchesToken = { token, currentNetwork, currentSsid ->
                token.matches(currentNetwork, currentSsid)
            },
            shellReady = ::shellWifiReady,
            shell =
                CarWifiShellPort { command, timeoutMs ->
                    val result = Shell.exec(command, timeoutMs)
                    CarWifiShellResult(result.exitCode, result.output)
                },
            readPreference = { preferredInternetNetwork().toCarWifiPreference() },
            useSystemWifi = { preference, shizukuReady ->
                InternetNetworkPolicy.useSystemWifi(preference.toInternetNetwork(), shizukuReady)
            },
            allowExistingSystemWifi = { preference ->
                InternetNetworkPolicy.allowExistingSystemWifi(preference.toInternetNetwork())
            },
            allowSystemFallback = { preference ->
                InternetNetworkPolicy.allowSystemFallback(preference.toInternetNetwork())
            },
            phoneModeDecision = { preference, activeTarget, activeIdentityUnknown, saved ->
                InternetNetworkPolicy
                    .phoneModeSystemWifiDecision(
                        network = preference.toInternetNetwork(),
                        activeTarget = activeTarget,
                        activeIdentityUnknown = activeIdentityUnknown,
                        saved = saved,
                    ).toCarWifiDecision()
            },
            bindVerifiedAndRegister = { ssid, verifiedNetwork, isCancelled, requiredCurrent ->
                bindCarWifiAndRegister(
                    ssid = ssid,
                    verifiedNetwork = verifiedNetwork,
                    isCancelled = isCancelled,
                    requiredCurrent = requiredCurrent,
                )
            },
            verifiedNetworkFor = ::verifiedWifiNetworkFor,
            reannounce = { ssid, force, network ->
                ctrlAdvertiser?.ensureForNetwork(ssid, force = force, network = network)
            },
            background = { name, block -> Bg.run(name, block) },
            uptimeMillis = { android.os.SystemClock.uptimeMillis() },
            sleep = { millis -> Thread.sleep(millis) },
            openWlanSettings = ::openWlanSettings,
            copyHotspotPsk = ::copyHotspotPsk,
            userChoicePending = { requestedAt ->
                WifiCarPlayConnector.isUserChoicePending(uiPausedAtMs, uiStopped, requestedAt)
            },
            log = ::appendLog,
            invitedWifiLabel = INVITED_WIFI_LABEL,
        )

    private fun InternetNetwork.toCarWifiPreference(): CarWifiInternetPreference =
        when (this) {
            InternetNetwork.PHONE -> CarWifiInternetPreference.PHONE
            InternetNetwork.CAR -> CarWifiInternetPreference.CAR
        }

    private fun CarWifiInternetPreference.toInternetNetwork(): InternetNetwork =
        when (this) {
            CarWifiInternetPreference.PHONE -> InternetNetwork.PHONE
            CarWifiInternetPreference.CAR -> InternetNetwork.CAR
        }

    private fun PhoneModeSystemWifiDecision.toCarWifiDecision(): CarWifiPhoneModeDecision =
        when (this) {
            PhoneModeSystemWifiDecision.PROCEED -> CarWifiPhoneModeDecision.PROCEED
            PhoneModeSystemWifiDecision.CONFLICT -> CarWifiPhoneModeDecision.CONFLICT
            PhoneModeSystemWifiDecision.CONFIRM -> CarWifiPhoneModeDecision.CONFIRM
        }

    /** 校对真实 WiFi 并加入车机热点（Phase 2c-2 委托给 [carWifiJoiner]）。 */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        carWifiJoiner.autoJoinCarWifi(wifi)
    }

    /** 端点先于 0x5703 到达（或上一轮换网失败）时的入网入口，遵循「互联网网络」偏好。 */
    private fun joinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): CarWifiLink.JoinResult<Network> = carWifiJoiner.joinCarWifi(wifi, shellReady)

    private fun preferredInternetNetwork(): InternetNetwork = appContext?.let(InternetNetworkPrefs::get) ?: InternetNetworkPrefs.DEFAULT

    /**
     * 本轮 shell 兜底把热点存成了系统网络时记录的 SSID——会话终态（disconnect 与
     * 推流 onEnded）自动 `forget` 还原。系统级保存无互联网热点会被当成默认网、
     * 部分 ROM 连带挤掉移动数据。
     */
    private var shellFallbackCleanupSsid: String?
        get() = carWifiState.shellFallbackCleanupSsid
        set(value) {
            carWifiState.shellFallbackCleanupSsid = value
        }

    /** 绑定当前已连接的 WiFi 并短等绑定完成（兜底路径共用）。返回绑定的网络或 null。 */
    private fun bindExistingCarWifi(
        connector: CarWifiLink<Network>,
        ssid: String,
        verifySsid: Boolean = true,
    ): Network? = carWifiJoiner.bindExistingCarWifi(connector, ssid, verifySsid)

    /** 会话终态清理 shell 兜底存的系统网络（[disconnect] 与推流 onEnded 共用）。 */
    private fun cleanupShellFallbackNetwork() {
        val ssid = shellFallbackCleanupSsid ?: return
        shellFallbackCleanupSsid = null
        Bg.run("wifi-fallback-cleanup") {
            val r = Shell.exec("cmd wifi forget-network ${shellQuote(ssid)}", 5_000L)
            appendLog(
                "WIFI: 会话末清除 shell 兜底保存的 \"$ssid\" ok=" + r.ok +
                    (if (!r.ok) "（请到 系统设置>WLAN 手动删除）" else ""),
            )
        }
    }

    /** Shizuku 就绪才可跑 `cmd wifi`（与其它 shell 调用同一守卫）。 */
    private fun shellWifiReady(): Boolean = appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true

    /** 静默路径与应用专属网络都失败：让用户重试或转手动连接。 */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        isCancelled: () -> Boolean = { connectCancelled },
    ) {
        carWifiJoiner.promptSpecifierFailed(wifi, shellReady, isCancelled)
    }

    /**
     * 绑定**已核验**的车机 WiFi 网络并重注册 ctrl 广播；绑定失败给出指引。
     *
     * 只接受调用方核验过的 [verifiedNetwork] 句柄：没有句柄时返回 false，绝不发起
     * 不带 specifier 的通配 `requestNetwork`——两张 Wi-Fi 并存时回调可能先绑另一张，
     * 随后却把车机 SSID 记到它上面，AirPlay 就走了错网络。需要用户确认的场景由
     * `CarWifiJoiner` 的确认/手动等待路径负责。
     *
     * 绑定动作用 [wifiBindLock] 串行化：auto-join 线程与端点交接线程都可能触发，
     * 并发绑定会互相 release（解绑瞬间 mDNS 广播直接失效）。
     *
     * 等待窗口 [WIFI_BIND_WAIT_S] 必须够长：旧版通配 `requestNetwork(WIFI)` 的
     * onAvailable 在「手机还没连上任何 WiFi、用户正在系统设置里手动加入热点」时，
     * 要等到真正关联+DCHP 完成才回调。旧值 5s 在奥迪实测中是致命竞态——20:02:09.352
     * 判定「不在热点」放弃整轮会话，20:02:09.467（**116ms 后**）onAvailable 就到了并
     * 绑定成功；用户被迫多等一轮 BT 重连（+13s）。宁可可中断地多等，也不要假失败。
     *
     * @return 进程网络是否绑定成功
     */
    private fun bindCarWifiAndRegister(
        ssid: String,
        verifiedNetwork: Network? = null,
        isCancelled: () -> Boolean = { connectCancelled },
        requiredCurrent: Network? = null,
    ): Boolean {
        val bound =
            wifiHandoffGuard.bind(
                isCancelled = isCancelled,
                connect = {
                    val current = wifiConnector?.currentNetwork
                    when (
                        val plan =
                            WifiBindPlan.decide(
                                ssid = ssid,
                                boundSsid = boundCarSsid,
                                verifiedNetwork = verifiedNetwork,
                                currentNetwork = current,
                                requiredCurrent = requiredCurrent,
                            )
                    ) {
                        WifiBindPlan.Decision.AlreadyBound -> true
                        is WifiBindPlan.Decision.BindVerified -> {
                            val ok = wifiConnector?.bindVerifiedNetwork(plan.network, ssid) == true
                            if (ok) boundCarSsid = ssid
                            ok
                        }
                        WifiBindPlan.Decision.NeedsConfirmation -> {
                            // 绝不回退为不带 specifier 的通配 requestNetwork：两张 Wi-Fi 并存
                            // 时回调可能先把另一张交给进程，随后却把车机 SSID 记到它上面。
                            appendLog("WIFI: 没有核验过的 \"$ssid\" Network 句柄——不发起通配绑定请求")
                            false
                        }
                        WifiBindPlan.Decision.Rejected -> false
                    }
                },
                release = { wifiConnector?.release() },
                onBound = {
                    ui.phase(t(R.string.sc_joined))
                    ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
                    appendLog(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
                },
            )
        if (!bound && !isCancelled()) {
            ui.phase(t(R.string.sc_bind_channel_failed, ssid))
            appendLog(
                ">>> 等待 ${WIFI_BIND_WAIT_S}s 仍未拿到 \"$ssid\" 的 WiFi 网络句柄——" +
                    "请确认 WLAN 已连上该热点后重新连接",
            )
        }
        return bound
    }

    /**
     * 弹窗询问用户（阻塞调用线程直到选择/关闭/90 秒超时）：
     * true=positive；false=negative；null=无人应答（调用方决定兜底）。
     *
     * 同一轮里相同问题只有在**同一张网络**上才复用答案：`shownNetwork` 是弹窗展示时
     * 采样的网络快照，手机换网后不会复用它，也不会并入正为旧网络展示的弹窗
     * （见 [WifiPromptBroker]）。`null` = 调用方没有网络快照，此问题不合并。
     *
     * 取消或轮次变化时立即返回 null，并且不再为新网络补弹旧轮次的框。
     *
     * [isCancelled] 必须是本轮的有效性判定（通常含 [connectCancelled] 与交接所有权）：
     * 车机拆链后等待要立刻退出。broker 只负责停止等待，**不会**关掉已显示的
     * AlertDialog，因此提前退出时还要按 requestId 撤框，否则用户会看到一个点了也
     * 没用的框（见 [dialogDismissSink]）。
     */
    private fun askWifiJoinChoice(
        spec: DialogSpec,
        shownNetwork: WifiNetworkToken<Network>? = null,
        isCancelled: () -> Boolean = { connectCancelled },
    ): Boolean? {
        val round = wifiPromptBroker.currentGeneration()

        fun expired(): Boolean = isCancelled() || wifiPromptBroker.currentGeneration() != round
        // 旧请求可能正为另一张网络展示：等它收尾后重新提问，而不是把不匹配的答案
        // 当成用户拒绝（那会让一次本可完成的交接直接失败）。
        repeat(CarWifiJoiner.CONFIRM_CURRENT_MAX_ATTEMPTS) {
            if (expired()) return null
            when (val claim = wifiPromptBroker.claim(spec, shownNetwork)) {
                is WifiPromptBroker.Claim.Answered -> return claim.answer

                is WifiPromptBroker.Claim.Follower -> {
                    val answer = wifiPromptBroker.await(claim.future, JOIN_DIALOG_TIMEOUT_S * 1_000, ::expired)
                    // null 不代表本请求过期：用户可能只是关掉了为**另一张网络**展示的框。
                    // 只有本请求真的被取消/跨轮才放弃；否则为当前网络重新提问。
                    if (claim.matches) return answer
                    if (expired()) return null
                    return@repeat
                }

                is WifiPromptBroker.Claim.Owner -> {
                    // 本次询问的标识：提前退出时按它撤掉对应的那个弹窗。
                    val requestId = dialogRequestIds.incrementAndGet()
                    val request = spec.copy(requestId = requestId)
                    val answered =
                        java.util.concurrent.atomic
                            .AtomicBoolean(false)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val handled =
                            ui.ask(request) { result ->
                                answered.set(true)
                                wifiPromptBroker.finish(claim, result, cache = true)
                            }
                        if (!handled) {
                            // 无 UI 宿主（Activity 销毁窗口）：宁可中止也不默认替用户决定加入
                            wifiPromptBroker.finish(claim, null, cache = false)
                            return@post
                        }
                    }
                    try {
                        val answer = wifiPromptBroker.await(claim.future, JOIN_DIALOG_TIMEOUT_S * 1_000, ::expired)
                        if (!claim.future.isDone) wifiPromptBroker.timeout(claim)
                        return answer
                    } finally {
                        // 提前退出（取消/跨轮/超时）时撤掉仍显示的弹窗；已应答时是无害 no-op
                        // （UI 侧按 requestId 只关对应那一个）。
                        if (!answered.get()) runCatching { ui.dismiss(requestId) }
                    }
                }
            }
        }
        return null
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** 热点密码复制到剪贴板（手动到 WLAN 页连接时粘贴），并提示用户。 */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    ui.hint { t(R.string.sc_psk_copied, passphrase.length) }
                }
            }
        }
    }

    /** 当前 Wi-Fi SSID：优先系统 API（有定位权限时），否则用 Shizuku 的 shell 状态。 */
    private fun liveWifiSsid(): String? = carWifiJoiner.liveWifiSsid()

    /** 日志只记录可读性，不输出可能包含引号而绕过 redactor 的 SSID 原文。 */
    private fun wifiForLog(
        ssid: String?,
        unknown: String = "SSID 不可读",
    ): String = carWifiJoiner.wifiForLog(ssid, unknown)

    private fun liveWifiMatches(ssid: String): Boolean? = carWifiJoiner.liveWifiMatches(ssid)

    /**
     * 目标 SSID 对应的、可安全绑定的 `Network` 句柄。
     *
     * 有定位权限时优先用 [WifiCarPlayConnector.verifiedWifiNetwork]（同一回调里核验句柄与 SSID）。
     * Android 13+ 本应用只申请 `NEARBY_WIFI_DEVICES`，没有定位权限时该方法恒为 null——
     * 此时若 Shizuku 读到的站端 SSID 与目标一致、且系统只有这一张 Wi-Fi，
     * 唯一性已经成立，不必再弹「使用当前 Wi-Fi？」。句柄与 SSID 是两次系统查询，
     * 必须连续两次采样都成立且句柄相同才采信（见 [VerifiedWifiSelection.confirmed]），
     * 否则手机恰在两次查询之间换网就会把 A 的句柄贴成 B。两张及以上并存或采样不一致时
     * 仍返回 null，交给用户确认，绝不猜网络。
     */
    private fun verifiedWifiNetworkFor(ssid: String): Network? {
        val connector = wifiConnector ?: return null
        connector.verifiedWifiNetwork(ssid)?.let { return it }

        fun sample() =
            VerifiedWifiSelection.Sample(
                network = connector.singleWifiNetwork(),
                ssid = liveWifiSsid(),
            )
        return VerifiedWifiSelection.confirmed(ssid, sample(), sample())
    }

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    private fun shellQuote(s: String): String = carWifiJoiner.shellQuote(s)

    /**
     * 断开当前任一路径的 iAP2 控制会话。
     *
     * [eaStopReason] 非空 = 本轮控制会话到此结束，先发 0xEA01 收掉 EA 会话再关；
     * null = 复现「BT 控制已结束」状态（DiPlay 只在此状态应答 RTSP），保持原有立即关闭。
     */
    private fun detachActiveBtSessions(eaStopReason: String?) {
        bluetoothServer?.detachCurrentSession(eaStopReason)
        bluetoothClient?.detachCurrentSession(eaStopReason)
    }

    private class RetiringAirPlayClient(
        private val client: AirPlayClient,
    ) : RetiringClient {
        override val sessionActive: Boolean get() = client.sessionActive

        override fun stopLikeIphone(): Boolean = client.stopLikeIphone()

        override fun teardownSession() {
            client.teardownSession()
        }

        override fun close() {
            client.close()
        }
    }

    /**
     * 等车机 ctrl 邀请（`GET /ctrl-int/1/connect`）——catplay 的连接触发点。
     *
     * 邀请意味着「车机已在 WiFi 上发现我们，且已把 AirPlay 端口打开」，此时连
     * AirPlay 才不会被 Connection refused。无邀请时可在发现 `_airplay._tcp` 后走直连；
     * 通用路径会先给公告及重公告留出宽限期。
     *
     * @return true = 收到邀请 / 已发现端点；false = 等待窗口耗尽
     */
    private fun awaitCtrlInvite(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        timeoutMillis: Long,
    ): Boolean = endpointResolver.awaitCtrlInvite(endpoint, timeoutMillis)

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    private fun createAudioChannelStarter() =
        AudioChannelStarter(
            session =
                object : AudioSessionPort<AirPlayClient, Context, AudioSender, CarLifeAudioBridge> {
                    override val currentClient get() = this@SessionController.currentClient
                    override val context get() = appContext
                    override val secret get() = currentSharedSecret
                    override val host get() = currentAudioHost
                    override val enabled get() = audioEnabledForSession
                    override val preferCompat get() = audioPreferCompat
                    override var sender: AudioSender?
                        get() = audioSender
                        set(value) {
                            audioSender = value
                        }
                    override var bridge: CarLifeAudioBridge?
                        get() = nativeAudioBridge
                        set(value) {
                            nativeAudioBridge = value
                        }

                    override fun baselineAllowed() = audioBaselineAllowed()

                    override fun <T> withControllerLock(block: () -> T): T = synchronized(this@SessionController) { block() }
                },
            clients =
                object : AudioClientPort<AirPlayClient> {
                    override fun generation(client: AirPlayClient) = client.connectionGeneration

                    override fun isConnectionCurrent(
                        client: AirPlayClient,
                        generation: Long,
                    ) = client.isConnectionCurrent(generation)

                    override fun isStopping(client: AirPlayClient) = client.sessionStopping

                    override fun receiverIdentity(client: AirPlayClient) = client.receiverIdentity()

                    override fun candidates(
                        client: AirPlayClient,
                        preferCompat: Boolean,
                        allowBaseline: Boolean,
                    ) = client.audioCandidates(preferCompat = preferCompat, allowBaseline = allowBaseline)

                    override fun claim(
                        client: AirPlayClient,
                        generation: Long,
                    ): AudioClaim {
                        val claim = client.claimMainAudioForStreaming(generation)
                        return AudioClaim(claim.note, claim.claimed, claim.announced)
                    }

                    override fun phoneOwnsAudio(client: AirPlayClient) = client.modes.phoneOwnsAudio

                    override fun setup(
                        client: AirPlayClient,
                        secret: ByteArray,
                        profile: AudioProfile,
                        tuning: AudioTuning,
                        expectedEpoch: Long,
                    ): AudioSetup? =
                        client.runAudioStream(secret, profile, tuning, expectedEpoch = expectedEpoch)?.let {
                            AudioSetup(it.streamConnectionId, it.dataPort, it.controlPort, it.streamKey, it.sampleRate)
                        }

                    override fun record(client: AirPlayClient) = client.record()
                },
            source =
                object : AudioSourcePort<Context, android.media.projection.MediaProjection, AudioSender, CarLifeAudioBridge> {
                    override fun projection() = CarPlayService.mediaProjection

                    override fun nativeAvailable(context: Context) = CarLifeLauncher.isAvailable(context)

                    override fun applicationContext(context: Context): Context = context.applicationContext

                    override fun isRunning(sender: AudioSender) = sender.isRunning

                    override fun createSender(
                        spec: AudioSenderSpec,
                        context: Context,
                        log: (String) -> Unit,
                        onPacketSent: () -> Unit,
                    ): AudioSender =
                        AudioSender(
                            host = spec.host,
                            dataPort = spec.dataPort,
                            streamKey = spec.streamKey,
                            log = log,
                            context = context,
                            sampleRate = spec.sampleRate,
                            codec = spec.codec,
                            streamType = spec.streamType,
                            adtsFrames = spec.adtsFrames,
                            controlPort = spec.controlPort,
                            onPacketSent = onPacketSent,
                        )

                    override fun createBridge(
                        sender: AudioSender,
                        sampleRate: Int,
                        context: Context,
                        log: (String) -> Unit,
                    ): CarLifeAudioBridge = CarLifeAudioBridge(sender, sampleRate, AudioDuckingPrefs.config(context), log)

                    override fun startCapture(
                        sender: AudioSender,
                        projection: android.media.projection.MediaProjection,
                    ) = sender.start(projection)

                    override fun startExternal(sender: AudioSender) = sender.startExternalSource()

                    override fun startBridge(bridge: CarLifeAudioBridge) = bridge.start()

                    override fun stopSender(sender: AudioSender) = sender.stop()

                    override fun stopBridge(bridge: CarLifeAudioBridge) = bridge.stop()
                },
            memory =
                object : AudioStartMemory<Context> {
                    override fun load(
                        context: Context,
                        identity: String,
                    ) = AudioProfileMemory.load(context, identity)

                    override fun reset(
                        context: Context,
                        identity: String,
                    ) = AudioProfileMemory.reset(context, identity)

                    override fun recordRejected(
                        context: Context,
                        identity: String,
                        key: String,
                        allCandidates: Set<String>,
                        markExhausted: Boolean,
                    ) = AudioProfileMemory.recordRejected(context, identity, key, allCandidates, markExhausted)
                },
            detection =
                object : AudioDetectionPort<AirPlayClient> {
                    override fun collecting(client: AirPlayClient) = ReceiverDetectionRuntime.collecting(client.currentEndpoint.observation)

                    override fun preferredAudio(client: AirPlayClient) =
                        ReceiverDetectionRuntime.preferredAudio(client.currentEndpoint.observation, client.capabilityDigest)

                    override fun audioSelected(
                        client: AirPlayClient,
                        key: String,
                    ) = ReceiverDetectionRuntime.audioSelected(client.currentEndpoint.observation, key)
                },
            diagnostics =
                object : AudioStartDiagnostics {
                    override fun currentAttemptId() = ConnectionDiagnostics.timeline.snapshot()?.id

                    override fun note(
                        attempt: String,
                        line: String,
                    ) = ConnectionDiagnostics.timeline.note(attempt, line)

                    override fun audioPacket(attempt: String) = ConnectionDiagnostics.timeline.audioPacket(attempt)
                },
            active = audioChannelState,
            nowMs = { SystemClock.uptimeMillis() },
            log = ::appendLog,
        )

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：SETUP type=100 协商 → 播放捕获 → ChaCha 封包推流。
     *
     * 依赖 MediaProjection（AudioPlaybackCapture 的授权来源），且需用户在「启动连接」
     * 弹窗中选择「开启声音」——音视频同时推流显著增加负载（实测会卡顿/断连）。
     *
     * @return 音频通道是否真正启动（调用方据此决定是否补发 RECORD 激活新加入的流）
     */
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean = audioChannelStarter.startAudioChannel(context, client, sharedSecret, host)

    /**
     * 会话结束时的**音频拆链归因**（2026-10-19 Honda HFT / Alpine 实测新增）。
     *
     * 现象：选了「视频+音频」后会话能建立、视频也正常，但音频起流约 6s 后车机把整条
     * AirPlay 会话拆掉（`EVENT: 读取结束 - Connection reset`），只推视频则稳定。
     * 车机侧不会告诉我们「哪一档音频不兼容」，只能用存活时间反推：
     *  - 音频起流后**没跑满** [AUDIO_PROFILE_PROBE_MS] 就整链被拆 → 判该档位不兼容，
     *    记入本车机名单（全部档位都被判死则置 disabled，下次直接只推视频）；
     *  - 跑满了 → 记为该档位可用，下次连接直接首选。
     *
     * 注意只在「确实起过音频」时归因；纯视频会话结束不做任何记忆改写。
     */
    private fun attributeAudioTeardown() {
        audioTeardown.attribute()
    }

    private fun clearActiveAudioState() {
        audioChannelState.clear()
    }

    /** 界面提示文案（阶段提示、Snackbar、确认弹窗；随界面语言）。日志仍写中文。 */

    /**
     * 当前网络的快照：Network 句柄 + 可读 SSID。弹窗答案只能在**同一张网络**上复用
     * （见 [WifiPromptBroker]）；两者都读不到时返回 null——此时不合并、不缓存答案。
     */
    private fun currentNetworkToken(): WifiNetworkToken<Network>? =
        WifiNetworkToken(wifiConnector?.currentWifiNetwork(), liveWifiSsid()).takeIf { it.hasIdentity }

    private fun t(
        @StringRes id: Int,
        vararg args: Any,
    ): String = appContext?.let { AppLanguage.localized(it).getString(id, *args) }.orEmpty()

    private fun audioBaselineAllowed(): Boolean = appContext?.let(CompatibilityPrefs::audioBaseline) == true

    /** 该车机上「所有可能用到的档位键」——用于判断是否已全部试穿。 */
    private fun audioCandidateKeys(identity: String): Set<String> = audioChannelStarter.audioCandidateKeys(identity)

    /**
     * modesChanged 资源重申调度（MIB3 控制会话完成事件驱动）。
     *
     * AADisplay r17 在奥迪上长播验证的时序：auth+burst 之后 **+1.5s 首发、+4s/+12s/
     * +30s 各重申一次**（绝对时刻）。声明若落在 0xAA00/0xAA05 进行中，会与 iAP POST
     * 抢控制通道、奥迪 ~12s 后死（AADisplay 注释原话）——所以只能由 burst 完成事件
     * 触发，不能在会话建立时一次性发完。082446 定谳：第二控制会话会重置车机的资源
     * 仲裁，早先那份 200 OK 的声明到订阅簇完成时已经失效，车机在等一份新的。
     */
    @Volatile
    private var modesReclaimEpoch = 0L

    private fun scheduleModesReclaim(client: AirPlayClient) {
        val epoch = ++modesReclaimEpoch
        val connectionGeneration = client.connectionGeneration
        Bg.run("modes-reclaim") {
            val start = android.os.SystemClock.uptimeMillis()
            for (atMs in longArrayOf(1_500, 4_000, 12_000, 30_000)) {
                while (true) {
                    if (epoch != modesReclaimEpoch ||
                        client.cancelled ||
                        connectCancelled ||
                        !client.isConnectionCurrent(connectionGeneration)
                    ) {
                        return@run
                    }
                    val remain = atMs - (android.os.SystemClock.uptimeMillis() - start)
                    if (remain <= 0) break
                    Thread.sleep(minOf(remain, 200L))
                }
                if (epoch != modesReclaimEpoch ||
                    client.cancelled ||
                    connectCancelled ||
                    !client.isConnectionCurrent(connectionGeneration)
                ) {
                    return@run
                }
                if (!client.assertModes(expectedEpoch = connectionGeneration)) return@run
                appendLog("AIRPLAY: modesChanged 资源重申（burst 后 +${atMs / 1000}s，AADisplay r17 时序）")
            }
        }
    }

    private fun discoveredEndpoint(info: android.net.nsd.NsdServiceInfo): DiscoveredEndpoint =
        object : DiscoveredEndpoint {
            override val hostAddress: String?
                get() = info.host?.hostAddress
            override val port: Int
                get() = info.port

            override fun txtBytes(key: String): ByteArray? = info.attributes[key]
        }

    private val endpointResolver =
        EndpointResolver(
            uptimeMillis = { android.os.SystemClock.uptimeMillis() },
            sleepMillis = { millis -> Thread.sleep(millis) },
            isConnectCancelled = { connectCancelled },
            captureInviteView = {
                val advertiser = ctrlAdvertiser
                advertiser?.let { captured ->
                    object : InviteView {
                        override val inviteCount: Int
                            get() = captured.inviteCount
                        override val lastInviteAtMillis: Long
                            get() = captured.lastInviteAtMillis
                        override val advertisedAtMillis: Long
                            get() = captured.advertisedAtMillis
                    }
                }
            },
            awaitEndpoint = { timeoutMs ->
                endpointBrowser?.awaitEndpoint(timeoutMs)?.let(::discoveredEndpoint)
            },
            forceAnnounce = { ssid ->
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            },
            gatewayCandidates = {
                wifiConnector?.gatewayCandidates().orEmpty()
            },
            directDiscoveryPolicy = { endpoint, discovered ->
                ReceiverCompatibility.prefersOra03DirectDiscovery(
                    endpoint.receiverSignals,
                    txtValue(discovered, "model"),
                    txtValue(discovered, "fv"),
                    txtValue(discovered, "srcvers"),
                    VehiclePresetRuntime.current,
                )
            },
            log = ::appendLog,
            resolveWaitMillis = ENDPOINT_RESOLVE_WAIT_MS,
        )

    /**
     * 端点补全：优先吸收 mDNS 解析出的地址，再决定是否等待/兜底。
     *
     * 关键（2026-10-19 奥迪 4851 实车日志）：0x4301 只给了
     * `fe80::3ca8:64ff:fe7c:293e`（IPv6 链路本地），而 `_airplay._tcp` 解析出的是
     * `10.174.189.1:7000`——车机实际用 IPv4 通的。**不能因为 0x4301 带了 IPv6 就不看
     * mDNS**：那样会把唯一验证过的 IPv4 路径丢掉。这里先做一次「零等待」缓存合并，
     * 拿到 IPv4 就按 EndpointHosts 的优先级（IPv4 → IPv6）使用；缓存没命中才按需等。
     */
    @Suppress("ktlint:standard:function-expression-body")
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        return endpointResolver.resolveEndpointHost(
            endpoint = endpoint,
            source = source,
            onResolutionNeeded = { ui.phase(t(R.string.sc_mdns)) },
        )
    }

    /**
     * Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时的热更新入口。
     *
     * 三种归属：
     *  - 正在推流：冗余端点，忽略（与 [onCarPlayEndpoint] 同一防护，避免拆掉健康会话）；
     *  - 交接/配对进行中：热更新当前 AirPlayClient 的端点，配对循环下一轮即用新地址；
     *    若 client 尚未创建（还在绑网/等邀请）先暂存，配对线程起步时并入；
     *  - 上一轮已失败（无会话在跑）：按新端点重走一轮完整交接——Audi MMI 9310
     *    实测 0x4301 在 120s 配对窗口耗尽后 28s 才到，旧实现把它当日志垃圾丢掉，
     *    车机等 15s 无人连入即拆链，表现为「连上后自动断开」。
     */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (!ReceiverDetectionRuntime.isCurrent(endpoint.observation)) return
        if (connectCancelled) {
            appendLog(">>> [$source] 0x4301 晚到（已取消连接）——忽略")
            return
        }
        // 晚到端点即使只供当前 client 热更新，也要把完整配置留给下一轮仅 SSID 重连。
        rememberEndpointWifi(endpoint)
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 0x4301 晚到（推流中）——忽略冗余端点")
            return
        }
        var pairingClient: AirPlayClient? = null
        if (pendingEndpointUpdate.routeIfActive(
                endpoint,
                merge = { previous, latest ->
                    if (previous.observation === latest.observation) preferEndpoint(latest, previous) else latest
                },
            ) {
                pairingClient =
                    currentClient?.takeIf { client ->
                        // 直接热更新只允许同一 RFCOMM observation：A 的配对进行中收到
                        // B 的 0x4301 时必须留在 B 自己的暂存槽位，不能写进 A 的客户端。
                        client.pairingInProgress && pendingEndpointUpdate.sameSlot(client.currentEndpoint, endpoint)
                    }
                pairingClient != null
            }
        ) {
            val client = pairingClient
            if (client != null) {
                // 合并而非替换：0x4301 可能不带 IPv4（Audi MMI 9310 实测端点解析依赖
                // mDNS/网关），直接替换会把已解析到的地址抹掉，配对循环反而连不上。
                val merged = preferEndpoint(endpoint, client.currentEndpoint)
                val changed = client.updateEndpoint(merged, "0x4301 晚到")
                appendLog(
                    if (changed) {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点已热更新，下一轮重试用新地址"
                    } else {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点无变化，忽略"
                    },
                )
            } else {
                appendLog(">>> [$source] 0x4301 晚到（交接尚未创建配对客户端）——暂存，配对起步时采用")
            }
            return
        }
        // 用户已断开（BT 组件已停）时不得用晚到消息复活会话
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
            appendLog(">>> [$source] 0x4301 晚到（蓝牙链路已退役）——忽略")
            return
        }
        appendLog(">>> [$source] 0x4301 晚到（上一轮已结束）——按新端点重走一轮连接")
        onCarPlayEndpoint(endpoint, "$source/0x4301 晚到")
    }

    /** 把交接期间暂存的晚到端点并入新建的客户端（建 client 与收到 0x4301 的竞态窗口）。 */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val observation = client.currentEndpoint.observation
        // 只取同一 observation：另一条 RFCOMM 链路先到的端点属于它自己的交接。
        val late = pendingEndpointUpdate.takeIf { it.observation === observation } ?: return
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "交接期间晚到 0x4301")) {
            appendLog("AIRPLAY: 交界窗口的 0x4301 端点已并入配对客户端")
        }
    }

    /**
     * 释放交接所有权；释放成功时唤醒其他仍在等待的 RFCOMM observation。
     */
    private fun releaseHandoffOwnership(observation: ReceiverObservation?) {
        if (handoffOwnership.endIfOwnedBy(observation)) wakePendingHandoffs(observation)
    }

    /** observation 既属于当前轮次，其 RFCOMM 链路也仍然存活。 */
    private fun isActiveObservation(observation: ReceiverObservation?): Boolean =
        ReceiverDetectionRuntime.isCurrent(observation) && activeReceiverObservations.isActive(observation)

    /**
     * 交接所有权释放后唤醒仍暂存的晚到端点。
     *
     * 只补投刚结束的 observation 会让另一条链路（B）先到的 0x4301 永远无人消费：
     * 它在本轮交接期间被 [PendingHandoffUpdate.routeIfActive] 暂存，却等不到自己的交接。
     * 一次只唤醒一条，避免后续项因交接互斥而丢失；优先刚释放 observation 自己的
     * late endpoint，防止 B 抢在 A 的补投前面。
     */
    private fun wakePendingHandoffs(
        released: ReceiverObservation?,
        allowAfterCancel: Boolean = false,
    ) {
        if (connectCancelled && !allowAfterCancel) return
        Bg.run("late-endpoint-wake") {
            val wake =
                PendingEndpointWake(
                    pending = pendingEndpointUpdate,
                    isActive = { !isDisconnecting() && isActiveObservation(it.observation) },
                    isBusy = { sessionStarting.get() || isDisconnecting() },
                    deliver = { pending ->
                        onCarPlayEndpoint(pending, "0x4301 晚到（交接释放后唤醒）")
                        handoffOwnership.owns(pending.observation) || pendingStartSessionEndpoint.get() === pending
                    },
                )
            if (wake.wake(prefer = { it.observation === released })) {
                appendLog(">>> 交接释放后唤醒暂存的 0x4301 端点")
            }
        }
    }

    /**
     * 空端点等待期（车机声明会发 0x4301 但迟迟未到）的观察任务：
     * 1. 每 5s 轮询当前 WiFi——用户手动连上车机热点后弹窗确认，确认即以该 SSID
     *    合成端点重走 [onCarPlayEndpoint]（无端口 → mDNS/网关兜底）；
     * 2. 本轮空端点被真实/合成端点接手、被同链路新空端点替换、或所属链路断开
     *    （[pendingStartSessionEndpoint] 不再是 [endpoint]）时让位退出；ctrl 邀请触发的
     *    临时交接只会暂停观察——绑定失败释放后继续等，而不是永久退出；
     * 3. 超过 [PENDING_START_SESSION_WAIT_MS] 无进展：记日志并拆掉保活链路，
     *    让 BT 重连循环接管（与旧「8s 拆链」的区别只在窗口长度）。
     */
    private fun watchForPendingStartSession(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        Bg.run("pending-start-session-watch") {
            var promptedSsid: String? = null
            var triedInvites = 0
            var tick = 0
            val deadline = android.os.SystemClock.uptimeMillis() + PENDING_START_SESSION_WAIT_MS

            fun stillPending(): Boolean = pendingStartSessionEndpoint.get() === endpoint
            while (!connectCancelled && android.os.SystemClock.uptimeMillis() < deadline) {
                // 晚到 0x4301 / 合成端点已接手本轮（或链路已断）——观察任务让位。
                if (!stillPending()) return@run
                // 交接临时占用（如 ctrl 邀请正在绑定当前网络）：暂停观察，失败释放后继续。
                if (sessionStarting.get()) {
                    Thread.sleep(1_000)
                    continue
                }
                // 车机 ctrl 邀请 = 手机已在车机网络上（大众 2026-10-31：首次配对前不回 0x5703、不发 0x4301，
                // 只发邀请）。无需 Shizuku 读 SSID、也无需询问用户：绑定当前 WiFi 后直接接邀请。
                // 每来一次新邀请最多试一次绑定，失败后继续等下一次。
                val invites = ctrlAdvertiser?.inviteCount ?: 0
                if (invites > triedInvites) {
                    triedInvites = invites
                    if (continueOnInvitedWifi(endpoint, source)) return@run
                }
                Thread.sleep(1_000)
                if (connectCancelled) return@run
                if (++tick % 5 != 0) continue
                val ssid = liveWifiSsid()?.takeIf { it.isNotBlank() }
                val network = wifiConnector?.currentWifiNetwork() ?: continue
                if (ssid.isNullOrEmpty() && !hasRecentUnverifiedInvite()) continue
                val label = ssid?.takeIf { it.isNotEmpty() } ?: INVITED_WIFI_LABEL
                // 同一 SSID 只问一次（用户选「继续等待」后不再打扰）；换网络再问。
                val promptKey = ssid ?: network.toString()
                if (promptedSsid == promptKey) continue
                promptedSsid = promptKey
                val choice =
                    askWifiJoinChoice(
                        DialogSpec(
                            title =
                                if (ssid == null) t(R.string.sc_use_current_title) else t(R.string.sc_detected_title, ssid),
                            message =
                                if (ssid == null) {
                                    t(R.string.sc_use_current_body, t(R.string.sc_current_network))
                                } else {
                                    t(R.string.sc_detected_body, ssid)
                                },
                            positive = if (ssid == null) t(R.string.sc_use_current) else t(R.string.sc_continue_as_hotspot),
                            negative = t(R.string.sc_keep_waiting),
                        ),
                        // 同一张网络只问一次；答案也只能在这张网络上复用。
                        shownNetwork = WifiNetworkToken(network, ssid),
                    )
                if (choice == true && !connectCancelled && !sessionStarting.get() && stillPending()) {
                    if (wifiConnector?.currentWifiNetwork() != network) {
                        promptedSsid = null
                        appendLog(">>> [$source] 用户确认期间当前网络已变化——重新等待确认")
                        continue
                    }
                    appendLog(">>> [$source] 用户确认当前网络为车机热点——以合成端点重走连接")
                    onCarPlayEndpoint(
                        endpoint.copy(
                            ssid = label,
                            wifiCredentialSource = WifiCredentialSource.CURRENT_NETWORK,
                        ),
                        "$source/手动入网",
                        verifiedCurrentNetwork = network,
                    )
                    return@run
                }
            }
            if (!connectCancelled &&
                !sessionStarting.get() &&
                pendingStartSessionEndpoint.compareAndSet(endpoint, null)
            ) {
                appendLog(
                    ">>> [$source] 空端点等待 ${PENDING_START_SESSION_WAIT_MS / 60_000} 分钟超时——" +
                        "车机未下发 0x4301。拆掉保活链路让重连循环接管，请导出日志反馈",
                )
                detachActiveBtSessions(eaStopReason = "空端点等待超时")
            }
        }
    }

    /**
     * 空端点 + 车机已发 ctrl 邀请：按手机当前 WiFi 绑定并以合成端点重走连接。
     *
     * 邀请只可能经手机与车机共享的网络到达，所以「当前 WiFi」就是车机网络；
     * SSID 读不到（没有 Shizuku/定位权限）时用 [INVITED_WIFI_LABEL] 占位——
     * 它只作为绑定键与日志标签，不会写进 [lastCarWifiSsid]，也不会被拿去按 SSID 入网。
     */
    private fun continueOnInvitedWifi(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Boolean {
        // 只接手仍在等待的那个空端点：它被接手、替换或所属链路断开后，邀请不能再以它的身份交接。
        fun stillPending(): Boolean = pendingStartSessionEndpoint.get() === endpoint
        if (connectCancelled ||
            sessionStarting.get() ||
            !stillPending() ||
            !receiverHasInvited(endpoint.observation) ||
            !ReceiverDetectionRuntime.isCurrent(endpoint.observation) ||
            !inviteHandoffInProgress.compareAndSet(false, true)
        ) {
            return false
        }
        try {
            if (!handoffOwnership.begin(endpoint.observation) { connectCancelled = false }) {
                return false
            }
            var transferred = false
            try {
                val liveSsid = liveWifiSsid()?.takeIf { it.isNotEmpty() }
                val label = liveSsid ?: INVITED_WIFI_LABEL
                appendLog(
                    ">>> [$source] 车机已在 WiFi 上发 ctrl 邀请（无 0x5703/0x4301）——按手机当前 " +
                        wifiForLog(liveSsid) + " 绑定后继续",
                )
                val inviteNetwork = verifiedInviteNetwork(endpoint.observation)
                if (inviteNetwork == null ||
                    !bindCarWifiAndRegister(
                        label,
                        inviteNetwork,
                        isCancelled = {
                            connectCancelled ||
                                !ReceiverDetectionRuntime.isCurrent(endpoint.observation) ||
                                !handoffOwnership.owns(endpoint.observation)
                        },
                    )
                ) {
                    val late = completePendingOrRelease(endpoint) { releaseHandoffOwnership(endpoint.observation) }
                    if (late != null) {
                        appendLog(">>> [$source] 当前网络绑定失败，但已收到完整 0x4301——改用其凭据继续")
                        onCarPlayEndpoint(late, "$source/ctrl邀请晚到0x4301", ownershipPreAcquired = true)
                        transferred = true
                        return true
                    }
                    // completePendingOrRelease 已释放；不能让 finally 释放同观察的新交接。
                    transferred = true
                    appendLog(">>> [$source] 未能绑定当前 WiFi——继续等待下一次车机邀请或晚到的 0x4301")
                    return false
                }
                if (connectCancelled || !stillPending() || !handoffOwnership.owns(endpoint.observation)) return false
                val late = completePendingFor(endpoint)
                val handoffEndpoint =
                    late ?: endpoint.copy(
                        ssid = label,
                        wifiCredentialSource = WifiCredentialSource.CURRENT_NETWORK,
                    )
                onCarPlayEndpoint(
                    handoffEndpoint,
                    "$source/ctrl邀请",
                    ownershipPreAcquired = true,
                    verifiedCurrentNetwork = if (late == null) inviteNetwork else null,
                    requireCurrentBinding = late == null,
                )
                transferred = true
                return true
            } finally {
                if (!transferred) releaseHandoffOwnership(endpoint.observation)
            }
        } finally {
            inviteHandoffInProgress.set(false)
        }
    }

    /** 取走同一轮、同一 RFCOMM 观察上的完整 0x4301；等待绑定期间它不能被新一轮清掉。 */
    private fun completePendingFor(endpoint: Iap2SenderSession.CarPlayEndpoint): Iap2SenderSession.CarPlayEndpoint? =
        pendingEndpointUpdate.takeIf {
            it.observation === endpoint.observation && it.hasCompleteWifiCredentials
        }

    /** 无完整端点时在同一锁内释放；新端点不能恰好落在检查与释放之间。 */
    private fun completePendingOrRelease(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        release: () -> Unit,
    ): Iap2SenderSession.CarPlayEndpoint? =
        pendingEndpointUpdate.takeIfOrRelease(
            matches = {
                !connectCancelled &&
                    ReceiverDetectionRuntime.isCurrent(endpoint.observation) &&
                    it.observation === endpoint.observation &&
                    it.hasCompleteWifiCredentials
            },
            release = release,
        )

    /**
     * 资源声明（modesChanged）的串行执行器：事件读取线程只负责接收、应答与投递。
     * assertModes 要等控制通道的 RTSP 回应，放在事件线程上会挡住同一连接后续的
     * 触摸/iAP 消息（2026-11-01 审查）。密集请求合并：已有一条待执行时不再排队。
     */
    private val controlActions =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "carplay-control-actions").apply { isDaemon = true }
        }

    private data class ModesAction(
        val client: AirPlayClient,
        val reason: String,
        var requestKeyFrame: Boolean,
        var retakeScreen: Boolean,
        var rebuildStream: Boolean,
    )

    private val modesActionLock = Any()
    private var pendingModesAction: ModesAction? = null

    /**
     * 拿回主屏 3 s 后记一笔车机反应：有触控 = 已切到 CarPlay；无触控也无要帧 = 车机没理这次归属变化。
     * 帕萨特回原车主界面后再点图标属于后者（2026-11-01 23:22），需要据此区分是仲裁还是解码问题。
     */
    private fun scheduleTakeBackCheck(
        client: AirPlayClient,
        reason: String,
        alreadyRetried: Boolean,
    ) {
        val touchesBefore = touchLogCount
        val keyFramesBefore = forceKeyFrameCount
        Bg.run("takeback-check") {
            try {
                Thread.sleep(TAKE_BACK_CHECK_MS)
            } catch (_: InterruptedException) {
                return@run
            }
            if (currentClient !== client) return@run
            appendLog(
                "SESSION: 拿回主屏 ${TAKE_BACK_CHECK_MS}ms 后：车机触控 +${touchLogCount - touchesBefore} 条、" +
                    "要关键帧 +${forceKeyFrameCount - keyFramesBefore} 次（${client.modes.describe()}；起因 $reason）",
            )
            applyScreenReturnStrategy(
                client,
                ScreenReturnPolicy.takeBackFailed(touchLogCount - touchesBefore, forceKeyFrameCount - keyFramesBefore),
                alreadyRetried,
            )
        }
    }

    /**
     * 回屏策略在当前时点的有效值：只有主页「车型适配」手动选了大众，兼容层才在这里读取用户偏好；
     * 其他车型选择或自动判定一律 OFF。`client.receiverCompatibility()` 只作为中性 Policy 载体，
     * 不用它的 family/rules/邀请重连判资格；资格与惰性读取都在
     * [ReceiverCompatibility.withScreenReturnPreference] 内完成。
     */
    private fun currentScreenReturnPolicy(client: AirPlayClient): ReceiverCompatibility.Policy =
        ReceiverCompatibility.withScreenReturnPreference(
            client.receiverCompatibility(),
            VehiclePresetRuntime.current,
        ) { appContext?.let(CompatibilityPrefs::screenReturnStrategy) ?: ScreenReturnStrategy.OFF }

    /** 连续「拿回主屏车机没反应」次数；车机有反应或新一轮连接时清零。 */
    @Volatile
    private var takeBackFailures = 0

    /** 回屏策略的失败后续动作；用户未选择策略（OFF）时什么都不做。 */
    private fun applyScreenReturnStrategy(
        client: AirPlayClient,
        failed: Boolean,
        alreadyRetried: Boolean,
    ) {
        val strategy = currentScreenReturnPolicy(client).screenReturn
        if (strategy == ScreenReturnStrategy.OFF) return
        if (!failed) {
            takeBackFailures = 0
            return
        }
        val failures = ++takeBackFailures
        if (ScreenReturnPolicy.retryRebuild(strategy, failed, alreadyRetried)) {
            appendLog("PASSAT: 策略「失败后重建一次」——拿回主屏车机没反应，重建主屏流重试")
            enqueueModesAssert(
                client,
                "帕萨特策略：拿回主屏无反应，重建重试",
                requestKeyFrame = true,
                retakeScreen = true,
                rebuildStream = true,
                takeBackRetried = true,
            )
        } else if (ScreenReturnPolicy.disconnect(strategy, failures)) {
            appendLog("PASSAT: 策略「连续失败后断开」——连续 $failures 次拿回主屏车机没反应，结束本轮，请重新连接")
            synchronized(this) {
                if (!connectCancelled && !client.cancelled && currentClient === client) {
                    appContext?.let { disconnect(it, DiagnosticCode.VIDEO_CLOSED) }
                }
            }
        } else {
            appendLog("PASSAT: 策略 ${strategy.key}：拿回主屏车机没反应（连续 $failures 次）")
        }
    }

    private fun enqueueModesAssert(
        client: AirPlayClient,
        reason: String,
        requestKeyFrame: Boolean,
        retakeScreen: Boolean = false,
        rebuildStream: Boolean = false,
        takeBackRetried: Boolean = false,
    ) {
        val connectionGeneration = client.connectionGeneration
        val action =
            synchronized(modesActionLock) {
                val pending = pendingModesAction
                if (pending?.client === client) {
                    pending.requestKeyFrame = pending.requestKeyFrame || requestKeyFrame
                    pending.retakeScreen = pending.retakeScreen || retakeScreen
                    pending.rebuildStream = pending.rebuildStream || rebuildStream
                    appendLog("EVENT: $reason → 与待执行的 modesChanged 合并")
                    return
                }
                ModesAction(client, reason, requestKeyFrame, retakeScreen, rebuildStream).also { pendingModesAction = it }
            }
        controlActions.execute {
            val request =
                synchronized(modesActionLock) {
                    if (pendingModesAction === action) pendingModesAction = null
                    action.copy()
                }
            // 执行时会话可能已取消或换了 client：迟到任务不得对新会话发 modesChanged
            if (connectCancelled || currentClient !== client || !client.isConnectionCurrent(connectionGeneration)) {
                appendLog("EVENT: $reason → 会话已结束或更替，丢弃迟到的 modesChanged")
                return@execute
            }
            // 用户点图标时主屏本就归手机：车机停在原车界面却不认这个归属，先报让出、再报拿回
            if (request.retakeScreen && client.modes.phoneOwnsScreen) {
                if (client.assertModes(releaseScreen = true, expectedEpoch = connectionGeneration)) {
                    try {
                        Thread.sleep(SCREEN_RETAKE_GAP_MS)
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                }
            }
            if (connectCancelled || currentClient !== client || !client.isConnectionCurrent(connectionGeneration)) return@execute
            val asserted = client.assertModes(expectedEpoch = connectionGeneration)
            if (connectCancelled || currentClient !== client || !client.isConnectionCurrent(connectionGeneration)) return@execute
            val restoreScreen = request.requestKeyFrame && asserted && client.modes.phoneOwnsScreen
            val recovery = screenStreamRecovery
            if (restoreScreen && request.rebuildStream && recovery != null) {
                when (recovery()) {
                    ScreenRecoveryResult.ABORTED -> return@execute
                    ScreenRecoveryResult.FAILED -> {
                        synchronized(this) {
                            if (!connectCancelled && !client.cancelled && currentClient === client) {
                                appendLog("SCREEN: 主屏流恢复失败——结束本轮，等待用户重新连接")
                                appContext?.let { disconnect(it, DiagnosticCode.VIDEO_CLOSED) }
                            }
                        }
                        return@execute
                    }
                    ScreenRecoveryResult.RESTORED -> Unit
                }
            }
            if (connectCancelled ||
                client.cancelled ||
                currentClient !== client ||
                !client.isConnectionCurrent(connectionGeneration)
            ) {
                return@execute
            }
            if (restoreScreen && client.modes.phoneOwnsScreen) {
                videoSender?.reprimeOnNextKeyFrame()
                scheduleTakeBackCheck(client, reason, takeBackRetried)
            }
            // 自己补的 I 帧不计入车机要帧风暴（用户连点图标不能被当成解码失败）
            val keyFrame = restoreScreen && client.modes.phoneOwnsScreen && videoSender?.requestKeyFrame(fromReceiver = false) == true
            appendLog(
                "EVENT: $reason → modesChanged " + (if (asserted) "已应答" else "失败") +
                    if (request.requestKeyFrame) "，关键帧" + (if (keyFrame) "已请求" else "未就绪") else "",
            )
        }
    }

    /** 上次「推流中车机邀请 → 重建会话」的 uptime 时刻（0 = 本次连接尚无；startConnection 清零）。 */
    @Volatile
    private var lastInviteReconnectAtMillis = 0L

    /** 当前推流会话的诊断轮次（邀请重连时在时间线上收口本轮）。 */
    @Volatile
    private var streamingDiagnosticAttempt: String? = null

    /**
     * 车机 ctrl 邀请回调（应答线程）。
     *
     * - 连接阶段：已有本轮空端点时立即触发“绑定当前网络”的交接，不再等下一次 1s 轮询；
     * - 推流阶段：已登记的车机（[ReceiverCompatibility.Policy.reconnectOnStreamingInvite]）
     *   退役当前会话并按邀请重连，带 [StreamingInviteReconnect.DEBOUNCE_MS] 防抖。
     */
    private fun onCtrlInvite(localAddress: String? = null) {
        val local = localAddress?.substringBefore('%')?.takeIf { it.isNotBlank() }
        val observation = activeReceiverObservations.single()
        ctrlInviteEvidence =
            CtrlInviteEvidence(
                receivedAtMillis = SystemClock.uptimeMillis(),
                target = currentTargetBinding() ?: observation?.binding,
                localAddress = local,
                network = local?.let { wifiConnector?.wifiNetworkWithLocalAddress(it) },
                observation = observation,
            )
        val client = currentClient
        if (client == null) {
            val pending = pendingStartSessionEndpoint.get()
            if (pending != null && !connectCancelled && !sessionStarting.get()) {
                Bg.run("ctrl-invite-wifi-handoff") { continueOnInvitedWifi(pending, "CTRL邀请") }
            }
            return
        }
        val streaming = !connectCancelled && !sessionStarting.get() && videoSender?.isRunning == true
        if (!streaming) return
        val now = android.os.SystemClock.uptimeMillis()
        val decision =
            synchronized(inviteReconnectLock) {
                StreamingInviteReconnect
                    .decide(
                        streaming = true,
                        enabled = client.receiverCompatibility().reconnectOnStreamingInvite,
                        nowMillis = now,
                        lastReconnectAtMillis = lastInviteReconnectAtMillis,
                    ).also { if (it == StreamingInviteReconnect.Decision.RECONNECT) lastInviteReconnectAtMillis = now }
            }
        when (decision) {
            StreamingInviteReconnect.Decision.NOT_STREAMING -> Unit
            StreamingInviteReconnect.Decision.NOT_ENABLED ->
                appendLog("CTRL: 推流中收到车机邀请——该车机未登记邀请重连，保持当前会话")
            StreamingInviteReconnect.Decision.DEBOUNCED ->
                appendLog(
                    "CTRL: 推流中收到车机邀请——距上次邀请重连 ${now - lastInviteReconnectAtMillis}ms，" +
                        "不足 ${StreamingInviteReconnect.DEBOUNCE_MS / 1000}s，忽略",
                )
            StreamingInviteReconnect.Decision.RECONNECT ->
                Bg.run("streaming-invite-reconnect") { reconnectOnStreamingInvite(client) }
        }
    }

    private val inviteReconnectLock = Any()

    private fun reconnectOnStreamingInvite(client: AirPlayClient) {
        val endpoint = client.currentEndpoint
        if (connectCancelled ||
            currentClient !== client ||
            sessionStarting.get() ||
            videoSender?.isRunning != true ||
            !ReceiverDetectionRuntime.isCurrent(endpoint.observation)
        ) {
            appendLog("CTRL: 推流中邀请重连——会话已更替或结束，放弃")
            return
        }
        appendLog(">>> 推流中收到车机 ctrl 邀请（车机要求重建会话）——退役当前会话，按邀请重连")
        streamingDiagnosticAttempt?.let { attempt ->
            ConnectionDiagnostics.timeline.note(attempt, "CTRL invite while streaming: reconnect")
            ConnectionDiagnostics.timeline.videoEnded(attempt)
        }
        streamingDiagnosticAttempt = null
        retireSession()
        onCarPlayEndpoint(endpoint, "推流中邀请重连")
    }

    /** 本次连接内「SETUP 被车机关通道后等邀请重连」已用次数（startConnection 清零）。 */
    @Volatile
    private var inviteRetryCount = 0

    /**
     * 初始 SETUP 被车机立即关通道后：等车机下一次 ctrl 邀请（比当前更新的那次）再重走连接。
     * 帕萨特 2025 实测邀请在被拒后 0.3–0.6s 就会再来；不重试的话本轮只能等用户手动重连。
     */
    private fun scheduleInviteRetry(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (inviteRetryCount >= MAX_INVITE_RETRIES) {
            appendLog(">>> [$source] 已按车机邀请重连 $MAX_INVITE_RETRIES 次仍被拒——不再自动重试，请导出日志反馈")
            return
        }
        val attempt = ++inviteRetryCount
        val baseline = ctrlAdvertiser?.lastInviteAtMillis ?: 0L
        Bg.run("invite-retry") {
            val deadline = android.os.SystemClock.uptimeMillis() + INVITE_RETRY_WAIT_MS
            while (!connectCancelled && android.os.SystemClock.uptimeMillis() < deadline) {
                val latest = ctrlAdvertiser?.lastInviteAtMillis ?: 0L
                if (latest > baseline && !sessionStarting.get()) {
                    appendLog(">>> [$source] 收到车机新邀请——第 $attempt 次重连 AirPlay")
                    onCarPlayEndpoint(endpoint, "$source/邀请重连")
                    return@run
                }
                Thread.sleep(200)
            }
            if (!connectCancelled) {
                appendLog(">>> [$source] ${INVITE_RETRY_WAIT_MS / 1000}s 内未收到车机新邀请——不再重试")
            }
        }
    }

    /**
     * 配对循环的端点刷新器（非阻塞）：只读浏览器缓存的 `_airplay._tcp`，
     * 有则并入 IP/端口/公钥等字段。返回 null = 无新信息（配对循环继续用当前端点）。
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? = endpointResolver.enrichEndpointFromBrowser(endpoint, source)

    private fun txtValue(
        info: DiscoveredEndpoint,
        key: String,
    ): String? = EndpointResolver.txtValue(info, key)

    /**
     * 晚到端点优先：车机明确告知的端口/公钥优先于 mDNS 合成值，
     * 但 IP 列表取并集（0x4301 可能不带 IPv4，Audi MMI 9310 实测即如此）。
     */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint = EndpointResolver.preferEndpoint(late, fallback)

    /**
     * 初始 SETUP（带重连重试）。
     *
     * 已配对路径：配对循环刚连通过，SETUP 一次即可（失败就是真失败，交给降级/收尾）。
     * 明文会话路径：车机的 AirPlay 端口可能要到「用户点车机图标」后才开，
     * catplay 同样是「收到邀请才连」；这里在 [INITIAL_SETUP_WINDOW_MS] 内每 2s
     * 重连一次并重试 SETUP，避免端口晚开 1~2 秒就整轮失败。
     */
    private fun runInitialSetup(
        client: AirPlayClient,
        plainSession: Boolean,
        noPairing: Boolean,
    ): AirPlayClient.SetupInfo? {
        val first = client.runSetup()
        if (first != null || !plainSession) return first
        val deadline = android.os.SystemClock.uptimeMillis() + INITIAL_SETUP_WINDOW_MS
        var attempt = 1
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            attempt++
            appendLog("AIRPLAY: 明文 SETUP 第 ${attempt - 1} 次未通过——2s 后重连重试（车机 AirPlay 端口可能尚未开放）")
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return null
            }
            if (connectCancelled) return null
            client.close()
            if (!client.reconnect(2_000)) continue
            val retried = client.runSetup()
            if (retried != null) {
                appendLog("AIRPLAY: ✓ 明文 SETUP 在第 $attempt 次尝试成功")
                return retried
            }
            // 端口开了但 SETUP 仍被拒：TXT 判断可能错了，交给调用方的加密升级路径
            if (!noPairing) return null
        }
        return null
    }

    /** BT 会话交接点：拿到 0x5703/0x4301 后两条路径流程一致——绑 WiFi → AirPlay 配对 → SETUP → 推流。 */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
        ownershipPreAcquired: Boolean = false,
        verifiedCurrentNetwork: Network? = null,
        requireCurrentBinding: Boolean = false,
    ) {
        fun releaseHandoff() {
            releaseHandoffOwnership(endpoint.observation)
        }

        // 预先持有所有权的调用方（ctrl 邀请）把释放责任交给本函数：任何提前返回都要归还。
        fun rejectPreAcquired() {
            if (ownershipPreAcquired) releaseHandoff()
        }
        val context = appContext
        if (context == null) {
            rejectPreAcquired()
            return
        }

        // 熔断按实际发起本轮的控制链路对端隔离；未选定目标车机时也不会误拦其它车机。
        val peerAddress = endpoint.peerAddress ?: TargetDevicePrefs.get(context)?.address

        // 入口在构造 attempt scope 之前直查检测归属：过期端点不得触碰交接互斥。
        if (!ReceiverDetectionRuntime.isCurrent(endpoint.observation)) {
            rejectPreAcquired()
            return
        }
        // 自动重连熔断中：车机回连的端点也忽略。手动连接会先清空熔断，不受影响。
        if (reconnectAttribution.block(peerAddress) != null) {
            appendLog(">>> [$source] 自动重连已暂停——忽略车机回连端点（手动连接可解除）")
            rejectPreAcquired()
            return
        }
        // 冗余端点防护：接收端控制器（DiPlay 实测）在我们配对推流后才完成既有的
        // RFCOMM 重连并重发 0x4301。此时重启配对会 retireSession 掉健康会话
        // （编码器 stop 竞态 = 「连上后自动断开」循环的根因）。推流中的端点一律忽略；
        // 真断连时推流自行终止（isRunning=false），下一个端点自然接续重连。
        if (videoSender?.isRunning == true) {
            rejectPreAcquired()
            appendLog(">>> [$source] 会话推流中——忽略冗余端点（接收端周期重连的重复 0x4301）")
            return
        }
        if (!ownershipPreAcquired) {
            // 获取所有权与清除上一轮取消标志在同一锁内完成；断开流程先关闭入口，
            // 因此不会出现“begin 后又清掉 connectCancelled”的竞态。
            if (!handoffOwnership.begin(endpoint.observation) { connectCancelled = false }) {
                appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
                return
            }
        }
        if (!handoffOwnership.owns(endpoint.observation)) {
            appendLog(">>> [$source] 交接在获取后被断开流程释放——忽略本次端点")
            return
        }
        val diagnosticAttempt = ConnectionDiagnostics.receiverAttempt()
        // 本轮失败/成功的熔断记账都以这条控制链路的对端地址为键。
        activePeerAddress = peerAddress
        ReceiverDetectionRuntime.attach(endpoint.observation, diagnosticAttempt)
        // scope 只读现状：id/observation 固定为本轮，两个 Boolean 每次访问都重新问运行时。
        val scope =
            LegacyAttemptScope(
                id = diagnosticAttempt,
                observation = endpoint.observation,
                cancelled = { connectCancelled },
                owns = { ReceiverDetectionRuntime.isCurrent(it) },
            )

        // 交接归属是独立于 isCurrent/isActive 的第二层判据，不能折进 scope。
        fun handoffCancelled(): Boolean = attemptHandoffCancelled(scope) { handoffOwnership.owns(endpoint.observation) }
        ConnectionDiagnostics.timeline.note(
            diagnosticAttempt,
            "RECEIVER srcvers=${endpoint.sourceVersion} features=${endpoint.advertisedFeatures}",
        )
        // 新一轮交接开始：清掉已失效轮次或已关闭链路的晚到端点暂存。仍在 active 上的
        // 其他 observation（另一条 RFCOMM 链路先到的 0x4301）必须保留——drain 已按
        // observation 过滤，不会再覆盖本会话刚解析出的端点；它们等本次交接释放后单独唤醒。
        // ctrl-invite 绑定期间收到的同轮 0x4301 同样保留；配对线程稍后会合并它。
        pendingEndpointUpdate.retainIf { isActiveObservation(it.observation) }
        if (endpoint.ssid.isNotEmpty()) {
            pendingStartSessionEndpoint.set(null)
        }
        // 指纹与是否有 AP 信息无关：CHERY/Tiggo 在空端点等待晚到 0x4301 时也要能
        // 显示该车型的 30 秒窗口提示。
        currentReceiverMatchesArrizo8 = endpoint.isArrizo8Fingerprint()
        // 空端点 = 新协议车机声明会发 0x4301 但窗口内未到（Iap2SenderSession 已不拆链，
        // 保活泵会把晚到的 0x4301 经 onLateEndpoint 送回本函数重走完整流程）。
        // 这里不占 sessionStarting：晚到端点走「上一轮已结束」分支；等待期间轮询手机
        // WiFi——用户手动连上车机热点（密码车机设置可知，凯迪拉克 CT5 实测）后经确认
        // 可直接以该 SSID 合成端点走 mDNS/网关路径。
        if (endpoint.ssid.isEmpty()) {
            pendingStartSessionEndpoint.set(endpoint)
            ConnectionDiagnostics.timeline.stage(diagnosticAttempt, ConnectionStage.BLUETOOTH)
            ConnectionDiagnostics.timeline.issue(diagnosticAttempt, DiagnosticCode.ENDPOINT_PENDING)
            appendLog(
                ">>> [$source] 端点无 AP 信息（车机未在窗口内下发 0x4301）——蓝牙保持保活等待晚到 0x4301；" +
                    "检测到手机已连 WiFi 时会询问是否按车机热点继续",
            )
            ui.phase(t(R.string.sc_waiting_4301))
            releaseHandoff()
            watchForPendingStartSession(endpoint, source)
            return
        }
        when (endpoint.wifiCredentialSource) {
            WifiCredentialSource.ACCESSORY -> {
                ConnectionDiagnostics.timeline.milestone(
                    diagnosticAttempt,
                    ConnectionMilestone.WIFI_CREDENTIALS,
                    "source=endpoint",
                )
                appendLog(
                    ">>> [$source] 拿到车机完整热点配置：SSID 已读取 security=${endpoint.securityType}；" +
                        "密码=${if (endpoint.securityType == 0) "(空，车机声明开放)" else "已获取"}",
                )
            }

            WifiCredentialSource.KNOWN_SSID -> {
                appendLog(
                    ">>> [$source] 只有已知 SSID，没有安全类型/密码——" +
                        "先核对当前网络，绝不按开放热点重新加入",
                )
            }

            WifiCredentialSource.CURRENT_NETWORK -> {
                appendLog(">>> [$source] 复用已验证的当前车机网络（$source）——只绑定，不按 SSID 重新加入")
            }

            WifiCredentialSource.NONE -> {
                appendLog(">>> [$source] 端点没有可用 Wi-Fi 信息——等待车机补发或用户确认")
            }
        }
        ui.phase(t(R.string.sc_resolving_hotspot))
        if (endpoint.ssid != INVITED_WIFI_LABEL) ui.ssidChanged { endpoint.ssid }
        // 只有完整配置才写密码缓存；仅 SSID 不得把空密码合成开放网络。
        // 占位标签不是真实 SSID，也不能写进缓存。
        rememberEndpointWifi(endpoint)
        // 诊断（老车机排查必看）：地址是 IPv4 还是只有 IPv6、TXT features 是否声明配对能力。
        // 奥迪 4xxx 两种「连不上」都源于这里——旧版日志里完全没有这两项。
        val addresses = endpoint.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        val pairingFlag = AirPlayFeatures.supportsPairing(endpoint.advertisedFeatures)
        appendLog(
            ">>> [$source] 端点诊断：地址=${addresses.ifEmpty { listOf("(无)") }}" +
                "（${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "无地址"
                } else {
                    "仅 IPv6"
                }}）" +
                " features=" + (endpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知") +
                " HomeKit配对=" +
                when (pairingFlag) {
                    true -> "声明支持"
                    false -> "未声明（按 pre-HomeKit 明文会话处理）"
                    null -> "未知（按需配对）"
                },
        )
        // 端点携带本轮蓝牙事实，mDNS 的能力补充到同一份策略，不丢失接收端身份。
        appendLog(
            ReceiverCompatibility.describe(
                ReceiverCompatibility.resolve(
                    endpoint.receiverSignals.copy(
                        features = endpoint.advertisedFeatures,
                        srcvers = endpoint.sourceVersion,
                    ),
                ),
            ),
        )
        // 先绑网络再解析端点：mDNS 组播跟随 bindProcessToNetwork 的网络，解析放前面
        // 只会拿到旧网络的结果（此前靠 resolveEndpointHost 内部重试掩盖，白等数秒）。
        // 绑定完成后再并行做三件事：ctrl 广播针对新网络重注册、后台发现 _airplay._tcp、
        // 等车机 ctrl 邀请（车机已发现我们的权威信号）。
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound || handoffCancelled()) {
                ConnectionDiagnostics.timeline.abandon(diagnosticAttempt, !scope.isActive, DiagnosticCode.WIFI_JOIN_FAILED)
                val joinCode = ConnectionDiagnostics.timeline.snapshot()?.code
                if (scope.isActive) {
                    ui.phase(t(R.string.sc_not_on_hotspot_endpoint, endpoint.ssid))
                    appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${endpoint.ssid}\" 后断开重连")
                } else {
                    appendLog(">>> [$source] 网络交接已被取消或蓝牙链路失效——结束本轮入网阶段")
                }
                val late = completePendingOrRelease(endpoint, ::releaseHandoff)
                if (late != null) {
                    appendLog(">>> [$source] 网络交接失败时收到完整 0x4301——接替本轮连接")
                    onCarPlayEndpoint(late, "$source/交接失败晚到0x4301", ownershipPreAcquired = true)
                }
                // 加入热点失败不经过 disconnect：在这里记账，避免连续失败绕过熔断。
                if (recordAutoReconnectOutcome(diagnosticAttempt, joinCode, peerAddress)) {
                    appContext?.let { app ->
                        Bg.run("auto-reconnect-trip") { disconnect(app, joinCode ?: DiagnosticCode.WIFI_JOIN_FAILED) }
                    }
                }
                return@proceed
            }
            ConnectionDiagnostics.timeline.milestone(diagnosticAttempt, ConnectionMilestone.WIFI_JOINED)
            ConnectionDiagnostics.timeline.stage(diagnosticAttempt, ConnectionStage.DISCOVERY)
            // 网络绑定完成：ctrl 广播重新注册（落到车机所在网络），同时开始后台 mDNS 发现。
            // 两者都发生在等邀请期间，不额外占用时间。
            if (!wifiHandoffGuard.runIfActive(::handoffCancelled) {
                    ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = wifiConnector?.currentNetwork)
                    endpointBrowser?.restart()
                }
            ) {
                releaseHandoff()
                return@proceed
            }
            // 0x4301 自带完整地址+端口 = 接收端已明确告知「连这里」，无需等邀请
            // （DiPlay 测试台路径：本轮直接配对，省掉固定等待）。
            // IPv6 也算地址：奥迪 4xxx 的 0x4301 常只给 fe80::（旧判据只看 IPv4，
            // 会把这类端点当成「不完整」，白等 ctrl 邀请再退回网关兜底）。
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)
            val airPlayConnected = AtomicBoolean(false)
            Bg.run("airplay-pairing") {
                try {
                    if (!scope.isActive) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        releaseHandoff()
                        return@run
                    }
                    if (endpointSelfContained) {
                        appendLog("AIRPLAY: 0x4301 端点完整（${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}）——直接配对，不等 ctrl 邀请")
                    } else {
                        // 等 ctrl 邀请（catplay CarPlayCtrlServer：邀请=车机已发现手机且
                        // AirPlay 窗口已开，收到即刻连接）。此前是「固定睡 1.6s 赌它已到」，
                        // 快的时候白等、慢的时候不够；改为事件驱动。
                        awaitCtrlInvite(endpoint, CTRL_INVITE_WAIT_MS)
                    }
                    // 端点解析放在邀请之后：车机的 `_airplay._tcp` 常在受邀后才上线，
                    // 且这一步最长 3s——必须在后台线程做，不能占住网络回调线程。
                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    // 交接流程起步期间车机补发的 0x4301（比 mDNS 合成更权威）在此并入。
                    pendingEndpointUpdate.takeIf { it.observation === endpoint.observation }?.let { late ->
                        effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                        appendLog(
                            "AIRPLAY: 并入交接期间晚到的 0x4301 端点 → " +
                                "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                        )
                    }
                    if (effectiveEndpoint.airPlayPort > 0 && EndpointHosts.hasUsable(effectiveEndpoint.ipAddresses)) {
                        ConnectionDiagnostics.timeline.milestone(
                            diagnosticAttempt,
                            ConnectionMilestone.RECEIVER_RESOLVED,
                            if (effectiveEndpoint.portIsFallback) "port=fallback" else "port=advertised",
                        )
                    }
                    if (!scope.isActive) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        releaseHandoff()
                        return@run
                    }

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            // 链路代际：蓝牙侧 0x1D01 画像已判定（CLASSIC_MIB3/EVENT_LINK），
                            // AirPlay 阶段的 MIB3 布防据此分派 marker 策略（2026-10-23 分代重构）
                            linkGeneration = ReceiverProfile.linkGenerationOf(effectiveEndpoint.receiverSignals),
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                            initialInfoRequest = ReceiverDetectionRuntime.infoRequest(effectiveEndpoint.observation),
                            detecting = ReceiverDetectionRuntime.collecting(effectiveEndpoint.observation),
                            retryTick = { attempt ->
                                // 配对重试期间车机仍可能没探测到 ctrl（Bonjour 漏掉首次公告）：
                                // 周期性强制重播一次，并把 mDNS 现状写进日志——否则排查时
                                // 只看得到「端口未开」的重试刷屏，无法判断是车机没邀请还是
                                // 地址不对（2026-10-19 奥迪 0.1.7 日志就缺这两条信息）。
                                ctrlAdvertiser?.ensureForNetwork(
                                    endpoint.ssid,
                                    force = true,
                                    network = wifiConnector?.currentNetwork,
                                )
                                val invites = ctrlAdvertiser?.inviteCount ?: 0
                                val found = endpointBrowser?.awaitEndpoint(0)
                                appendLog(
                                    "AIRPLAY: 重试 #$attempt —— 已重新公告 ctrl；" +
                                        "车机邀请=$invites 次，mDNS _airplay._tcp=" +
                                        (found?.let { "${it.host?.hostAddress}:${it.port}" } ?: "未发现"),
                                )
                            },
                        ).also { created ->
                            created.onMilestone = { reached, detail ->
                                if (reached == ConnectionMilestone.AIRPLAY_CONNECTED) {
                                    airPlayConnected.set(true)
                                    val current = ConnectionDiagnostics.timeline.snapshot()?.stage
                                    if (ConnectionStagePolicy.shouldEnterPairing(current)) {
                                        ConnectionDiagnostics.timeline.stage(diagnosticAttempt, ConnectionStage.PAIRING)
                                    }
                                }
                                ConnectionDiagnostics.timeline.milestone(diagnosticAttempt, reached, detail)
                            }
                            // 车机媒体键（iAP2 0x6802 通路）：方向盘/面板的上一曲、下一曲。
                            // 放在这里而不是调用点，保证重连重建 client 时也挂得上。
                            created.onMediaKey = { keyCode -> routeCarMediaKey(keyCode, "iAP2 0x6802") }
                            // 旋钮/四向拨杆（iAP2 0x6802 通路）：注入虚拟屏的 DPAD
                            created.onNavKey = { keyCode -> injectNavigationKey(keyCode) }
                            // MIB3 控制会话完成 → modesChanged 资源重申 burst（082446 定谳：
                            // 只在会话早期声明过一次的话，第二控制会话把车机资源仲裁重置，
                            // 车机全程健康却永不切 CarPlay 画面）。
                            created.onTunnelControlSessionComplete = {
                                scheduleModesReclaim(created)
                            }
                        }
                    if (scope.isCurrent) retireSession()
                    // 会话静默降噪（2026-10-10）：从交接点起暂停其它接收端的出站连接探测
                    // （典型：连着 DiPlay 测试台时不再噪音式探测 Audi）。会话结束/断开时解除。
                    BtSessionGate.sessionActive = true
                    // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                    ui.phase(t(R.string.sc_pairing))
                    var client = newClient()
                    if (!scope.isActive) {
                        client.close()
                        return@run
                    }
                    currentClient = client
                    drainPendingEndpoint(client)
                    val compatibility = ReceiverCompatibility.describe(client.receiverCompatibility())
                    appendLog(compatibility)
                    ConnectionDiagnostics.timeline.note(diagnosticAttempt, compatibility)
                    // pre-HomeKit 老车机（CarPlay 210.81 时代，奥迪 4xxx 固件）在
                    // `_airplay._tcp` 里**不声明** HK_PAIRING_AND_ENCRYPT：它们只接受
                    // 明文 SETUP（et=NONE）+ AES-CTR 屏幕流，走 pair-setup 必然失败。
                    // catplay 的接收端分类 1 即此形态；旧实现会在这里空转 120s 配对窗口，
                    // 用户看到的就是「连不上」。
                    val noPairing = client.receiverCompatibility().pairingMode == ReceiverCompatibility.PairingMode.ADVERTISED_PLAIN
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            client.skipPairing("TXT features 未声明 HK_PAIRING_AND_ENCRYPT")
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
                        }
                    if (!scope.isActive) {
                        if (scope.isCurrent) retireSession()
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        releaseHandoff()
                        return@run
                    }
                    // 配对失败后的兜底路径（纯函数决策，真机抓包场景由 PairingFallbackTest 钉死）
                    val fallbackAfterFirstRound =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                            policy = client.receiverCompatibility(),
                        )
                    if (fallbackAfterFirstRound.useBtRetireHack) {
                        // 第二阶段（仅测试台 DiPlay）：复现「BT 控制已结束」状态再试一次。
                        // 真车（Audi）绝不能断 BT——真无线 CarPlay 的 BT 控制通道全程保活，
                        // 断开会让车机拆掉整轮 AirPlay 窗口（实车实测）。
                        // pairingForbidden（任意段 403，含 verify/认证段——2026-10-21 真机
                        // sysdiagnose 定谳：首连被 403 是车机固有状态，它 18~20s 后自动
                        // 重邀、第二次即成）时同样禁用：断 BT 会让车机整栈重建、恰好错过
                        // 即将到来的重邀窗口。
                        // 门槛：仅当本轮真的连上过接收端 RTSP（attemptedPairing）才用此 hack；
                        // 端点未解析（无 IP）/端口未开时绝不值得断 BT（2026-10-11 踩过：
                        // mDNS 失败也触发 detach，白白拆掉车机窗口）。
                        // 这里只模拟交接后的退役状态，不结束 EA 会话。
                        detachActiveBtSessions(eaStopReason = null)
                        client = newClient()
                        if (!scope.isActive) {
                            client.close()
                            return@run
                        }
                        currentClient = client
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (!scope.isActive) {
                            if (scope.isCurrent) retireSession()
                            appendLog("AIRPLAY: 用户已取消——中止配对")
                            releaseHandoff()
                            return@run
                        }
                    }
                    // keys == null 时仍有合法路径：**catplay 的 et=NONE 明文会话**。
                    //  - 接收端明确不支持配对（pre-HomeKit / TXT 无 HK_PAIRING_AND_ENCRYPT）；
                    //  - 配对端点明确返回不支持。超时、连接复位或普通失败不构成明文依据。
                    //  - 403（pairingForbidden）禁止降级：既可能是车机尚未就绪，
                    //    也可能是软件接收端拒绝设备身份，不能从状态码推断明文能力。
                    // hack 段可能已重跑拿到 keys：决策以重跑后的状态为准。
                    val plainSession =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                            policy = client.receiverCompatibility(),
                            allowPlainAfterFailure = appContext?.let(CompatibilityPrefs::plainFallback) == true,
                        ).usePlainSession
                    if (plainSession &&
                        appContext?.let(CompatibilityPrefs::plainFallback) == true &&
                        !client.pairingUnsupported &&
                        client.receiverCompatibility().pairingMode != ReceiverCompatibility.PairingMode.ADVERTISED_PLAIN
                    ) {
                        appendLog("COMPAT: 用户开启「配对失败后尝试明文」，本轮按明文会话兜底")
                    }
                    if (plainSession) {
                        ui.phase(t(R.string.sc_plain_session))
                        appendLog(
                            "AIRPLAY: 配对不可用（${if (client.pairingUnsupported) "接收端不支持" else "配对失败"}）" +
                                "——改用 et=NONE 明文会话 + AES-CTR 屏幕流（catplay 接收端分类 1/2）",
                        )
                        // 配对循环结束时 socket 已关/状态机不可用：SETUP 前必须重连一次。
                        // 失败不直接判死：车机 AirPlay 端口可能还没开，下面会按窗口重试。
                        if (!client.reconnect()) {
                            appendLog("AIRPLAY: 明文会话首次重连未成功——继续按窗口重试 SETUP")
                        }
                    }
                    // keys 是 lambda 内的可变捕获变量：Kotlin 无法智能转换，先固化成不可变值。
                    val pairedKeys = keys
                    if (pairedKeys != null || plainSession) {
                        ConnectionDiagnostics.timeline.stage(diagnosticAttempt, ConnectionStage.SETUP)
                        ui.phase(if (pairedKeys != null) t(R.string.sc_paired_setup) else t(R.string.sc_plain_session))
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ 配对完成 writeKey=" +
                                    pairedKeys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "… " +
                                    "readKey=" + pairedKeys.phoneReadKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                        }
                        // 已配对 → 共享密钥；明文会话 → null（流加密降级为 AES-CTR 零密钥）
                        var sessionKey = pairedKeys?.sharedSecret
                        var setup = runInitialSetup(client, plainSession, noPairing)
                        if (setup == null && plainSession && noPairing) {
                            // catplay 的「加密升级」路径：只有在**我们主动跳过配对**（TXT 未声明
                            // HK_PAIRING_AND_ENCRYPT）且明文 SETUP 仍失败时才补做。
                            // 反过来（配对先试过且失败）再试一次没有意义，只会多等 30s。
                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
                                sessionKey = upgraded.sharedSecret
                                setup = client.runSetup()
                            }
                        }
                        if (!scope.isActive) {
                            client.close()
                            return@run
                        }
                        if (setup != null && client.controlChannelClosed) {
                            // 帕萨特 2025（VW4211）：没等到新鲜 ctrl 邀请就连入时，初始 SETUP 照回 200
                            // 但随即关控制通道；继续往下走只会得到假会话（/info、事件通道、屏幕流全失败）。
                            appendLog(
                                "AIRPLAY: 车机在初始 SETUP 后立即关闭控制通道（多为未等到新鲜 ctrl 邀请就连入）" +
                                    "——放弃本次会话，等车机下一次邀请后重连",
                            )
                            runCatching { client.close() }
                            scheduleInviteRetry(endpoint, source)
                            return@run
                        }
                        if (setup != null) {
                            // 记录会话凭据：投影授权晚于连接时用于动态补启音频。
                            // 用 client.currentEndpoint 而非 effectiveEndpoint：配对期间
                            // mDNS 刷新过端点时，真正连上的是 client 里的地址（2026-10-18：
                            // 0x4301 的定长 IP 字段带 NUL，mDNS 给的是干净地址，取旧值
                            // 会让视频端口/keepAlive 用脏地址 → 一律解析失败）。
                            currentSharedSecret = sessionKey
                            currentAudioHost =
                                client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            // 每通道一个 router；媒体键边沿与旧轮隔离。
                            val eventCommandRouter =
                                EventCommandRouter(
                                    scope = scope,
                                    setup = setup,
                                    isStaleClient = { currentClient !== client },
                                    client =
                                        object : EventCommandRouter.EventClient {
                                            override val modes = client.modes

                                            override fun onIapEventBytes(data: ByteArray) = client.onIapEventBytes(data)
                                        },
                                    counters =
                                        object : EventCommandRouter.EventCounters {
                                            override var touchLogCount: Int
                                                get() = this@SessionController.touchLogCount
                                                set(value) {
                                                    this@SessionController.touchLogCount = value
                                                }

                                            override var consumerReleaseCount: Int
                                                get() = this@SessionController.consumerReleaseCount
                                                set(value) {
                                                    this@SessionController.consumerReleaseCount = value
                                                }

                                            override var iapEventMessageCount: Int
                                                get() = this@SessionController.iapEventMessageCount
                                                set(value) {
                                                    this@SessionController.iapEventMessageCount = value
                                                }

                                            override var forceKeyFrameCount: Int
                                                get() = this@SessionController.forceKeyFrameCount
                                                set(value) {
                                                    this@SessionController.forceKeyFrameCount = value
                                                }

                                            override var requestUiCount: Int
                                                get() = this@SessionController.requestUiCount
                                                set(value) {
                                                    this@SessionController.requestUiCount = value
                                                }
                                        },
                                    nowUptimeMillis = { SystemClock.uptimeMillis() },
                                    output =
                                        object : EventCommandRouter.EventOutput {
                                            override fun appendLog(line: String) = this@SessionController.appendLog(line)

                                            override fun routeCarMediaKey(
                                                keyCode: Int,
                                                label: String,
                                            ) = this@SessionController.routeCarMediaKey(keyCode, label)

                                            override fun injectNavigationKey(keyCode: Int) =
                                                this@SessionController.injectNavigationKey(keyCode)

                                            override fun enqueueModesAssert(
                                                reason: String,
                                                requestKeyFrame: Boolean,
                                                retakeScreen: Boolean,
                                                rebuildStream: Boolean,
                                            ) {
                                                this@SessionController.enqueueModesAssert(
                                                    client,
                                                    reason,
                                                    requestKeyFrame = requestKeyFrame,
                                                    retakeScreen = retakeScreen,
                                                    rebuildStream = rebuildStream,
                                                )
                                            }

                                            override fun runBackground(
                                                name: String,
                                                task: () -> Unit,
                                            ) = Bg.run(name, task)

                                            override fun retryAudioAfterClaimReturned() =
                                                this@SessionController.retryAudioAfterClaimReturned(client)

                                            override fun onTouchSent() = ReceiverDetectionRuntime.touch(client.currentEndpoint.observation)
                                        },
                                    currentVideoSender = {
                                        videoSender?.let { sender ->
                                            object : EventCommandRouter.KeyFrameSender {
                                                override fun requestKeyFrame(): Boolean = sender.requestKeyFrame()

                                                override val lastReceiverRequestIdrGapMs: Long?
                                                    get() = sender.lastReceiverRequestIdrGapMs

                                                override val isExternalPassthrough: Boolean
                                                    get() = sender.isExternalPassthrough

                                                override val isTranscoding: Boolean
                                                    get() = sender.isTranscoding
                                            }
                                        }
                                    },
                                    currentTouchTarget = {
                                        currentCarLifeSession?.let { carLife ->
                                            object : EventCommandRouter.TouchTarget {
                                                override val videoWidth: Int
                                                    get() = carLife.videoWidth

                                                override val videoHeight: Int
                                                    get() = carLife.videoHeight

                                                override fun sendTouch(
                                                    x: Int,
                                                    y: Int,
                                                    down: Boolean,
                                                ): Boolean = carLife.sendTouch(x, y, down)
                                            }
                                        }
                                    },
                                    audioEnabled = { audioEnabledForSession },
                                    audioStreamRunning = { audioSender?.isRunning == true },
                                    resolveScreenReturnRebuild = { requested ->
                                        ScreenReturnPolicy.rebuildStream(currentScreenReturnPolicy(client).screenReturn, requested)
                                    },
                                )
                            client.connectEventChannel(sessionKey, setup) { command ->
                                eventCommandRouter.onCommand(command)
                            }
                            // 像 iPhone 一样请车机关蓝牙（真 iPhone 会话开始约 0.1 s 即发，早于屏幕流）
                            if (client.receiverCompatibility().releaseBluetoothAfterSetup) client.releaseReceiverBluetooth()
                            // 资源声明（must）：真车对未声明资源的屏幕流 SETUP 直接 400
                            client.assertModes()
                            // HID 输入模式武装：/info hidDevices（奥迪字符串 "0"/"1"，触摸="1"）。
                            // display features=0x08（高保真触摸）已声明，缺这步车机不发 hidSendReport。
                            setup.hidDeviceUuids.forEach { uuid ->
                                client.sendHidSetInputMode(uuid, mode = 0)
                            }
                            val screen = client.runScreenStream(sessionKey, setup.displayUuid)
                            if (screen != null) {
                                // 地址取 client 真正连通的那个（配对期间可能被 mDNS 刷新过）：
                                // 视频数据端口与 keepAlive 都按它建 socket；IPv6 端点也算
                                // 可用地址（EndpointHosts 统一排序：IPv4 → 全局 IPv6 → 链路本地 IPv6）。
                                val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                                // 心跳双保险（缺失任一都会被车机 ~10s 看门狗拆会话，2026-10-11 实测）：
                                //   keepAlive UDP ping（车机 SETUP 返回的 keepAlivePort，10s 周期）
                                //   + RTSP POST /feedback（1s 周期，catplay FEEDBACK_INTERVAL）
                                if (host != null && setup.keepAlivePort != null) {
                                    client.startKeepAlive(host, setup.keepAlivePort!!)
                                }
                                client.startFeedbackLoop()
                                // iAP 隧道（type-130）：车机→手机的 iAP2 EA 数据（触摸 HID 载体）。
                                // 建立失败不阻塞会话（触摸不可用但视频照常）。
                                val tunnel = client.runIapTunnel(sessionKey)
                                tunnel?.onIapMessage = { frame ->
                                    // 触摸证据采集：先全量记录，格式确认后接 TouchRouter
                                    if (frame.messageId != 0x0000) {
                                        val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                        appendLog(
                                            "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // 关键时序（2026-10-17 奥迪定谳）：屏幕 SETUP 后必须尽快连上
                                // dataPort——车机有 ~2s 看门狗。音频 SETUP 实测要 ~2.1s，
                                // 若排在前面，端口连接迟到即触发车机 reset 控制通道
                                // （日志特征：`音频 SETUP 往返 2091ms` 与 `EVENT 读取结束
                                // - Connection reset` 同一毫秒）。故先建 VideoSender 并
                                // openDataPort()，再协商音频，最后 RECORD + 启动编码器。
                                var pendingVideoW = 0
                                var pendingVideoH = 0
                                var carLife: CarLifeSession? = null
                                lateinit var sessionVideoSender: VideoSender
                                if (host != null) {
                                    // 「显示」偏好：流画布/虚拟屏按车机分辨率百分比生成。
                                    // 只在会话建立时生效——编码器与虚拟屏尺寸此时定型；
                                    // 密度同步缩放，逻辑布局（dp）与 100% 完全一致。
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    val scale = scalePct / 100f
                                    // 缩放后向下对齐偶数（H.264 要求）：虚拟屏/合成器/编码器
                                    // 三方必须同一尺寸，故在源头对齐（VideoSender 内另有兜底）
                                    val streamW =
                                        ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                    val streamH =
                                        ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                    val fps =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .fps(it)
                                            } ?: 30
                                    // 缩放后的画布才是交给编码器的最终尺寸。实验面板可能把它推到编码器
                                    // 无法承受的规模（2288×1728@160% ≈ 10.1MP），这里按资源上限收敛；
                                    // 被缩小时「实际发送尺寸」判定会如实报告实验未生效（PR #78 P2）。
                                    val (fittedW, fittedH) =
                                        if (setup.experimentalPanelGeometry != null) {
                                            DisplaySurfaceReport.fitWithinCanvasLimits(streamW, streamH)
                                        } else {
                                            streamW to streamH
                                        }
                                    if (fittedW != streamW || fittedH != streamH) {
                                        appendLog(
                                            "DISPLAY: 实验画布 ${streamW}x$streamH 超出资源上限 → 收敛为 " +
                                                "${fittedW}x$fittedH（不会再请求 ${streamW}x$streamH）",
                                        )
                                    }
                                    val effectiveW = fittedW
                                    val effectiveH = fittedH
                                    activeScalePercent = scalePct
                                    activeTargetFps = fps
                                    appendLog(
                                        "DISPLAY: 流 ${setup.displayWidth}x${setup.displayHeight} → " +
                                            "${effectiveW}x$effectiveH（$scalePct%）@${fps}fps",
                                    )
                                    // 实验（issue #62）：面板几何同时成为触摸映射的源尺寸。目前没有证据
                                    // 证明车机 HID 坐标范围也随之变化；若它仍按 displays[0] 上报，触摸会偏移。
                                    // 这里明确写出待验证点，避免把「实验已开启」误读成「触摸已适配」。
                                    if (setup.experimentalPanelGeometry != null) {
                                        appendLog(
                                            "EVENT: 触摸源尺寸按实验面板 " +
                                                "${setup.displayWidth}x${setup.displayHeight} 映射" +
                                                "（车机 HID 是否同范围未经台架/实车验证，偏移即为此因）",
                                        )
                                    }
                                    pendingVideoW = effectiveW
                                    pendingVideoH = effectiveH
                                    val carLifeSource =
                                        object : ExternalVideoSource {
                                            override fun requestKeyFrame(): Boolean = currentCarLifeSession?.requestKeyFrame() == true
                                        }

                                    fun newScreenSender(screenInfo: AirPlayClient.ScreenStreamInfo): VideoSender {
                                        lateinit var sender: VideoSender
                                        val firstFrameSent = AtomicBoolean(false)
                                        sender =
                                            VideoSender(
                                                host = host,
                                                dataPort = screenInfo.dataPort,
                                                frameCipher = screenInfo.cipher,
                                                // 用收敛后的尺寸：实验面板不得把编码器画布推出资源上限。
                                                width = effectiveW,
                                                height = effectiveH,
                                                targetFps = fps,
                                                log = { line -> appendLog(line) },
                                                keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                                externalVideoSource = carLifeSource,
                                                receiverMaxFps = setup.maxFps,
                                                // 车机拿走主屏（原车界面、倒车影像等）时暂停屏幕视频，归还后从新的 IDR 恢复
                                                phoneOwnsScreen = { client.modes.phoneOwnsScreen },
                                                onScreenOutputSuspended = { suspended ->
                                                    ConnectionDiagnostics.timeline.screenOutput(diagnosticAttempt, suspended)
                                                },
                                                onExternalFrameSent = { bytes ->
                                                    ConnectionDiagnostics.timeline.media(diagnosticAttempt, true, bytes)
                                                    if (firstFrameSent.compareAndSet(false, true)) {
                                                        // 真出画了：清空自动重连熔断计数（本轮已是健康会话）
                                                        appContext?.let { app ->
                                                            AutoReconnectGuard.recordHealthy(
                                                                app,
                                                                peerAddress,
                                                                diagnosticAttempt,
                                                            )
                                                        }
                                                    }
                                                    val now = android.os.SystemClock.uptimeMillis()
                                                    if (lastEnvironmentSampleAt == 0L ||
                                                        now - lastEnvironmentSampleAt >= ENVIRONMENT_SAMPLE_MS
                                                    ) {
                                                        lastEnvironmentSampleAt = now
                                                        appContext?.let { app ->
                                                            Bg.run("stream-environment") {
                                                                appendLog("ENV: ${StreamEnvironment.describe(app)}")
                                                            }
                                                        }
                                                    }
                                                },
                                                onEnded = onVideoEnded@{
                                                    videoEndedHandling.handle(
                                                        object : VideoEndCapture<VideoSender, CarLifeSession> {
                                                            override val senderIdentity: () -> VideoSender = { sender }

                                                            override val carLifeBinding: () -> CarLifeSession? = { carLife }

                                                            override val mediaObservation: () -> Any? =
                                                                { client.currentEndpoint.observation }

                                                            override val attempt: String = diagnosticAttempt

                                                            override val peerAddress: String? = peerAddress
                                                        },
                                                    )
                                                },
                                            )
                                        // 实验（issue #62，PR #78 评审 P2）：只在 VideoConfig 真正写出后判定，
                                        // 且尺寸变化时重新判定——首个源帧可能未发送，之后还可能切转码/主屏恢复。
                                        setup.experimentalPanelGeometry?.let { target ->
                                            val lastReported = AtomicReference<Pair<Int, Int>?>(null)
                                            sender.onVideoConfigSent = { actualWidth, actualHeight ->
                                                reportPanelExperiment(target, actualWidth, actualHeight, lastReported)
                                            }
                                        }
                                        return sender
                                    }
                                    sessionVideoSender = newScreenSender(screen)
                                    videoSender = sessionVideoSender
                                    // 先连端口（车机看门狗），编码器等 RECORD 后再启动
                                    videoSender?.openDataPort()
                                    screenStreamRecovery = recover@{
                                        fun failure(): ScreenRecoveryResult =
                                            if (!scope.isActive || client.cancelled || currentClient !== client) {
                                                ScreenRecoveryResult.ABORTED
                                            } else {
                                                ScreenRecoveryResult.FAILED
                                            }
                                        val previousSender =
                                            synchronized(this) {
                                                val previousOutput = carLifeOutput ?: return@recover ScreenRecoveryResult.ABORTED
                                                val previous = videoSender ?: return@recover ScreenRecoveryResult.ABORTED
                                                if (!scope.isActive ||
                                                    currentClient !== client ||
                                                    previousOutput.sender !== previous ||
                                                    currentCarLifeSession !== carLife ||
                                                    !client.modes.phoneOwnsScreen
                                                ) {
                                                    return@recover ScreenRecoveryResult.ABORTED
                                                }
                                                carLifeOutput = null
                                                videoSender = null
                                                previous
                                            }
                                        appendLog("SCREEN: 回原车界面后的 requestUI——仅重建主屏流，保留 CarWith 与音频")
                                        previousSender.stop()
                                        if (!scope.isActive || !client.teardownScreenStream()) return@recover failure()
                                        if (!scope.isActive) return@recover ScreenRecoveryResult.ABORTED
                                        val resumed = client.runScreenStream(sessionKey, setup.displayUuid) ?: return@recover failure()
                                        if (!scope.isActive) return@recover ScreenRecoveryResult.ABORTED
                                        val replacement = newScreenSender(resumed)
                                        var restored = false
                                        try {
                                            synchronized(this) {
                                                if (!scope.isActive || currentClient !== client) return@recover ScreenRecoveryResult.ABORTED
                                                videoSender = replacement
                                            }
                                            if (!replacement.openDataPort() ||
                                                !scope.isActive ||
                                                // 屏幕流已 TEARDOWN/SETUP：单次 RECORD 车机也必须重发一次，
                                                // 否则新流没有激活（艾瑞泽 8 重进后触控/按键失效）。
                                                !client.record(force = true) ||
                                                !scope.isActive ||
                                                !replacement.start {} ||
                                                !scope.isActive
                                            ) {
                                                return@recover failure()
                                            }
                                            synchronized(this) {
                                                if (!scope.isActive || currentClient !== client || videoSender !== replacement) {
                                                    return@recover ScreenRecoveryResult.ABORTED
                                                }
                                                carLifeOutput = CarLifeOutput(diagnosticAttempt, replacement)
                                            }
                                            client.modes.onScreenStreamRestored()
                                            replacement.requestKeyFrame(fromReceiver = false)
                                            restored = true
                                            appendLog("SCREEN: 主屏流已重新 SETUP/RECORD，等待新的 IDR 与触控")
                                            ScreenRecoveryResult.RESTORED
                                        } finally {
                                            if (!restored) {
                                                synchronized(this) {
                                                    if (videoSender === replacement) videoSender = null
                                                    if (carLifeOutput?.sender === replacement) carLifeOutput = null
                                                }
                                                replacement.stop()
                                            }
                                        }
                                    }
                                }
                                // 先 RECORD 激活会话并起视频，再协商音频（2026-10-17 奥迪定谳）：
                                // 车机在「屏幕流 SETUP 之后」有约 2s 看门狗，等不到 RECORD/数据流即
                                // 拆掉整轮会话。音频 SETUP 实测本身要 ~2.05-2.16s（车机侧音频通路
                                // 初始化），排在 RECORD 之前必然超时——日志里 reset 与音频 SETUP
                                // 返回同一毫秒、且 5 次实测全部复现。故顺序改为：
                                //   屏幕 SETUP → 连数据端口 → RECORD → 起编码器（出帧）→ 音频 SETUP
                                // 音频 SETUP 失败只丢声音，视频会话保持。
                                if (!client.record()) {
                                    appendLog("AIRPLAY: RECORD 未确认——结束本轮，不启动视频或音频")
                                    ConnectionDiagnostics.timeline.finish(diagnosticAttempt, DiagnosticCode.SETUP_FAILED, true)
                                    // 该出口只关客户端、不走 disconnect：必须在这里记账。
                                    val tripped =
                                        recordAutoReconnectOutcome(diagnosticAttempt, DiagnosticCode.SETUP_FAILED, peerAddress)
                                    if (scope.isCurrent && currentClient === client) {
                                        ui.phase(t(R.string.sc_record_unconfirmed))
                                        ui.hint { t(R.string.sc_record_unconfirmed) }
                                        retireSession()
                                    } else {
                                        client.close()
                                    }
                                    if (tripped) {
                                        appContext?.let { app ->
                                            Bg.run("auto-reconnect-trip") { disconnect(app, DiagnosticCode.SETUP_FAILED) }
                                        }
                                    }
                                    return@run
                                }
                                ConnectionDiagnostics.timeline.stage(diagnosticAttempt, ConnectionStage.CARWITH)
                                if (host != null) {
                                    val videoW = pendingVideoW
                                    val videoH = pendingVideoH
                                    val output = CarLifeOutput(diagnosticAttempt, sessionVideoSender)
                                    // 按接收端屏幕比例请求，减少缩放/黑边；CarWith 不采纳时以其返回为准。
                                    // 实验（issue #62）：默认 1920 长边上限会把面板尺寸先压小（2288×1728 →
                                    // 1920×1448），使实验连「请求正确尺寸」都做不到；命中面板时放开到面板长边，
                                    // 但是否真的生效仍以实际返回尺寸为准（见 newFormalCarLifeSession）。
                                    val experimentalPanelWidth = setup.experimentalPanelGeometry?.first
                                    val experimentalPanelHeight = setup.experimentalPanelGeometry?.second
                                    val requestMaxLongSide =
                                        if (experimentalPanelWidth != null && experimentalPanelHeight != null) {
                                            maxOf(
                                                CarLifeProtocol.VIDEO_REQUEST_MAX_LONG_SIDE,
                                                experimentalPanelWidth,
                                                experimentalPanelHeight,
                                            )
                                        } else {
                                            CarLifeProtocol.VIDEO_REQUEST_MAX_LONG_SIDE
                                        }
                                    val requestedVideo =
                                        CarLifeProtocol.videoRequestFor(
                                            setup.displayWidth,
                                            setup.displayHeight,
                                            requestMaxLongSide,
                                        )
                                    val compatibility = client.receiverCompatibility()
                                    val preferVehicleResolution =
                                        compatibility.preferVehicleResolution &&
                                            setup.displayFromInfo &&
                                            setup.displayWidth >= 2 &&
                                            setup.displayHeight >= 2
                                    if (setup.experimentalPanelGeometry != null) {
                                        appendLog(
                                            "DISPLAY: 实验面板几何 ${experimentalPanelWidth}x$experimentalPanelHeight" +
                                                " → 请求 CarWith ${requestedVideo.first}x${requestedVideo.second}" +
                                                "（长边上限 $requestMaxLongSide）",
                                        )
                                    }
                                    if (preferVehicleResolution) {
                                        appendLog(
                                            "CARLIFE: 将声明 DISABLE_RESOLUTION_OPTION=1，请求 CarWith 按车机显示尺寸 " +
                                                "${requestedVideo.first}x${requestedVideo.second} 编码",
                                        )
                                    } else if (compatibility.preferVehicleResolution) {
                                        appendLog(
                                            "CARLIFE: 未取得有效 /info 显示几何，暂不声明 DISABLE_RESOLUTION_OPTION",
                                        )
                                    }
                                    val createdCarLife =
                                        newFormalCarLifeSession(
                                            context,
                                            requestedVideo,
                                            preferVehicleResolution = preferVehicleResolution,
                                        )
                                    carLife = createdCarLife
                                    val claimed = claimCarLifeSession(CarLifeSessionRegistry.Owner.FORMAL, createdCarLife)
                                    val videoStarted = claimed && videoSender?.start {} == true
                                    // 入网已完成，此时才拿低延迟锁（持锁会让入网所需的 Wi‑Fi 扫描失败）
                                    if (videoStarted) StreamingWifiLock.acquire(context)
                                    if (videoStarted) streamingDiagnosticAttempt = diagnosticAttempt
                                    // 锁屏/息屏/Doze 迁移写进日志与时间线（锁屏退出的定位依据，只读不改状态）
                                    if (videoStarted) {
                                        ScreenLockMonitor.start(
                                            context,
                                            log = ::appendLog,
                                            note = { ConnectionDiagnostics.timeline.note(diagnosticAttempt, it) },
                                        )
                                    }
                                    if (videoStarted) carLifeOutput = output
                                    // 在 CarWith 启动前布防：CarWith 可能在 start() 内就恢复媒体，
                                    // 启动后再取 nowPlaying 会把自动播放误当成“连接前已在播放”。
                                    if (videoStarted) armAutoPlayBehavior(createdCarLife)
                                    val carLifeStarted = videoStarted && createdCarLife.start()
                                    appendLog(
                                        "MODE: CarWith ${if (carLifeStarted) "已请求连接，等待真实视频" else "连接请求失败"} " +
                                            "${videoW}x$videoH（等待 CarLife 首帧，不代表已完成握手）",
                                    )
                                    if (!claimed) {
                                        ConnectionDiagnostics.timeline.finish(diagnosticAttempt, DiagnosticCode.CARWITH_BUSY, true)
                                        appendLog("MODE: CarWith 自检正在占用 CarWith，本次 CarPlay 不启动视频桥接")
                                    }
                                    if (carLifeStarted) {
                                        appendLog("SCREEN: CarWith 尚未收到首帧，保持手机可见以完成原厂连接")
                                    } else {
                                        stopAutoPlayWatch()
                                        ConnectionDiagnostics.timeline.finish(diagnosticAttempt, DiagnosticCode.CARWITH_FAILED, true)
                                        if (carLifeOutput === output) carLifeOutput = null
                                        releaseCarLifeSession(createdCarLife)
                                    }
                                }
                                // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                // 会话已激活（RECORD 完成）后再协商；失败不影响视频主链路。
                                val audioStarted =
                                    startAudioAndRecord(
                                        context,
                                        client,
                                        sessionKey,
                                        host,
                                        // 奥迪曾需流后补发；受影响车机由客户端策略跳过重复 RECORD。
                                        "AUDIO: 音频流已加入——检查会话激活",
                                        recordAfterSetup = !client.receiverCompatibility().skipRecordAfterInitialAudioSetup,
                                    )
                                // 主音频被车机约束占用时的“未启动”不是音频故障：不写结论码，
                                // 否则它会长期盖住后续真正需要的诊断（如屏幕暂停）。
                                if (audioEnabledForSession &&
                                    !audioStarted &&
                                    !client.modes.audioClaimAwaitingReturn &&
                                    client.modes.phoneOwnsAudio
                                ) {
                                    ConnectionDiagnostics.timeline.issue(diagnosticAttempt, DiagnosticCode.AUDIO_UNAVAILABLE)
                                }
                            }
                        } else {
                            // 会话级 SETUP 被拒（典型：接收端按设备过滤回 403，见
                            // AirPlayClient.runSetup 的 deviceID 说明）。主动关闭控制通道：
                            // 否则接收端要等约 45s 视频超时才拆 BT 链路，用户只看到
                            // 「连上之后自动断开」，无从判断是会话建立失败。
                            ui.phase(t(R.string.sc_setup_rejected))
                            ConnectionDiagnostics.timeline.finish(diagnosticAttempt, DiagnosticCode.SETUP_FAILED, true)
                            // 该出口只关客户端、不走 disconnect：必须在这里记账。
                            val tripped =
                                recordAutoReconnectOutcome(diagnosticAttempt, DiagnosticCode.SETUP_FAILED, peerAddress)
                            appendLog("AIRPLAY: 会话建立失败——主动关闭控制通道，本轮结束")
                            ui.hint { t(R.string.sc_setup_rejected_hint) }
                            runCatching { client.close() }
                            if (tripped) {
                                appContext?.let { app ->
                                    Bg.run("auto-reconnect-trip") { disconnect(app, DiagnosticCode.SETUP_FAILED) }
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    appendLog("AIRPLAY: 配对线程异常 - ${error.message ?: error.javaClass.simpleName}")
                    // 只记异常，不在此结束本轮：finally 按阶段/取消原因统一判定结果
                    ConnectionDiagnostics.timeline.note(diagnosticAttempt, "EXCEPTION ${error.stackTraceToString()}")
                } finally {
                    releaseHandoff()
                    // 配对未成功建立推流（fail/异常/start 失败提前退出）时解除静默降噪，
                    // 否则 BT-C 会永久停在门闸外、连真车路径失效。
                    // 推流活跃时保持关闭，由 VideoSender.onEnded（推流终止）负责解除。
                    if (scope.isCurrent && videoSender?.isRunning != true) {
                        ConnectionDiagnostics.timeline.abandon(
                            diagnosticAttempt,
                            !scope.isActive,
                            if (!airPlayConnected.get() && scope.isActive) DiagnosticCode.AIRPLAY_CONNECT_FAILED else null,
                        )
                        // 配对/加入/异常等失败都从这里收尾，且不经过 disconnect：按实际结束码记账。
                        val abandonCode = ConnectionDiagnostics.timeline.snapshot()?.code
                        if (recordAutoReconnectOutcome(diagnosticAttempt, abandonCode, peerAddress)) {
                            appContext?.let { app ->
                                Bg.run("auto-reconnect-trip") {
                                    disconnect(app, abandonCode ?: DiagnosticCode.CONNECTION_INCOMPLETE)
                                }
                            }
                        }
                        BtSessionGate.sessionActive = false
                        // 收尾竞态：晚到 0x4301 若恰好在「配对已失败、互斥尚未释放」的
                        // 窗口内到达，会被暂存但没人消费。此处补投一次——车机等 15s
                        // 无人连入就拆链，错过这一投就是整轮失败（2026-10-18 分析）。
                        val pending =
                            if (scope.isActive) {
                                pendingEndpointUpdate.takeIf { it.observation === endpoint.observation }
                            } else {
                                null
                            }
                        if (pending != null && scope.isActive) {
                            appendLog(">>> 收尾时发现暂存的晚到 0x4301——立即按新端点重走一轮")
                            Bg.run("late-endpoint-retry") {
                                onCarPlayEndpoint(pending, "0x4301 晚到（本轮收尾补投）")
                            }
                        }
                    }
                }
            }
        }
        val targetBinding = wifiCacheBinding(endpoint.observation)
        val sourceKind =
            when {
                endpoint.hasCompleteWifiCredentials -> WifiCredentialSource.ACCESSORY
                endpoint.wifiCredentialSource == WifiCredentialSource.KNOWN_SSID -> WifiCredentialSource.KNOWN_SSID
                endpoint.wifiCredentialSource == WifiCredentialSource.CURRENT_NETWORK -> WifiCredentialSource.CURRENT_NETWORK
                else -> WifiCredentialSource.NONE
            }
        val cachedWifi =
            if (sourceKind == WifiCredentialSource.KNOWN_SSID) {
                wifiCredentialCache.lookup(targetBinding, endpoint.ssid)
            } else {
                null
            }

        carWifiJoiner.dispatchHandoff(
            CarWifiHandoffRequest(
                sourceLabel = source,
                source =
                    when (sourceKind) {
                        WifiCredentialSource.ACCESSORY -> CarWifiHandoffSource.ACCESSORY
                        WifiCredentialSource.KNOWN_SSID -> CarWifiHandoffSource.KNOWN_SSID
                        WifiCredentialSource.CURRENT_NETWORK -> CarWifiHandoffSource.CURRENT_NETWORK
                        WifiCredentialSource.NONE -> CarWifiHandoffSource.NONE
                    },
                ssid = endpoint.ssid,
                accessoryWifi = endpoint.accessoryWifiOrNull(),
                cachedWifi = cachedWifi,
                verifiedCurrentNetwork = verifiedCurrentNetwork,
                requireCurrentBinding = requireCurrentBinding,
                pendingEndpoint = pendingEndpointPort(endpoint, source),
                targetCurrent = { scope.isCurrent },
                scopeActive = { scope.isActive },
                cachedRetryCancelled = { !scope.isActive },
                handoffCancelled = { handoffCancelled() },
                freshInvite = { receiverHasInvited(endpoint.observation) },
                continueWithNetwork = { success -> continueWithNetwork(success) },
                releaseHandoff = ::releaseHandoff,
                clearCachedCredentials = { wifiCredentialCache.clear() },
                inviteNetwork = { verifiedInviteNetwork(endpoint.observation) },
            ),
        )
    }

    /** 等待已知 SSID 时，把晚到完整 0x4301 交回上层接替（端点对象不进入 net）。 */
    private fun pendingEndpointPort(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): CarWifiPendingEndpointPort =
        object : CarWifiPendingEndpointPort {
            override fun tryContinueDuringWait(onClaimed: () -> Unit): Boolean = continueWithLateEndpoint(endpoint, source, onClaimed)

            override fun tryContinueBeforeTimeout(onClaimed: () -> Unit): Boolean = continueWithLateEndpoint(endpoint, source, onClaimed)
        }

    private fun continueWithLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
        onClaimed: () -> Unit,
    ): Boolean {
        val late = completePendingFor(endpoint) ?: return false
        onClaimed()
        onCarPlayEndpoint(
            late,
            "$source/手动入网晚到0x4301",
            ownershipPreAcquired = true,
        )
        return true
    }

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0

    /** 车机媒体键「释放」报文计数（值为 0 的 1 字节报告，静默丢弃，仅计数备查）。 */
    @Volatile
    private var consumerReleaseCount = 0

    /** 车机 requestUI 计数（用户点 CarPlay 图标的次数；反复点通常意味着没切到 CarPlay 画面）。 */
    @Volatile
    private var requestUiCount = 0

    /** 上次记录推流环境（Wi-Fi/温控/电量）的时刻；0 = 本轮尚未记录。 */
    @Volatile
    private var lastEnvironmentSampleAt = 0L

    /** 车机 forceKeyFrame 计数（日志限流用）。 */
    @Volatile
    private var forceKeyFrameCount = 0

    @Volatile
    private var iapEventMessageCount = 0
}

/** 配对失败后的兜底路径决策（纯函数，真机抓包场景由 PairingFallbackTest 钉死）。 */
internal data class PairingFallback(
    /** 「断 BT 复现控制结束再试」二段 hack（仅测试台 DiPlay 形态）。 */
    val useBtRetireHack: Boolean,
    /** et=NONE 明文会话降级，只接受能力声明或明确不支持配对的证据。 */
    val usePlainSession: Boolean,
)

/**
 * 403 不代表支持明文或应该断蓝牙：原厂接收端可能尚未就绪，软件接收端也可能拒绝
 * 不匹配的设备身份。能力声明、接收端例外及失败事实由统一兼容策略共同决策。
 */
internal fun pairingFallback(
    keysEstablished: Boolean,
    noPairing: Boolean,
    pairSetupRejected: Boolean,
    pairingForbidden: Boolean,
    attemptedPairing: Boolean,
    pairingUnsupported: Boolean,
    policy: ReceiverCompatibility.Policy = ReceiverCompatibility.resolve(ReceiverProfile.Signals()),
    allowPlainAfterFailure: Boolean = false,
): PairingFallback {
    val decision =
        ReceiverCompatibility.pairingFallback(
            policy,
            keysEstablished,
            noPairing,
            pairSetupRejected,
            pairingForbidden,
            attemptedPairing,
            pairingUnsupported,
            allowPlainAfterFailure,
        )
    return PairingFallback(decision.useBtRetireHack, decision.usePlainSession)
}

/** [requestUiAudioDecision] 的结果。 */
internal data class RequestUiAudioDecision(
    /** 是否在 requestUI 的 modesChanged 里把主音频（resourceID=2）声明给手机。 */
    val takeMainAudio: Boolean,
    /** 是否应后台重试建立音频流（此前 SETUP 因车机高约束占用主音频被推迟）。 */
    val retryAfterPendingClaim: Boolean,
)

/**
 * 用户点 CarPlay 图标（`requestUI`）时的主音频决策。
 *
 * [audioEnabled] 只是用户在「启动连接」里的选择，不代表本轮音频流已建立：无共享密钥、
 * 无投屏授权或所有音频档位 SETUP 失败时 `startAudioChannel` 都返回 false，发送器不存在。
 * 此时若在 modesChanged 里把主音频声明给手机，车机会停掉自己的音频，而 CarPlay 没有
 * 发送端接替——重进 CarPlay 后静音，或抢走车机正在播放的本地音频。因此只在
 * [audioStreamRunning] 时随 requestUI 接管主音频。
 *
 * [audioClaimAwaitingReturn] 表示音频 SETUP 因车机以高于 userInitiated 的约束持有/借用
 * 主音频而推迟。这条路径不在这里声明归属，而是交给 [retryAfterPendingClaim] 的后台重试，
 * 由 `ModeArbiter.onAudioStreamStarting()` 按重试时的约束决定是否接管（倒车提示音等不抢）。
 */
internal fun requestUiAudioDecision(
    audioEnabled: Boolean,
    audioStreamRunning: Boolean,
    audioClaimAwaitingReturn: Boolean,
): RequestUiAudioDecision =
    RequestUiAudioDecision(
        takeMainAudio = audioStreamRunning,
        retryAfterPendingClaim = audioEnabled && !audioStreamRunning && audioClaimAwaitingReturn,
    )

/** 占位标签只能作为本次绑定键，不能伪装成后续轮次可复用的真实 SSID。 */
internal fun usableKnownCarSsid(
    bound: String?,
    last: String?,
): String? =
    listOf(bound, last)
        .firstOrNull { !it.isNullOrBlank() && it != SessionController.INVITED_WIFI_LABEL }

/**
 * 端点交付前失败时，是否应结束当前时间线并计入自动重连熔断。
 *
 * 只收尾仍能继续的轮次：已失败/已结束的轮次不再覆盖记录；已经推进到 DISCOVERY
 * 及以后的轮次说明端点早已交付（那属于 `onLinkClosed` 的语义），这里不重复处理。
 * [waitingForLateEndpoint] 为 true 表示本轮已收到**空端点**、正在有意等待晚到的
 * 0x4301——它同样停在 waiting+BLUETOOTH，但必须由 `watchForPendingStartSession`
 * 自己收尾，不能当成失败。抽成纯函数是为了单测覆盖（bug #66）。
 */
internal fun settlesOnPreEndpointFailure(
    status: String,
    stage: ConnectionStage,
    waitingForLateEndpoint: Boolean = false,
    anotherLinkLive: Boolean = false,
): Boolean =
    status == "waiting" &&
        stage == ConnectionStage.BLUETOOTH &&
        !waitingForLateEndpoint &&
        !anotherLinkLive

/**
 * 链路关闭是否应进入自动重连熔断记账。
 *
 * 熔断按对端地址累计，但同一台车机可能同时有多条 RFCOMM 链路（客户端/服务端并存、
 * 空端点候选）。只有当前交接 owner、取消回调已释放的 owner、已交付当前会话的链路、
 * 或正在等待晚到 0x4301 的空端点候选关闭才算失败；其余链路关闭只清理自身状态，
 * 不能把健康交接累计成 BT_LINK_CLOSED。
 */
internal fun countsLinkCloseForAutoReconnect(
    ownedHandoffEnded: Boolean,
    ownedHandoff: Boolean,
    ownsCurrentSession: Boolean,
    ownsPendingStartSession: Boolean,
): Boolean = ownedHandoffEnded || ownedHandoff || ownsCurrentSession || ownsPendingStartSession

/** 音频起流后的存活时间：不足 10 s 用毫秒，避免拆链窗口附近的整秒取整丢失间隔。 */
internal fun audioLivedLabel(livedMillis: Long): String =
    when {
        livedMillis < 0 -> "未知间隔"
        livedMillis < 10_000L -> "${livedMillis}ms"
        else -> java.lang.String.format(java.util.Locale.ROOT, "%.1fs", livedMillis / 1_000.0)
    }

/**
 * 链路协商未完成时音频会话被拆的归因文案。
 *
 * 只有间隔落在实测 ~10s 看门狗窗口内才写「看门狗形态」；更短或更长都保留实测间隔并明确
 * 标出差异。issue #55 中奔驰车机在音频 SETUP 后 16–559 ms 就关闭屏幕口，固定写成
 * ~10s 看门狗会误导排查。
 */
internal fun audioLinkTeardownNote(livedMillis: Long): String {
    val shape =
        when {
            livedMillis < 0 -> "间隔未知"
            livedMillis < AUDIO_LINK_WATCHDOG_MIN_MS -> "${audioLivedLabel(livedMillis)}，短于 ~10s 看门狗"
            livedMillis <= AUDIO_LINK_WATCHDOG_MAX_MS -> "${audioLivedLabel(livedMillis)}，符合 ~10s 看门狗形态"
            else -> "${audioLivedLabel(livedMillis)}，长于 ~10s 看门狗"
        }
    return "AUDIO: 音频加入后会话被车机拆掉（链路协商未完成，$shape）——不记音频档位"
}

/** 实测 ~10s 链路看门狗为 10.17–10.18 s；只有落在该窗口内才按看门狗形态归因。 */
private const val AUDIO_LINK_WATCHDOG_MIN_MS = 8_000L
private const val AUDIO_LINK_WATCHDOG_MAX_MS = 12_000L
