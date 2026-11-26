package com.mcarplay.app.compat

import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.net.AirPlayFeatures
import java.security.MessageDigest

/** 协议事实 → 可解释策略。接收端例外只在这里登记；本轮状态通过参数传入，不跨会话缓存。 */
object ReceiverCompatibility {
    enum class Family { GENERIC, WINPLAY, DIPLAY, CLASSIC_MIB3 }

    enum class PairingMode { ADVERTISED_PLAIN, PAIR_AND_ENCRYPT, PROBE }

    enum class BluetoothRole { KEEP_DURING_BRINGUP, NETWORK_HANDOFF }

    enum class BluetoothLossAction { KEEP_NETWORK_SESSION, END_SESSION }

    data class Policy(
        val family: Family,
        val pairingMode: PairingMode,
        val bluetoothRole: BluetoothRole,
        val linkGeneration: ReceiverProfile.LinkGeneration,
        val allowBluetoothRetireRetry: Boolean,
        val requiresRealBluetoothIdentity: Boolean,
        /** 无稳定身份时为 null，禁止把所有未知接收端共用一份持久化兼容记忆。 */
        val memoryKey: String?,
        val rules: List<String>,
        /**
         * 会话 SETUP 后像 iPhone 一样发 `disableBluetooth`：车机关闭自身蓝牙，会话只走 Wi-Fi。
         * 2026-11-02 艾瑞泽 8 车机日志：真 iPhone 每次会话开始约 0.1–0.6 s 都发；不发时车机全程保留
         * 蓝牙 iAP2，mcarplay 断开后 20 s 原厂 `_PerformPeriodTasks` 崩溃。只登记核对过的车机。
         */
        val releaseBluetoothAfterSetup: Boolean = false,
        /**
         * SETUP `macAddress` 填本机在车机热点上的真实 Wi-Fi 地址（iPhone 同款）。艾瑞泽 8 把它记为
         * `mAutoReConnectMacAddress`；mcarplay 原先填占位 `aa:bb:cc:dd:ee:ff`（2026-11-02 车机日志）。
         */
        val reportWifiMacInSetup: Boolean = false,
        /**
         * 不 SETUP type-130 iAP 隧道。艾瑞泽 8 对它报 `Unsupported stream type: 130`，真 iPhone 从不请求，
         * 隧道在该车机上本就不可用（2026-11-02 车机日志）。MBUX 对该 SETUP 不回包并在约 1.4 s 后
         * reset 控制通道（2026-11-20 issue #62 日志），HyperCar 成功路径也不请求 type-130。
         */
        val skipIapTunnel: Boolean = false,
        /**
         * 控制通道请求带 `X-Apple-Client-Name` / `X-Apple-ProtocolVersion`（车机 AirPlay 库含这两个头名）。
         * 艾瑞泽 8 对 iPhone 记 `session started: From=iPhone`，对 mcarplay 为空 `From=`（2026-11-02）。
         */
        val sendAppleClientHeaders: Boolean = false,
        /**
         * 艾瑞泽 8 兼容顺序：pair-setup/pair-verify 优先使用 `X-Apple-HKP: 0`，初始 SETUP 前尝试 MFi-SAP。
         * 2026-11-02 艾瑞泽 8 车机日志。
         */
        val iphoneAuthSequence: Boolean = false,
        /** 在加密会话的初始 SETUP 前请求 /auth-setup。 */
        val authSetupBeforeSession: Boolean = false,
        /**
         * 蓝牙与网络隧道 iAP2 的 MFi 挑战都固定为该长度、只发一次、不学习（null = 按记忆轮换）。艾瑞泽 8 为 MFi Auth 3.0 芯片
         * （32 字节摘要），真 iPhone 每路认证一次 0.45 s 通过；mcarplay 发 20/16B 时车机芯片挂起（I2C `error -110`、认证线程
         * `sem_wait` 无返回），后续挑战排队，会话结束仍有认证挂起；长度记忆还曾把 32B 误记为被拒（2026-11-02 车机日志）。
         */
        val pinnedMfiChallengeLength: Int? = null,
        /** 等待超过 30 s 时显示 CHERY/Tiggo/320.17.7 的车机重置提示。 */
        val arrizo8ConnectWindowHint: Boolean = false,
        /**
         * 推流中收到车机 ctrl 邀请（`GET /ctrl-int/1/connect`）时退役当前会话并按邀请重连。大众 Cinemo 栈车机
         * （ID7 `VW4131`、帕萨特 `VW4211`）推流中发邀请表示要重建会话：手机不接，车机约 18 s 后自行拆会话，
         * 用户看到黑屏（ID7 黑屏诊断：15 次断流前各有一次邀请）。其余车机推流中的邀请只记日志。
         */
        val reconnectOnStreamingInvite: Boolean = false,
        /**
         * 实验：显示屏几何改取 `/info displayPanels[].initialVideoStreams` 指向的表面，而不是 `displays[0]`。
         *
         * issue #62（车主称 2025 款奔驰 GLC，iAP2 `/info` 自报 `Mercedes-Benz / MB_E-P-4`、AirPlay 480.7.4）
         * 链路全通、推流 109 s，但车机不显示画面：`/info` 里 `displays[0]` 为 1278 高、`displayPanels[0]`
         * 为 1728 高且其 `initialVideoStreams` 指向同一 UUID。**该根因未定谳**，文档明确要求先有实车对照
         * 才能改协商（docs/CARWITH-ENTRY-FINDINGS.md「issue #62」一节）。因此本开关：
         *  - 默认关，**只在手动选车型时**打开，不从车机自报身份自动启用；
         *  - 只影响 SETUP 的显示几何（编码尺寸与触摸映射随之变化），不改 `modesChanged` / MIB3 订阅；
         *  - 无面板几何时保持 `displays[0]` 原值，不会退化成兜底分辨率。
         */
        val usePanelVideoGeometry: Boolean = false,
        /**
         * 在 CarWith 的 `0x18052` 功能清单里声明 `DISABLE_RESOLUTION_OPTION=1`，让随后 `0x18007`
         * 的宽高按车机 `/info` 显示几何生效，而不是被 CarWith 映射回自己的分辨率档位。
         *
         * WinPlay 台架默认启用（用于验证实际 CarWith 返回尺寸）；奔驰 MB_E-P 手动预设也启用。
         * 其它接收端保持关闭，避免一次改动同时影响所有车型。
         */
        val preferVehicleResolution: Boolean = false,
        /**
         * SETUP `/setup`、屏幕 SETUP `/`、RECORD `/`、TEARDOWN `/` 共用一个绝对会话 URI
         * （`rtsp://<host>/<sessionId>`），而不是相对路径。
         *
         * 同一份 HyperCar 成功日志的每个请求都是绝对 URI；MBUX 的 RTSP 服务可能按 URI 中的
         * 会话标识把屏幕流绑定到显示会话。仅对手动 `MERCEDES_MB_E` 开启。
         */
        val absoluteSessionUri: Boolean = false,
        /**
         * 会话 SETUP 是否提议 `mainBuffered`（AirPlay type 103 缓冲音频）。
         *
         * 本端只实现 type 100/101 的 UDP 音频；提议未实现的能力会让接收端启用后
         * 等不到对应通道。HyperCar 0.1.0.588 在同车机成功轮明确记录
         * `mainBuffered_proposed=false`，其 SETUP 报文也比 mcarplay 少这一项。
         * 仅手动 `MERCEDES_MB_E` 关闭，其它接收端保持原能力集。
         */
        val offerMainBufferedAudio: Boolean = true,
        /**
         * 会话 SETUP 是否提议 `hevc`。本端视频编码器始终输出 H.264；对只按能力集选解码器的
         * 接收端，提议 HEVC 会让车机等待一条永远不会来的 HEVC 流。HyperCar 0.1.0.588 在
         * `MB_E-P-4` 成功轮提议了 HEVC，但用户要求本车型固定走 H.264，故仅手动
         * `MERCEDES_MB_E` 关闭；其它接收端保持既有能力集。
         */
        val offerHevc: Boolean = true,
        /**
         * 屏幕 SETUP 前再发一次 `modesChanged`。HyperCar 0.1.0.588 在 `MB_E-P-4` 成功轮的
         * 顺序是 RECORD → 首次资源声明 → 第二次资源声明 → 屏幕 SETUP；第二次声明与其屏幕
         * 资源生命周期绑定。仅手动 `MERCEDES_MB_E` 开启，其它接收端保持已验证的一次声明。
         */
        val repeatScreenModesDeclaration: Boolean = false,
        /**
         * 屏幕 SETUP 前不发送 AirPlay `hidSetInputMode`。HyperCar 0.1.0.588 在 `MB_E-P-4`
         * 成功轮没有发这些命令，首帧后车机仍正常发送 `hidSendReport` 并完成触控。仅手动
         * `MERCEDES_MB_E` 跳过；其它接收端（奥迪等）继续靠该命令武装触摸。
         */
        val skipScreenHidArming: Boolean = false,
        val rebuildScreenOnResourceReturn: Boolean = false,
    )

