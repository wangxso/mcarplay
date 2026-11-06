package com.mcarplay.app.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.mcarplay.app.compat.BluetoothBringup
import com.mcarplay.app.compat.ReceiverDetectionRuntime
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）**主动连接车机**的无线 iAP2 RFCOMM 客户端 —— 真车阶段新增。
 *
 * 真无线 CarPlay 建链方向（对照 carplay-wifi-extractor client 模式，已在真车验证）：
 * 手机与车机完成系统级蓝牙配对后，由**手机主动连入**车机 SDP 广播的配件端服务
 * （UUID 00000000-deca-fade-deca-deafdecacaff，奥迪 MIB3 SDP 实测存在）；
 * 奇瑞等接收端则由车机主动回连手机，两个方向均需保留。
 *
 * 连上后复用与测试台完全相同的发送端 bring-up（[Iap2SenderSession]），
 * 成功时回调 [onEndpoint] 交出热点凭据与 AirPlay 端点。
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 发送端链路断开（保活泵退出）时通知主控（释放僵尸轮次，见 SessionController.onSenderLinkClosed）。 */
    private val onLinkClosed: () -> Unit = {},
    /** 本进程已知的车机热点 SSID（本轮无新凭据时沿用，避免每轮 8 秒拆链）。 */
    private val knownCarSsid: () -> String? = { null },
    /** 车机是否已在 WiFi 上发过 ctrl 邀请（无凭据时据此不拆链）。 */
    private val receiverInvited: () -> Boolean = { false },
    /** 0x1D01 画像判定的链路代际（Phase 1 完成后回调，AirPlay 阶段分派用）。 */
    private val onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    /** 目标车机（动态读取：选择后无需重启链路，下一轮尝试即生效）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    private val diagnostics: BluetoothDiagnostics = BluetoothDiagnostics({}),
    /** 将系统蓝牙开关变化交给手机 SDP 监听，以恢复本轮仍需发布的服务。 */
    private val onAdapterStateChanged: (Int) -> Unit = {},
    /** 车机拒绝主动连入、改为等它回连时调用一次（参数为设备名），界面据此提示「在车机上点本机」。 */
    private val onAwaitingReceiverTap: (String) -> Unit = {},
) {
    /** 自动模式 SDP 闸门结论。 */
    enum class SdpGate {
        /** SDP 已声明 …caff。 */
        CONNECT,

        /** SDP 未声明，但有其它证据（车载音频类别 / 曾建立过 iAP2）——仍尝试。 */
        CONNECT_DESPITE_SDP,

        /** 没有任何 CarPlay 证据（耳机、手表等）——跳过，保持其音频连接。 */
        SKIP,
    }

    companion object {
        /**
         * 配件端（车机）无线 iAP2 服务 UUID。
         * 手机端 UUID 是 …cafe（xcertplay IAP2_IPHONE_UUID），配件端是 …caff。
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /** 「蓝牙已连接→已断开」日志节流（车机可能反复自动重连，避免刷屏）。 */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /** 出站探测暂停期间的复查间隔（只读内存状态，不发起蓝牙连接）。 */
        private const val PROBE_IDLE_POLL_MILLIS = 5_000L

        /** ACL 状态未知时用配置文件在线状态兜底的复查间隔（每设备）。 */
        private const val PROFILE_ONLINE_RECHECK_MILLIS = 30_000L

        /** SDP 服务探测等待窗（fetchUuidsWithSdp → ACTION_UUID 广播）。 */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L

        private val KNOWN_SERVICES =
            mapOf(
                0x1101 to "SPP",
                0x1105 to "OPP",
                0x110A to "A2DP源",
                0x110B to "A2DP接收",
                0x110C to "AVRCP目标",
                0x110E to "AVRCP",
                0x1112 to "HSP网关",
                0x1115 to "PANU",
                0x1116 to "NAP",
                0x111E to "HFP",
                0x111F to "HFP网关",
                0x112F to "PBAP",
                0x1132 to "MAP",
                0x1200 to "DID",
            )
        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        /** BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO（显式写出，单测里 Android 常量同样可用）。 */
        private const val DEVICE_CLASS_CAR_AUDIO = 0x0420

        private val HFP_HANDS_FREE_UUID: UUID = UUID.fromString("0000111e$BASE_UUID_SUFFIX")
        private val A2DP_SINK_UUID: UUID = UUID.fromString("0000110b$BASE_UUID_SUFFIX")
        private val MAP_NOTIFICATION_UUID: UUID = UUID.fromString("00001133$BASE_UUID_SUFFIX")

        /**
         * 大众帕萨特 2025 款（2026-10-31 诊断报告）SDP 里没有 …caff，RFCOMM 照样能连上并跑完 iAP2 认证；
         * 只按 SDP 判定会让自动模式永远跳过它。以下任一证据都不让 SDP 否决：
         *  - 曾成功建立 …caff RFCOMM；
         *  - 设备类别为车载音频；
         *  - 服务组合像车机：HFP 免提 + A2DP 接收 + MAP 通知（帕萨特 SDP 实测即此组合；
         *    耳机没有 MAP，手表通常没有 A2DP 接收）。误判的代价只是一次失败的 RFCOMM 尝试。
         */
        fun sdpGateDecision(
            hasIap2Service: Boolean,
            deviceClass: Int?,
            rememberedReceiver: Boolean,
            services: List<UUID> = emptyList(),
        ): SdpGate =
            when {
                hasIap2Service -> SdpGate.CONNECT
                rememberedReceiver || deviceClass == DEVICE_CLASS_CAR_AUDIO || looksLikeHeadUnit(services) ->
                    SdpGate.CONNECT_DESPITE_SDP
                else -> SdpGate.SKIP
            }

        /**
         * 自动模式候选：SDP 缓存带 …caff 的排前，再接 [sdpGateDecision] 放行的例外（帕萨特这类不声明
         * …caff 的车机）；两者都没有时退化为全部已配对设备，由连接循环里的 SDP 闸门逐台判断。
         * 只留带 …caff 的设备会让离线的旧接收端挡住眼前的车机（2026-11-01 审查）。
         */
        fun <T> autoCandidates(
            bonded: List<T>,
            gate: (T) -> SdpGate,
        ): List<T> {
            val decisions = bonded.map { it to gate(it) }
            val withService = decisions.filter { it.second == SdpGate.CONNECT }.map { it.first }
            if (withService.isEmpty()) return bonded
            return withService + decisions.filter { it.second == SdpGate.CONNECT_DESPITE_SDP }.map { it.first }
        }

        private fun looksLikeHeadUnit(services: List<UUID>): Boolean =
            HFP_HANDS_FREE_UUID in services && A2DP_SINK_UUID in services && MAP_NOTIFICATION_UUID in services

        fun describeDeviceClass(deviceClass: Int?): String =
            when (deviceClass) {
                null -> "未知"
                DEVICE_CLASS_CAR_AUDIO -> "车载音频"
                else -> String.format(java.util.Locale.ROOT, "0x%04X", deviceClass)
            }

        /**
         * 目标设备 SDP 声明的服务摘要：已知蓝牙标准服务写简称，其余（含厂商私有 UUID）写全称，
         * 并单列出站使用的 …caff 服务。即使未声明它，车机仍可能主动连接手机的 …cafe 服务。
         * `completed=false` 表示 SDP 未在等待窗内返回，此时列表只是系统缓存。
         */
        fun describeServices(
            uuids: List<UUID>,
            completed: Boolean,
        ): String {
            val names =
                uuids.distinct().map { uuid ->
                    val text = uuid.toString().lowercase()
                    when {
                        uuid == ACCESSORY_IAP2_UUID -> "iAP2(…caff)"
                        text.endsWith(BASE_UUID_SUFFIX) && text.startsWith("0000") ->
                            text.substring(4, 8).toInt(16).let { short ->
                                KNOWN_SERVICES[short]
                                    ?: String.format(java.util.Locale.ROOT, "0x%04X", short)
                            }
                        else -> text
                    }
                }
            val iap2 =
                when {
                    ACCESSORY_IAP2_UUID in uuids -> "有"
                    completed -> "无"
                    else -> "未确定（SDP 未返回）"
                }
            return "声明 ${names.size} 个服务${if (completed) "" else "（SDP 未在 ${SDP_PROBE_TIMEOUT_MILLIS}ms 内返回，仅为系统缓存）"}：" +
                (names.joinToString(" / ").ifEmpty { "无" }) + "；接收端 iAP2(…caff) 服务=$iap2" +
                if (completed && ACCESSORY_IAP2_UUID !in uuids) "（仍可由车机回连，不代表不支持无线 CarPlay）" else ""
        }
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)
    private val stateMonitor = AtomicReference<BluetoothStateMonitor?>(null)
    private var diagnosticsEpoch = 0L

    /**
     * 客户端线程世代号：start()/stop() 都会递增。卡在阻塞 connect() 里的线程被
     * stop() 解除阻塞后（关闭 connecting socket），会因世代不符直接退出——
     * 防止「stop 后线程残活、用户再次启动时新旧两代并发连车机」
     * （2026-10-11 实测：多个僵尸线程并发 RFCOMM 会让车机频繁拆链）。
     */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    private val activeChannel = AtomicReference<Iap2LinkChannel?>(null)

    /** 链路及其会话对象（成对保存，退役时只取属于当前链路的会话发 0xEA01）。 */
    private val activeSession = AtomicReference<Pair<Iap2LinkChannel, Iap2SenderSession>?>(null)

    @Volatile
    private var running = false

    /** 目标设备不在已配对列表的提示节流（避免每 3 秒刷屏）。 */
    private var lastTargetMissingLogAt = 0L

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 指定目标的 SDP 诊断已记录的地址（每轮 start 每台一次）。 */
    private val targetServicesReported = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 拒绝主动连入的车机改为等回连（见 [InboundWaitPolicy]）。 */
    private val inboundWait = InboundWaitPolicy { SystemClock.elapsedRealtime() }

    /** 「是否提供 iAP2 服务」结论缓存（地址→结论）：SDP 查询不便宜，勿每 3s 重查。 */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 设备是否提供配件端 iAP2 服务（…caff）：先读系统 uuids 缓存，缺失再
     * `fetchUuidsWithSdp` 触发一次 SDP 查询并等 `ACTION_UUID` 广播（超时按无服务）。
     * 结论缓存到客户端停止——车机固件升级换 UUID 的场景由重启 App 覆盖。
     * 权限：start() 已校验 BLUETOOTH_CONNECT；uuids/fetchUuidsWithSdp 的
     * SecurityException 由 runCatching 吸收（探测失败按无服务处理，不断音频）。
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(
        device: BluetoothDevice,
        diagnosticEpoch: Long,
    ): Boolean {
        iap2ServiceCache[device.address]?.let { return it }
        if (cachedUuids(device).contains(ACCESSORY_IAP2_UUID)) {
            iap2ServiceCache[device.address] = true
            return true
        }
        val has = probeServices(device, diagnosticEpoch).second.contains(ACCESSORY_IAP2_UUID)
        iap2ServiceCache[device.address] = has
        return has
    }

    @SuppressLint("MissingPermission")
    private fun cachedUuids(device: BluetoothDevice): List<UUID> =
        runCatching { device.uuids?.map { it.uuid }.orEmpty() }.getOrDefault(emptyList())

    /** 触发一次 SDP 并等结果；返回（是否在等待窗内返回，服务 UUID 列表）。 */
    @SuppressLint("MissingPermission")
    private fun probeServices(
        device: BluetoothDevice,
        diagnosticEpoch: Long,
    ): Pair<Boolean, List<UUID>> {
        val startedAt = SystemClock.elapsedRealtime()
        val latch = java.util.concurrent.CountDownLatch(1)
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: android.content.Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address == device.address) latch.countDown()
                }
            }
        var registered = false
        var error: RuntimeException? = null
        val started =
            try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                registered = true
                device.fetchUuidsWithSdp()
            } catch (failure: RuntimeException) {
                error = failure
                false
            }
        var completed = false
        var interrupted = false
        if (started) {
            try {
                completed = latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                Thread.currentThread().interrupt()
            }
        }
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        val uuids = cachedUuids(device)
        diagnostics.sdp(
            diagnosticEpoch,
            device.address,
            when {
                error != null -> BluetoothDiagnostics.SdpOutcome.ERROR
                interrupted -> BluetoothDiagnostics.SdpOutcome.CANCELLED
                !started -> BluetoothDiagnostics.SdpOutcome.NOT_STARTED
                completed -> BluetoothDiagnostics.SdpOutcome.COMPLETE
                else -> BluetoothDiagnostics.SdpOutcome.TIMEOUT
            },
            SystemClock.elapsedRealtime() - startedAt,
            uuids.size,
            uuids.contains(ACCESSORY_IAP2_UUID),
            error,
        )
        return completed to uuids
    }

    /**
     * 指定目标时连接不经 SDP 闸门；这里只做一次诊断，记下车机声明了哪些服务，
     * 用来区分「车机不提供无线 CarPlay」与「提供但未进配对窗口」（2026-10-28 小米 SU7 实测无法区分）。
     */
    private fun reportTargetServices(
        device: BluetoothDevice,
        name: String,
        diagnosticEpoch: Long,
    ) {
        val (completed, uuids) = probeServices(device, diagnosticEpoch)
        if (uuids.contains(ACCESSORY_IAP2_UUID)) iap2ServiceCache[device.address] = true
        log("BT-C: 目标「$name」SDP ${describeServices(uuids, completed)}")
    }

    /** 出站探测窗口（车机 ACL 离线且无会话时停止探测，见 [OutboundProbePolicy]）。 */
    private val probePolicy = OutboundProbePolicy()

    /** 当前是否处于「暂停探测」状态（只用于暂停/恢复日志去重）。 */
    @Volatile
    private var probePaused = false

    /** ACL 未知时的配置文件在线结论缓存（地址 → (在线, 查询时间)）。 */
    private val profileOnlineCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /**
     * CarPlay 会话结束（推流终止）后调用：给一个短暂的探测宽限窗口——车上掉线时还能自动重连，
     * 车机已熄火（ACL 已断）时到点即停，不再整夜探测（2026-11-03/04 奇瑞日志）。
     */
    fun armAfterSessionEnd() {
        probePolicy.arm(SystemClock.elapsedRealtime(), OutboundProbePolicy.SESSION_END_GRACE_MILLIS)
    }

    /**
     * 该设备此刻是否值得主动连：ACL 在线、或处于探测窗口内；ACL 状态未知（进程启动晚于
     * 车机连上）且窗口已过时，以 HFP/A2DP 是否在线兜底（节流，避免每轮都查）。
     */
    private fun probeAllowed(
        device: BluetoothDevice,
        nowMillis: Long,
    ): Boolean {
        val acl = BtAclTracker.observe(device.address)
        if (probePolicy.shouldProbe(acl, nowMillis)) return true
        if (acl != null) return false
        val cached = profileOnlineCache[device.address]
        if (cached != null && nowMillis - cached.second < PROFILE_ONLINE_RECHECK_MILLIS) return cached.first
        val online = runCatching { BtProfiles.connectedNames(context, device.address).isNotEmpty() }.getOrDefault(false)
        profileOnlineCache[device.address] = online to nowMillis
        return online
    }

    /** 包住一次本应用发起的蓝牙链路操作（见 [OutboundProbePolicy.beginSelfLinkChange]）。 */
    private inline fun <T> selfLinkChange(block: () -> T): T {
        probePolicy.beginSelfLinkChange(SystemClock.elapsedRealtime())
        try {
            return block()
        } finally {
            probePolicy.endSelfLinkChange(SystemClock.elapsedRealtime())
        }
    }

    /** 「蓝牙已连接→已断开」日志节流时间戳。 */
    private var lastProfileLogAt = 0L

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        // 用户点连接 / ACL 自动拉起：都打开一次探测窗口（客户端可能正处于暂停探测状态）
        probePolicy.arm(SystemClock.elapsedRealtime(), OutboundProbePolicy.USER_WINDOW_MILLIS)
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: 蓝牙不可用或未开启")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        iap2ServiceCache.clear()
        skippedNoService.clear()
        targetServicesReported.clear()
        inboundWait.reset()
        profileOnlineCache.clear()
        probePaused = false
        running = true
        val myGeneration = generation.incrementAndGet()
        val diagnosticEpoch = diagnostics.epoch().also { diagnosticsEpoch = it }
        diagnostics.client(diagnosticEpoch, started = true)
        stateMonitor
            .getAndSet(
                BluetoothStateMonitor(
                    context,
                    diagnostics,
                    diagnosticEpoch,
                    targetProvider,
                    onAdapterStateChanged = onAdapterStateChanged,
                ) {
                    running && generation.get() == myGeneration
                }.also { it.start() },
            )?.stop()
        val target = targetProvider()
        log(
            "BT-C: 启动——将主动连入车机的无线 iAP2 服务（…caff）" +
                if (target != null) {
                    "，目标：「${target.name}」${target.address}（只连该设备）"
                } else {
                    "（未指定目标：依次尝试全部候选设备，可在「连接准备」中指定）"
                },
        )
        Thread({ connectLoop(myGeneration, diagnosticEpoch) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(
        myGeneration: Long,
        diagnosticEpoch: Long,
    ) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        val profileTrials = HashSet<String>()
        while (running && generation.get() == myGeneration) {
            // 静默降噪（2026-10-10 用户实测后启用）——会话期间完全静默，不产生任何连接尝试/日志：
            //  - inboundActive：接收端正在连我们（两条 RFCOMM 会互相踩；Audi 实测双会话混乱）
            //  - sessionActive：CarPlay 会话已建立（已拿到端点）——与其它接收端的连接尝试全部暂停
            //    典型场景：连着 Redmi/DiPlay 时不再无脑探测车机 Audi（消除日志噪音与蓝牙churn）；
            //    会话结束（任一端 BT 会话终结 / 手动断开）时自动恢复。
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                sleepQuietly(2_000)
                continue
            }
            val bonded = candidateDevices(adapter)
            if (bonded.isEmpty()) {
                // 指定目标但未配对时 candidateDevices 已给出节流提示；此时其它已配对设备仍在，不能说「没有」
                if (targetProvider() == null) log("BT-C: 没有已配对的蓝牙设备——请先在系统设置里与车机完成蓝牙配对")
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            // 车机 ACL 已断开且没有会话时不再主动连（2026-11-03/04 奇瑞整夜每分钟探测一次）。
            // 车机重新上电时 ACL 连上，CarBluetoothReceiver 会重新拉起会话。
            val now = SystemClock.elapsedRealtime()
            val candidates = bonded.filter { probeAllowed(it, now) }
            if (candidates.isEmpty()) {
                if (!probePaused) {
                    probePaused = true
                    log("BT-C: 车机蓝牙未连接且无会话——暂停主动探测（车机蓝牙连上或再次点「连接」后恢复）")
                }
                sleepQuietly(PROBE_IDLE_POLL_MILLIS)
                continue
            }
            if (probePaused) {
                probePaused = false
                log("BT-C: 恢复主动探测")
            }
            var connected: BluetoothSocket? = null
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                // 自动模式（未选目标）先过 SDP 闸门：候选=全部已配对设备，耳机/手表
                // 没有 iAP2 服务，连不上还白白被断 HFP/A2DP（设备级断开兜底更会波及
                // 其它在线设备）——确认提供 …caff 才进入「断开+连接」流程。选定目标
                // 的设备不过闸（用户显式指定，保持原路径与时序）。
                if (targetProvider() != null && targetServicesReported.add(device.address)) {
                    reportTargetServices(device, name, diagnosticEpoch)
                }
                if (targetProvider() == null && !awaitIap2Service(device, diagnosticEpoch)) {
                    val deviceClass = runCatching { device.bluetoothClass?.deviceClass }.getOrNull()
                    val remembered = Iap2ReceiverMemory.contains(context, device.address)
                    val services = cachedUuids(device)
                    when (
                        sdpGateDecision(
                            hasIap2Service = false,
                            deviceClass = deviceClass,
                            rememberedReceiver = remembered,
                            services = services,
                        )
                    ) {
                        SdpGate.SKIP -> {
                            if (skippedNoService.add(device.address)) {
                                log(
                                    "BT-C: 跳过「$name」（未发现 iAP2 服务——非 CarPlay 设备，保持其音频连接；" +
                                        "设备类别=${describeDeviceClass(deviceClass)}）",
                                )
                            }
                            continue
                        }

                        else ->
                            if (skippedNoService.add(device.address)) {
                                log(
                                    "BT-C: 「$name」SDP 未声明 iAP2，但" +
                                        when {
                                            remembered -> "曾建立过 iAP2 链路"
                                            deviceClass == DEVICE_CLASS_CAR_AUDIO -> "设备类别=车载音频"
                                            else -> "服务组合像车机（HFP+A2DP接收+MAP通知）"
                                        } +
                                        "——仍尝试连接（部分车机不在 SDP 里声明 …caff）",
                                )
                            }
                    }
                }
                // SDP 等待期间用户可能已停止或重启；旧轮不能再创建一个无法被 stop() 关闭的新 socket。
                if (!running || generation.get() != myGeneration) return
                // 已确认只接受车机回连：不再每 3 秒连一次（也不做下面的断开配置文件对照），只按间隔探测
                if (inboundWait.skipOutbound(device.address)) continue
                // 未知设备先保留 HFP/A2DP；只有已保存的本地策略或用户发起的一次对照才断开。
                // 每次 start 对同一目标最多执行一次，失败后恢复保留连接的基线，避免 ACL 反复震荡。
                if (ReceiverDetectionRuntime.bluetooth(device.address) == BluetoothBringup.DISCONNECT_PROFILES &&
                    profileTrials.add(device.address)
                ) {
                    val trialEpoch = ReceiverDetectionRuntime.profileTrialStarting(device.address)
                    // 主动断 HFP/A2DP 可能连带 ACL 断开：标记为自己引起，不能据此停止重试
                    val result = selfLinkChange { BtProfiles.disconnectChecked(context, device) }
                    ReceiverDetectionRuntime.profileTrialCompleted(
                        trialEpoch,
                        result.known,
                        result.disconnected,
                        result.before.isNotEmpty(),
                    )
                    if (result.before.isNotEmpty() || !result.known) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                            lastProfileLogAt = now
                            log(
                                "BT-C: 本地适配蓝牙对照：known=${result.known} disconnected=${result.disconnected}" +
                                    " before=${result.before.joinToString("/")} remaining=${result.remaining.joinToString("/")}",
                            )
                        }
                    }
                }
                // 出站 RFCOMM 尝试本身可能拉起又拆掉 ACL：同样标记为自己引起（connect() 可阻塞数十秒）
                val socket = selfLinkChange { tryConnect(device, name, myGeneration, diagnosticEpoch) }
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的无线 iAP2 服务（RFCOMM 建立）")
                    Iap2ReceiverMemory.remember(context, device.address)
                    connected = socket
                    break
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!inboundWait.isWaiting(device.address) && now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        log("BT-C: 连接 $name 失败（具体阶段与系统异常见 BT-DIAG），3 秒后重试…")
                    }
                }
            }
            if (connected == null) {
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() 与 connect() 成功竞争：本线程已过期，不建会话
                currentSocket.compareAndSet(connected, null)
                runCatching { connected.close() }
                return
            }
            runSession(connected)
            sleepQuietly(RETRY_DELAY_MILLIS)
        }
        log("BT-C: 客户端已停止")
    }

    /**
     * 候选设备：优先 SDP 缓存里带配件端 UUID 的已配对设备，加上 SDP 闸门放行的例外（见 [autoCandidates]）；
     * 缓存缺失时退化为全部已配对设备（connect 时的 SDP 查询会自行过滤）。
     * 指定了目标车机时**只返回该设备**（用户配对了多台车机时避免误连）。
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // 目标策略：地址命中即唯一候选——不再按 SDP 缓存过滤（缓存可能缺失，
            // 而用户显式指定就该尝试）；目标不在已配对列表时给一次提示并空转等待。
            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (hit.isEmpty()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                    lastTargetMissingLogAt = now
                    log(
                        "BT-C: 目标设备「${target.name}」(${target.address}) 不在已配对列表" +
                            "——请到系统蓝牙设置完成配对，或在「连接准备」改选目标",
                    )
                }
                return emptyList()
            }
            return hit
        }
        return autoCandidates(bonded) { device ->
            val services = cachedUuids(device)
            sdpGateDecision(
                hasIap2Service = ACCESSORY_IAP2_UUID in services,
                deviceClass = runCatching { device.bluetoothClass?.deviceClass }.getOrNull(),
                rememberedReceiver = Iap2ReceiverMemory.contains(context, device.address),
                services = services,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(
        device: BluetoothDevice,
        name: String,
        myGeneration: Long,
        diagnosticEpoch: Long,
    ): BluetoothSocket? {
        val target = targetProvider()
        val trace =
            diagnostics.connectStarted(
                diagnosticEpoch,
                device.address,
                runCatching { device.bondState }.getOrNull(),
                when {
                    target == null -> BluetoothDiagnostics.PeerMatch.UNSELECTED
                    TargetDevicePrefs.matches(target, device.address) -> BluetoothDiagnostics.PeerMatch.TARGET
                    else -> BluetoothDiagnostics.PeerMatch.OTHER
                },
            )
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (error: IOException) {
                diagnostics.connectFinished(
                    trace,
                    creatingSocket = true,
                    error = error,
                    cancelled =
                        !running || generation.get() != myGeneration,
                )
                return null
            } catch (error: SecurityException) {
                diagnostics.connectFinished(
                    trace,
                    creatingSocket = true,
                    error = error,
                    cancelled =
                        !running || generation.get() != myGeneration,
                )
                return null
            }
        // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
        // stop() 只有能拿到 socket 引用才能 close() 打断它，否则线程会滞留。
        currentSocket.set(socket)
        if (!running || generation.get() != myGeneration) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            diagnostics.connectFinished(trace, cancelled = true)
            return null
        }
        val connectStartedAt = SystemClock.elapsedRealtime()
        return try {
            socket.connect()
            diagnostics.connectFinished(trace, cancelled = !running || generation.get() != myGeneration)
            inboundWait.connected(device.address)
            socket
        } catch (error: IOException) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            val cancelled = !running || generation.get() != myGeneration
            diagnostics.connectFinished(trace, error = error, cancelled = cancelled)
            if (!cancelled) noteConnectFailure(device, name, error, SystemClock.elapsedRealtime() - connectStartedAt)
            null
        } catch (error: SecurityException) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            diagnostics.connectFinished(trace, error = error, cancelled = !running || generation.get() != myGeneration)
            null
        }
    }

    private fun noteConnectFailure(
        device: BluetoothDevice,
        name: String,
        error: IOException,
        elapsedMs: Long,
    ) {
        val services = cachedUuids(device)
        if (!inboundWait.failed(device.address, services, BluetoothFailure.reason(error), elapsedMs)) return
        log(
            "BT-C: 「$name」不接受手机主动连入（SDP 无 …caff，${elapsedMs}ms 即 READ_FAILED" +
                (if (InboundWaitPolicy.INBOUND_ONLY_SIGNATURE in services) "，带艾瑞泽 8 同款厂商服务" else "") +
                "）——改为等待车机回连：请在车机蓝牙/CarPlay 设备列表中点选本机；" +
                "每 ${InboundWaitPolicy.PROBE_INTERVAL_MILLIS / 1000} 秒仍试一次主动连接",
        )
        onAwaitingReceiverTap(name)
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car") { line -> log(line) }
        activeChannel.set(channel)
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                onWifiCredentials,
                onEndpointUpdate,
                onLinkClosed,
                knownCarSsid,
                receiverInvited,
                onGeneration,
                peerAddress = socket.remoteDevice?.address,
                onSession = { session -> if (activeChannel.get() === channel) activeSession.set(channel to session) },
            )
        } catch (error: IOException) {
            log("BT-C: SESSION 失败 - ${error.message}")
        } finally {
            activeChannel.compareAndSet(channel, null)
            activeSession.get()?.takeIf { it.first === channel }?.let { activeSession.compareAndSet(it, null) }
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            channel.close()
            log("BT-C: 本轮会话结束（客户端仍在运行，将自动重连）")
        }
    }

    /**
     * 主动结束当前 BT 控制会话（不影响重连循环）。
     *
     * [eaStopReason] 非空时先结束本轮 EA 会话（0xEA01，见 [retireControlLink]）；
     * 为 null 时立即关闭，供 AirPlay 配对前复现控制通道退役状态。
     */
    fun detachCurrentSession(eaStopReason: String? = null) {
        val channel = activeChannel.getAndSet(null)
        val session = activeSession.getAndSet(null)?.takeIf { it.first === channel }?.second
        val socket = currentSocket.getAndSet(null)
        if (channel == null) {
            socket?.let { runCatching { it.close() } }
            return
        }
        retireControlLink(session, eaStopReason) {
            channel.close()
            socket?.let { runCatching { it.close() } }
        }
        log("BT-C: 已主动断开当前控制会话")
    }

    fun stop() {
        running = false
        // 递增世代：即便有线程卡在 connect()，事后也会因世代不符自杀，不会在下次启动时复活
        generation.incrementAndGet()
        stateMonitor.getAndSet(null)?.stop()
        diagnostics.client(diagnosticsEpoch, started = false)
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
        activeChannel.getAndSet(null)?.let { runCatching { it.close() } }
        activeSession.set(null)
        thread?.interrupt()
        thread = null
        log("BT-C: 已停止")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * 入站会话门闸：车机主动连入的 iAP2 会话活跃期间，出站客户端暂停连接尝试。
 * 目的：车机通常只有单条无线 iAP2 上下文，双向同时建链会让双方互相干扰。
 */
object BtSessionGate {
    /** 入站（车机主动连入）的 iAP2 会话活跃：出站暂停，避免两条 RFCOMM 互相踩。 */
    @Volatile
    var inboundActive: Boolean = false

    /**
     * CarPlay 会话已建立（任一路径成功拿到端点后置位，由 SessionController 维护）：
     * 暂停一切出站连接尝试（静默降噪）——典型表现为连着 DiPlay 时不再探测 Audi。
     * 会话结束/手动断开时由 SessionController 清除。
     */
    @Volatile
    var sessionActive: Boolean = false
}
