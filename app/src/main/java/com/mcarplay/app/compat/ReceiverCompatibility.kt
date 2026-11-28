package com.mcarplay.app.compat

import com.mcarplay.app.iap2.Iap2LinkEngine
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
         * 2026-11-02 艾瑞泽 8 车机日志：真 iPhone 每次会话开始约 0.1–0.6 s 都发，但已排除与断开后
         * 20 s 原厂 `_PerformPeriodTasks` 崩溃的因果关系；且不主动断开直接熄火下车时，车机休眠前
         * 未走完 `setBtEnable` 会导致下次上车蓝牙保持关闭。奇瑞默认关闭，仅用户显式打开对应开关时发送。
         */
        val releaseBluetoothAfterSetup: Boolean = false,
        /**
         * SETUP `macAddress` 填本机在车机热点上的真实 Wi-Fi 地址（iPhone 同款）。艾瑞泽 8 把它记为
         * `mAutoReConnectMacAddress`；mcarplay 原先填占位 `aa:bb:cc:dd:ee:ff`（2026-11-02 车机日志）。
         */
        val reportWifiMacInSetup: Boolean = false,
        /**
         * 不 SETUP type-130 iAP 隧道。艾瑞泽 8 对它报 `Unsupported stream type: 130`，真 iPhone 从不请求，
         * 隧道在该车机上本就不可用（2026-11-02 车机日志）。去掉后断开后约 20 s 的原厂崩溃仍在，二者无关。
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
         * 出画协商策略：声明 `DISABLE_RESOLUTION_OPTION=1` 时，CarWith 的 `0x18007` 宽高按车机
         * `/info displays[0]` 几何生效，而不是被映射回 CarWith 自己的分辨率档位。
         *
         * issue #127：车机主屏几何超过默认 1920 长边上限（如 2560x1200）时，压小后的请求
         * 可能不被采纳，CarWith 回退到自己的档位后车机不出画。因此自动判定对**所有接收端**
         * 都打开本策略，但**是否真的声明**由流程按本轮 `/info` 几何再门控（见
         * `CarWithVideoNegotiation`）：只有 displays[0] 给出合法几何时才声明并精确请求，
         * 几何缺失/非法时回退 1920 长边请求且不声明该 key。手动「通用」预设会显式关闭。
         *
         * 本字段只表达策略，不直接决定帧尺寸；不要按车型名在流程代码里分支。
         */
        val preferVehicleResolution: Boolean = false,
        /**
         * 回屏策略（原帕萨特「回主界面再进」实验）。只由用户在主页「车型适配」**手动**选择大众时经
         * [withScreenReturnPreference] 读取偏好叠加；[VehiclePreset.AUTO] 自动识别到大众也不启用。
         * 默认 [ScreenReturnStrategy.OFF]（2026-11-05：三种做法都没有实车证据，见 docs/CARWITH-ENTRY-FINDINGS.md）。
         */
        val screenReturn: ScreenReturnStrategy = ScreenReturnStrategy.OFF,
        /** 手动车型实验覆盖的首个 iAP2 SYN 发送窗口；协议默认值为 4。 */
        val iap2InitialMaxOutgoing: Int = Iap2LinkEngine.DEFAULT_MAX_OUTGOING,
        /** 手动实验预设要求先等到对端 marker 时，是否暂缓发起本方 SYN。 */
        val waitForIap2PeerMarker: Boolean = false,
        /**
         * 车机在初始 AirPlay SETUP 写出后主动关闭蓝牙，并用已建立的 AirPlay TCP 继续会话。
         *
         * 2023 款传祺 GS3 的首轮实车日志中，RFCOMM 比 `SETUP 200` 早约 110 ms 关闭；
         * 仅等待 [com.mcarplay.app.airplay.AirPlayClient.sessionSetupAccepted] 会错过交接窗口。
         * 只允许手动 GS3 预设启用，其它接收端继续按原蓝牙断开策略收尾。
         */
        val keepNetworkSessionDuringSetupHandoffOnBluetoothLoss: Boolean = false,
        /**
         * 用户明确发起连接时，是否允许在初始 `/info` 车机持有主屏且约束不高于 userInitiated 时
         * 由手机接管主屏。
         *
         * 2023 款传祺 GS3 影速偶发白屏（2026-11-26 报告 `36a2e771`）：CarWith 持续出帧、音频始终
         * 正常，但 `/info modes` 以 500/500 把主屏交给车机，[com.mcarplay.app.video.ScreenOutputGate]
         * 因主屏不归手机连续跳过 3016 / 2796 帧。车机之后发 `requestUI` 时现有请求路径能正常恢复，
         * 故此开关只在用户点击连接后的首轮协商补一次同语义的主屏接管，且始终不接管主音频。
         * 自动重连、CarWith 主动返回车机和 501/1000 约束保持原行为。
         */
        val claimScreenOnExplicitStart: Boolean = false,
        /**
         * 会话建立后主动请求车机切到 CarPlay UI。
         *
         * Bicv MICA3A01 的 `/info` 把主屏的 `initialPermanentEntity` 固定报为车机（resourceID=1），
         * 且不主动发 `requestUI`。按 VW 的“车机 userInitiated 持有”处理会让屏幕门控一直暂停；
         * 这份证据链路全通、主屏 SETUP/RECORD 都成功，因此只对该已登记身份先发 requestUI，
         * 再按用户点图标的同一条资源拿回路径声明主屏归手机。未实车回归前不影响其他车机。
         */
        val autoRequestCarplayUi: Boolean = false,
        /**
         * 实验：首帧即走手机端转码（Baseline + 按车机 `maxFPS` 限帧），不等 `forceKeyFrame` 风暴。
         *
         * 上汽荣威（`SAIC / ROEWE / 490.0`）实车白屏排查：声音正常、几何 `2560x1200` 匹配，但 CarWith
         * 透传输出 High Profile + CABAC + Level 5.1 且实测 53–57 fps（超 `maxFPS=30`），车机不发
         * `forceKeyFrame` 且停在白屏占位层。仅对该身份启用单变量验证，未实车复测前不影响其他车机。
         */
        val transcodeFromStart: Boolean = false,
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
        cheryDisableBluetooth: Boolean = CompatibilityPrefs.cheryDisableBluetoothRuntime,
    ): Policy {
        val policy =
            applyPreset(
                resolveDetected(signals, mediaCapabilities, receiverInstance, cheryDisableBluetooth),
                preset,
                signals,
                cheryDisableBluetooth,
            )
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
        cheryDisableBluetooth: Boolean = CompatibilityPrefs.cheryDisableBluetoothRuntime,
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
                    iap2InitialMaxOutgoing = Iap2LinkEngine.DEFAULT_MAX_OUTGOING,
                    waitForIap2PeerMarker = false,
                    keepNetworkSessionDuringSetupHandoffOnBluetoothLoss = false,
                    claimScreenOnExplicitStart = false,
                    autoRequestCarplayUi = false,
                    transcodeFromStart = false,
                    screenReturn = ScreenReturnStrategy.OFF,
                    rules = policy.rules + "manual.generic",
                )
            VehiclePreset.CHERY ->
                policy.copy(
                    releaseBluetoothAfterSetup = cheryDisableBluetooth,
                    reportWifiMacInSetup = true,
                    skipIapTunnel = true,
                    sendAppleClientHeaders = true,
                    iphoneAuthSequence = true,
                    pinnedMfiChallengeLength = 32,
                    rules =
                        policy.rules +
                            buildList {
                                if (cheryDisableBluetooth && "chery.disable-bluetooth" !in policy.rules) {
                                    add("chery.disable-bluetooth")
                                }
                                add("manual.chery")
                            },
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
            // 2023 款传祺 GS3 影速：先等车机 marker，再用 0x7F 窗口发起 iAP2。
            VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW ->
                policy.copy(
                    iap2InitialMaxOutgoing = Iap2LinkEngine.MAX_OUTGOING_LIMIT,
                    waitForIap2PeerMarker = true,
                    keepNetworkSessionDuringSetupHandoffOnBluetoothLoss = true,
                    claimScreenOnExplicitStart = true,
                    rules =
                        policy.rules +
                            listOf(
                                "manual.trumpchi-gs3.iap2-marker-first",
                                "manual.trumpchi-gs3.explicit-start-screen-claim",
                            ),
                )
            // 奔驰 MB_E-P / 2025 GLC（issue #62）：先验证「CarWith 是否按车机尺寸出流」。
            // HyperCar 日志已给出同车机的 A/B：请求 1624x1278 时，未声明 DISABLE_RESOLUTION_OPTION
            // 会被 CarWith 回成 1280x720，声明后才返回 1624x1278。因此当前预设不再使用 1728 面板，
            // 保持 displays[0] 的 1624x1278，并只打开功能协商这一条改动。
            // issue #127 起该策略已是所有接收端的自动策略；这里保留显式置位，确保手动选此预设
            // 在自动判定未打开时仍然生效。
            // 已知是别家车机时拒绝套用，避免把实验开关带到其它接收端上。
            VehiclePreset.MERCEDES_MB_E ->
                if (manualMercedesAllowed(signals)) {
                    policy.copy(
                        usePanelVideoGeometry = false,
                        preferVehicleResolution = true,
                        rules = policy.rules + "manual.mercedes-mb-e.vehicle-resolution",
                    )
                } else {
                    policy.copy(rules = policy.rules + "manual.mercedes-mb-e-blocked")
                }
        }

    /**
     * 把用户回屏偏好叠加到 [policy] 的 [Policy.screenReturn]：只有主页「车型适配」**手动**选择
     * [VehiclePreset.VOLKSWAGEN] 时才调用 [readPreference]，其它选择（含 [VehiclePreset.AUTO]，
     * 即使自动识别到 `VWxxxx`）一律 [ScreenReturnStrategy.OFF]，且不触碰偏好读取。
     *
     * 资格只取自 [preset] 这一次读取，不从 [policy] 的 family、rules、邀请重连或制造商推导。
     * [readPreference] 在调用本函数时求值，调用方负责在原动态读取时点调用，不能提前缓存。
     */
    fun withScreenReturnPreference(
        policy: Policy,
        preset: VehiclePreset,
        readPreference: () -> ScreenReturnStrategy,
    ): Policy =
        policy.copy(
            screenReturn =
                if (preset == VehiclePreset.VOLKSWAGEN) {
                    readPreference()
                } else {
                    policy.screenReturn
                },
        )

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
    ): Boolean = normalize(model) == "ora es11" && normalize(firmware) == "n220346.2" && normalize(sourceVersion) == "365.10.2"

    internal enum class ReceiverIdentity { EXPECTED, GENERIC, OTHER }

    /** ORA 03, November 2026: one known firmware can advertise a generic identity after a failed handoff. */
    internal fun classifyReceiverIdentity(
        manufacturer: String?,
        model: String?,
        firmware: String?,
        sourceVersion: String?,
    ): ReceiverIdentity =
        when {
            normalize(manufacturer) == "gwm" && isOra03Receiver(model, firmware, sourceVersion) -> ReceiverIdentity.EXPECTED
            normalize(manufacturer) == "oem" &&
                normalize(model) == "oem model" &&
                normalize(firmware) == "0.0.1" &&
                normalize(sourceVersion) == "365.10.2" -> ReceiverIdentity.GENERIC
            else -> ReceiverIdentity.OTHER
        }

    internal data class ReceiverIdentityDecision(
        val showWarning: Boolean,
        val rememberExpected: Boolean,
    )

    /** A missing or different identity clears the current warning, not the remembered confirmation. */
    internal fun receiverIdentityDecision(
        known: Boolean,
        identity: ReceiverIdentity?,
    ): ReceiverIdentityDecision =
        ReceiverIdentityDecision(
            showWarning = known && identity == ReceiverIdentity.GENERIC,
            rememberExpected = !known && identity == ReceiverIdentity.EXPECTED,
        )

    private fun resolveDetected(
        signals: ReceiverProfile.Signals,
        mediaCapabilities: String,
        receiverInstance: String?,
        cheryDisableBluetooth: Boolean,
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
        // 艾瑞泽 8（iAP2 自报 Cloudrive / Tiggo / CHERY）：真 iPhone 会话期间会发 disableBluetooth 关闭车机蓝牙，
        // 但直接熄火下车时车机可能来不及 setBtEnable，导致下次上车蓝牙保持关闭；默认不发，仅开关打开时启用。
        val chery = normalize(signals.manufacturer) == "chery"
        val cheryReleasesBluetooth = chery && cheryDisableBluetooth
        val arrizo8ConnectWindowHint =
            chery && normalize(signals.model) == "tiggo" && normalize(signals.srcvers) == "320.17.7"
        val bicvGseEv3 =
            normalize(signals.manufacturer) == "bicv" &&
                normalize(signals.model) == "gse,ev3,suv" &&
                normalize(signals.srcvers) == "475.8.3" &&
                normalize(signals.firmwareVersion) == "mica3a01"
        val normalizedManufacturer = normalize(signals.manufacturer)
        val roewe490 =
            (normalizedManufacturer == "saic" || normalizedManufacturer.startsWith("saicmotor")) &&
                normalize(signals.model) == "roewe" &&
                normalize(signals.srcvers) == "490.0"
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
                if (chery) add("chery.real-wifi-mac")
                if (chery) add("chery.no-iap-tunnel")
                if (chery) add("chery.apple-client-headers")
                if (chery) add("chery.iphone-auth-sequence")
                if (chery) add("chery.mfi-32-only")
                if (vwStreamingInvite) add("vw.streaming-invite-reconnect")
                if (winPlay) add("winplay.vehicle-resolution")
                if (bicvGseEv3) add("bicv.gse-ev3.475.8.3.auto-request-ui")
                if (roewe490) add("saic.roewe.490.0.transcode-from-start")
                if (roewe490) add("saic.roewe.490.0.no-screen-rebuild")
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
            reportWifiMacInSetup = chery,
            skipIapTunnel = chery,
            sendAppleClientHeaders = chery,
            iphoneAuthSequence = chery,
            pinnedMfiChallengeLength = if (chery) 32 else null,
            arrizo8ConnectWindowHint = arrizo8ConnectWindowHint,
            reconnectOnStreamingInvite = vwStreamingInvite,
            // issue #127：出画协商不再限定 WinPlay/奔驰例外。任何接收端都可能出现主屏几何
            // 超出默认 1920 长边上限（2560x1200 等），统一打开策略；实际是否声明由流程按
            // 本轮 /info displays[0] 的合法几何门控（CarWithVideoNegotiation）。
            preferVehicleResolution = true,
            autoRequestCarplayUi = bicvGseEv3,
            transcodeFromStart = roewe490,
            screenReturn = if (roewe490) ScreenReturnStrategy.NO_REBUILD else ScreenReturnStrategy.OFF,
        )
    }

    fun bluetoothLossAction(
        policy: Policy,
        videoRunning: Boolean,
        networkHandoffReady: Boolean,
        /** 本会话已请求车机关蓝牙（disableBluetooth）：随后的蓝牙断开是预期结果，不收会话。 */
        bluetoothReleased: Boolean = false,
        /** GS3 初始 SETUP 已写出但应答尚未返回时，车机可能先关闭蓝牙。 */
        setupHandoffStarted: Boolean = false,
    ): BluetoothLossAction =
        if (bluetoothReleased ||
            videoRunning ||
            (policy.bluetoothRole == BluetoothRole.NETWORK_HANDOFF && networkHandoffReady) ||
            (policy.keepNetworkSessionDuringSetupHandoffOnBluetoothLoss && setupHandoffStarted)
        ) {
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