    data class PairingFallback(
        val useBtRetireHack: Boolean,
        val usePlainSession: Boolean,
    )

    fun resolve(
        signals: ReceiverProfile.Signals,
        mediaCapabilities: String = "",
        receiverInstance: String? = null,
        preset: VehiclePreset = VehiclePresetRuntime.current,
    ): Policy {
        val policy = applyPreset(resolveDetected(signals, mediaCapabilities, receiverInstance), preset, signals)
        val features = signals.features ?: 0L
        val unifiedMfi = features and AirPlayFeatures.UNIFIED_PAIRING_AND_MFI != 0L
        val standaloneMfi = !unifiedMfi && features and AirPlayFeatures.MFI_SAP != 0L
        return policy.copy(
            authSetupBeforeSession = !unifiedMfi && (standaloneMfi || policy.iphoneAuthSequence),
            rules = if (standaloneMfi) policy.rules + "protocol.mfi-sap-auth-setup" else policy.rules,
        )
    }

    /**
     * 用户在主页手动选择车型后，在自动判定结果之上叠加该车型的适配开关（[VehiclePreset.AUTO] 不改动）。
     *
     * [signals] 用于奔驰尺寸预设的厂商匹配。
     */
    fun applyPreset(
        policy: Policy,
        preset: VehiclePreset,
        signals: ReceiverProfile.Signals,
    ): Policy =
        when (preset) {
            VehiclePreset.AUTO -> policy
            // 通用：关闭所有按车机登记的例外，走协议基线
            VehiclePreset.GENERIC ->
                policy.copy(
                    bluetoothRole = BluetoothRole.KEEP_DURING_BRINGUP,
                    allowBluetoothRetireRetry = false,
                    requiresRealBluetoothIdentity = false,
                    releaseBluetoothAfterSetup = false,
                    reportWifiMacInSetup = false,
                    skipIapTunnel = false,
                    sendAppleClientHeaders = false,
                    iphoneAuthSequence = false,
                    pinnedMfiChallengeLength = null,
                    reconnectOnStreamingInvite = false,
                    usePanelVideoGeometry = false,
                    preferVehicleResolution = false,
                    offerHevc = true,
                    repeatScreenModesDeclaration = false,
                    skipScreenHidArming = false,
                    rebuildScreenOnResourceReturn = false,
                    rules = policy.rules + "manual.generic",
                )
            VehiclePreset.CHERY ->
                policy.copy(
                    releaseBluetoothAfterSetup = true,
                    reportWifiMacInSetup = true,
                    skipIapTunnel = true,
                    sendAppleClientHeaders = true,
                    iphoneAuthSequence = true,
                    pinnedMfiChallengeLength = 32,
                    rules = policy.rules + "manual.chery",
                )
            VehiclePreset.VOLKSWAGEN ->
                policy.copy(reconnectOnStreamingInvite = true, rules = policy.rules + "manual.volkswagen")
            VehiclePreset.WINPLAY ->
                policy.copy(
                    family = Family.WINPLAY,
                    bluetoothRole = BluetoothRole.NETWORK_HANDOFF,
                    requiresRealBluetoothIdentity = true,
                    preferVehicleResolution = true,
                    rules = policy.rules + "manual.winplay",
                )
            VehiclePreset.DIPLAY ->
                policy.copy(family = Family.DIPLAY, allowBluetoothRetireRetry = true, rules = policy.rules + "manual.diplay")
            VehiclePreset.CLASSIC_MIB3 ->
                policy.copy(
                    family = Family.CLASSIC_MIB3,
                    linkGeneration = ReceiverProfile.LinkGeneration.CLASSIC_MIB3,
                    rules = policy.rules + "manual.classic-mib3",
                )
            // 奔驰 MB_E-P / 2025 GLC（issue #62）：CarWith 尺寸协商已完成；当前实车日志显示 20B
            // 隧道挑战被静默 6s，32B 才在约 0.44s 内应答。HyperCar 对同一车机直接发 32B，
            // RECORD 后约 0.5s 完成认证并随后收到 StartHID。固定 32B，避免错过 MBUX 激活窗口。
            // 仍保持 displays[0] 的 1624x1278 与 DISABLE_RESOLUTION_OPTION=1。
            // 已知是别家车机时拒绝套用，避免把实验开关带到其它接收端上。
            VehiclePreset.MERCEDES_MB_E ->
                if (manualMercedesAllowed(signals)) {
                    policy.copy(
                        usePanelVideoGeometry = false,
                        preferVehicleResolution = true,
                        pinnedMfiChallengeLength = 32,
                        absoluteSessionUri = true,
                        offerMainBufferedAudio = false,
                        offerHevc = false,
                        repeatScreenModesDeclaration = true,
                        skipScreenHidArming = true,
                        rebuildScreenOnResourceReturn = true,
                        skipIapTunnel = true,
                        rules =
                            policy.rules + "manual.mercedes-mb-e.vehicle-resolution" +
                                "manual.mercedes-mb-e.mfi-32" +
                                "manual.mercedes-mb-e.absolute-session-uri" +
                                "manual.mercedes-mb-e.no-iap-tunnel" +
                                "manual.mercedes-mb-e.no-main-buffered" +
                                "manual.mercedes-mb-e.no-hevc" +
                                "manual.mercedes-mb-e.repeat-modes" +
                                "manual.mercedes-mb-e.no-hid-arming" +
                                "manual.mercedes-mb-e.screen-rebuild",
                    )
                } else {
                    policy.copy(rules = policy.rules + "manual.mercedes-mb-e-blocked")
                }
        }

    /** 手动奔驰预设的冲突守卫：厂商未知时放行，已知时必须自报 Mercedes-Benz。 */
    private fun manualMercedesAllowed(signals: ReceiverProfile.Signals): Boolean {
        val manufacturer = normalize(signals.manufacturer)
        return manufacturer.isEmpty() || manufacturer == "mercedes-benz"
    }

    /**
     * ORA 03 现场诊断：此固件通过 0x5703 提供热点凭据，未声明 0x4301，
     * 有时不会及时发出 ctrl 邀请。蓝牙与 mDNS 身份均吻合时只需等待首个邀请窗口。
     */
    internal fun prefersOra03DirectDiscovery(
        signals: ReceiverProfile.Signals,
        mdnsModel: String?,
        mdnsFirmware: String?,
        mdnsSourceVersion: String?,
        preset: VehiclePreset,
    ): Boolean =
        preset == VehiclePreset.AUTO &&
            normalize(signals.manufacturer) == "gwm" &&
            normalize(signals.model) == "ora es11" &&
            signals.sendIds?.let { 0x5703 in it && 0x4301 !in it } == true &&
            signals.receiveIds?.contains(0x5702) == true &&
            isOra03Receiver(mdnsModel, mdnsFirmware, mdnsSourceVersion)

    private fun isOra03Receiver(
        model: String?,
        firmware: String?,
        sourceVersion: String?,
    ): Boolean = normalize(model) == "ora es11" && normalize(firmware) == "n220346.2" && sourceVersion == "365.10.2"

    private fun resolveDetected(
        signals: ReceiverProfile.Signals,
        mediaCapabilities: String,
        receiverInstance: String?,
    ): Policy {
        val profile = ReceiverProfile.classify(signals)
        val generation = ReceiverProfile.linkGenerationOf(signals)
        val pairing =
            when (profile.era) {
                ReceiverProfile.Era.PRE_HOMEKIT -> PairingMode.ADVERTISED_PLAIN
                ReceiverProfile.Era.MODERN -> PairingMode.PAIR_AND_ENCRYPT
                else -> PairingMode.PROBE
            }
        val protocols = signals.externalAccessoryProtocols.map(::normalize).toSet()
        val winPlay =
            normalize(signals.model) == "winplay" &&
                normalize(signals.manufacturer) == "roylyl" &&
                "io.github.roylyl.winplay" in protocols
        val diPlay = normalize(signals.name) == "diplay" && "com.shilapi.xcertplay" in protocols
        val family =
            when {
                winPlay -> Family.WINPLAY
                diPlay -> Family.DIPLAY
                generation == ReceiverProfile.LinkGeneration.CLASSIC_MIB3 -> Family.CLASSIC_MIB3
                else -> Family.GENERIC
            }
        // WinPlay 1.2.4 与 1.2.5（13ccb04）的源码和 2026-10-27 实机对照：都按目标蓝牙地址过滤 SETUP、
        // 网络 iAP 认证后释放蓝牙。只登记核对过的固件号，不推断后续版本的交接时序。
        val winPlayFirmware = normalize(signals.firmwareVersion)
        val winPlayHandoff = winPlay && winPlayFirmware in WINPLAY_HANDOFF_FIRMWARE
        // DiPlay v0.2.21 自报 firmware=0.1.0（并非应用版本）。同时核对 EA 与名称，
        // 避免把 CARLINKIT 认证证书或单个显示名当成 DiPlay 软件身份。
        val diPlayWire010 = diPlay && normalize(signals.firmwareVersion) == "0.1.0"
        // 艾瑞泽 8（iAP2 自报 Cloudrive / Tiggo / CHERY）：真 iPhone 会话期间车机蓝牙关闭（2026-11-02 车机日志）
        val cheryReleasesBluetooth = normalize(signals.manufacturer) == "chery"
        val arrizo8ConnectWindowHint =
            cheryReleasesBluetooth && normalize(signals.model) == "tiggo" && normalize(signals.srcvers) == "320.17.7"
        // 大众车机型号自报 VWxxxx（ID7 VW4131、帕萨特 2025 VW4211），厂商字段并不统一（帕萨特报 Toppower）
        val vwStreamingInvite = VW_MODEL.matches(normalize(signals.model))
        val rules =
            buildList {
                add("protocol.${pairing.name.lowercase()}")
                if (winPlayHandoff) add("winplay.$winPlayFirmware.network-handoff")
                if (diPlayWire010) add("diplay.wire-0.1.0.bt-retire-retry")
                if (family == Family.CLASSIC_MIB3) add("iap2.classic-mib3")
                if (winPlay && !winPlayHandoff) add("winplay.unverified-firmware")
                if (diPlay && !diPlayWire010) add("diplay.unverified-firmware")
                if (cheryReleasesBluetooth) add("chery.disable-bluetooth")
                if (cheryReleasesBluetooth) add("chery.real-wifi-mac")
                if (cheryReleasesBluetooth) add("chery.no-iap-tunnel")
                if (cheryReleasesBluetooth) add("chery.apple-client-headers")
                if (cheryReleasesBluetooth) add("chery.iphone-auth-sequence")
                if (cheryReleasesBluetooth) add("chery.mfi-32-only")
                if (vwStreamingInvite) add("vw.streaming-invite-reconnect")
                if (winPlay) add("winplay.vehicle-resolution")
            }
        return Policy(
            family = family,
            pairingMode = pairing,
            bluetoothRole = if (winPlayHandoff) BluetoothRole.NETWORK_HANDOFF else BluetoothRole.KEEP_DURING_BRINGUP,
            linkGeneration = generation,
            allowBluetoothRetireRetry = diPlayWire010,
            requiresRealBluetoothIdentity = winPlayHandoff,
            memoryKey = memoryKey(signals, mediaCapabilities, receiverInstance),
            rules = rules,
            releaseBluetoothAfterSetup = cheryReleasesBluetooth,
            reportWifiMacInSetup = cheryReleasesBluetooth,
            skipIapTunnel = cheryReleasesBluetooth,
            sendAppleClientHeaders = cheryReleasesBluetooth,
            iphoneAuthSequence = cheryReleasesBluetooth,
            pinnedMfiChallengeLength = if (cheryReleasesBluetooth) 32 else null,
            arrizo8ConnectWindowHint = arrizo8ConnectWindowHint,
            reconnectOnStreamingInvite = vwStreamingInvite,
            preferVehicleResolution = winPlay,
        )
    }

    fun bluetoothLossAction(
        policy: Policy,
        videoRunning: Boolean,
        networkHandoffReady: Boolean,
        /** 本会话已请求车机关蓝牙（disableBluetooth）：随后的蓝牙断开是预期结果，不收会话。 */
        bluetoothReleased: Boolean = false,
    ): BluetoothLossAction =
        if (bluetoothReleased || videoRunning || (policy.bluetoothRole == BluetoothRole.NETWORK_HANDOFF && networkHandoffReady)) {
            BluetoothLossAction.KEEP_NETWORK_SESSION
        } else {
            BluetoothLossAction.END_SESSION
        }

    fun pairingFallback(
        policy: Policy,
        keysEstablished: Boolean,
        noPairing: Boolean,
        pairSetupRejected: Boolean,
        pairingForbidden: Boolean,
        attemptedPairing: Boolean,
        pairingUnsupported: Boolean,
        /** 用户开关「配对失败后尝试明文」：恢复旧兜底，403 仍禁止。 */
        allowPlainAfterFailure: Boolean = false,
    ): PairingFallback {
        if (keysEstablished || pairingForbidden) return PairingFallback(false, false)
        return PairingFallback(
            useBtRetireHack = policy.allowBluetoothRetireRetry && !noPairing && !pairSetupRejected && attemptedPairing,
            // 超时/复位不证明支持明文；只有能力明确声明或端点明确不支持配对才允许降级。
            usePlainSession =
                noPairing ||
                    pairingUnsupported ||
                    policy.pairingMode == PairingMode.ADVERTISED_PLAIN ||
                    (allowPlainAfterFailure && attemptedPairing),
        )
    }

    /** 只输出固定策略标签，不写入 SSID、MAC、序列号或原始身份文本。 */
    fun describe(policy: Policy): String =
        "COMPAT: family=${policy.family} pairing=${policy.pairingMode} bluetooth=${policy.bluetoothRole} " +
            "link=${policy.linkGeneration} btRetireRetry=${policy.allowBluetoothRetireRetry} " +
            "vehicleResolution=${policy.preferVehicleResolution} rules=${policy.rules.joinToString(",") }"

    private fun memoryKey(
        signals: ReceiverProfile.Signals,
        mediaCapabilities: String,
        receiverInstance: String?,
    ): String? {
        if (signals.model.isNullOrBlank() || signals.manufacturer.isNullOrBlank() || receiverInstance.isNullOrBlank()) return null
        val values =
            listOf(
                normalize(receiverInstance),
                normalize(signals.manufacturer),
                normalize(signals.model),
                normalize(signals.firmwareVersion),
                signals.features?.toString(16).orEmpty(),
                normalize(signals.srcvers),
                signals.externalAccessoryProtocols
                    .map(::normalize)
                    .sorted()
                    .joinToString(","),
                mediaCapabilities,
            )
        val text = values.joinToString("") { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return "rx1_" + hash.take(20).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** WinPlay iAP2 识别消息自报的固件号（`iap.ts` 硬编码）。 */
    private val WINPLAY_HANDOFF_FIRMWARE = setOf("1.2.4", "1.2.5")

    private val VW_MODEL = Regex("vw\\d{4}")

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase(java.util.Locale.ROOT)
}
