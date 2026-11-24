package com.mcarplay.app.compat

import com.mcarplay.app.iap2.ReceiverProfile
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
         * 隧道在该车机上本就不可用（2026-11-02 车机日志）。去掉后断开后约 20 s 的原厂崩溃仍在，二者无关。
         */
        val skipIapTunnel: Boolean = false,
        /**
         * 控制通道请求带 `X-Apple-Client-Name` / `X-Apple-ProtocolVersion`（车机 AirPlay 库含这两个头名）。
         * 艾瑞泽 8 对 iPhone 记 `session started: From=iPhone`，对 mcarplay 为空 `From=`（2026-11-02）。
         */
        val sendAppleClientHeaders: Boolean = false,
        /**
         * 按 iPhone 的配对与认证顺序：pair-setup/pair-verify 带 `X-Apple-HKP: 0`（车机对 iPhone 记 `pair-setup HK, type 0`，
         * 对 mcarplay 的 `2` 记 `type 2`），pair-verify 后、SETUP 前发 `POST /auth-setup`（车机对 iPhone 记 `MFiSAP_Exchange`，
         * 对 mcarplay 从未出现）。2026-11-02 艾瑞泽 8 车机日志。
         */
        val iphoneAuthSequence: Boolean = false,
        /**
         * 蓝牙与网络隧道 iAP2 的 MFi 挑战都固定为该长度、只发一次、不学习（null = 按记忆轮换）。艾瑞泽 8 为 MFi Auth 3.0 芯片
         * （32 字节摘要），真 iPhone 每路认证一次 0.45 s 通过；mcarplay 发 20/16B 时车机芯片挂起（I2C `error -110`、认证线程
         * `sem_wait` 无返回），后续挑战排队，会话结束仍有认证挂起；长度记忆还曾把 32B 误记为被拒（2026-11-02 车机日志）。
         */
        val pinnedMfiChallengeLength: Int? = null,
        /**
         * 对已取得日志证据的车机栈，同一会话只发送一次 RECORD，避免重复启动遗留定时器或触发拆链。
         * 当前覆盖艾瑞泽 8（CHERY / Tiggo）与 issue #55 报告中的奔驰 MB_D-PP-3/4，均限定 AirPlay 320.17.7。
         */
        val singleRecordPerSession: Boolean = false,
        /** ORA 03 时序验证：初始音频加入时沿用首个 RECORD，手动补启音频仍可激活。 */
        val skipRecordAfterInitialAudioSetup: Boolean = false,
        /**
         * 连接等待超过 30 s 时显示艾瑞泽 8 专属提示（该车机此时会自行重置并重新发起）。只影响界面文案，
         * 与 [singleRecordPerSession] 同一份 CHERY/Tiggo/320.17.7 身份判定；手动车型不改变它（车还是那台车）。
         */
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
        airPlayFirmware: String? = null,
    ): Policy = applyPreset(resolveDetected(signals, mediaCapabilities, receiverInstance, airPlayFirmware), preset, signals)

    /**
     * 用户在主页手动选择车型后，在自动判定结果之上叠加该车型的适配开关（[VehiclePreset.AUTO] 不改动）。
     *
     * [signals] 只用于**冲突守卫**：手动预设是为「自动判定认不出」准备的，不能把例外套到另一台
     * 已知厂牌的车机上。例如选了奔驰 MB_D-PP 预设但车机自报 `CHERY`，则保持原策略并只记录规则，
     * 不启用单次 RECORD。
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
                    singleRecordPerSession = false,
                    skipRecordAfterInitialAudioSetup = false,
                    reconnectOnStreamingInvite = false,
                    usePanelVideoGeometry = false,
                    preferVehicleResolution = false,
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
            // 奔驰 MB_D-PP（issue #55）：只登记单次 RECORD；不附带 Chery 的蓝牙/MFi 例外。
            // 身份为空 = 自动判定拿不到型号/厂商，正是手动预设的用途；已知是别家车机则拒绝套用。
            VehiclePreset.MERCEDES_MB_DPP ->
                if (manualMercedesAllowed(signals)) {
                    policy.copy(
                        singleRecordPerSession = true,
                        rules = policy.rules + "manual.mercedes-mb-dpp",
                    )
                } else {
                    policy.copy(rules = policy.rules + "manual.mercedes-mb-dpp-blocked")
                }
            // 奔驰 MB_E-P / 2025 GLC（issue #62）：先验证「CarWith 是否按车机尺寸出流」。
            // HyperCar 日志已给出同车机的 A/B：请求 1624x1278 时，未声明 DISABLE_RESOLUTION_OPTION
            // 会被 CarWith 回成 1280x720，声明后才返回 1624x1278。因此当前预设不再使用 1728 面板，
            // 保持 displays[0] 的 1624x1278，并只打开功能协商这一条改动。
            // 不附带 MB_D-PP 的单次 RECORD（那是另一代际、另一份报告的证据）。
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
                sourceVersion == "365.10.2" -> ReceiverIdentity.GENERIC
            else -> ReceiverIdentity.OTHER
        }

    private fun resolveDetected(
        signals: ReceiverProfile.Signals,
        mediaCapabilities: String,
        receiverInstance: String?,
        airPlayFirmware: String?,
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
        // 只登记已取得原厂库和崩溃证据的协议身份；srcvers 是 AirPlay 栈版本，不等于整车固件。
        val cherySingleRecord =
            cheryReleasesBluetooth && normalize(signals.model) == "tiggo" && normalize(signals.srcvers) == "320.17.7"
        // 奔驰 MB_D-PP-3/4（issue #55 车主报告：iAP2 自报 Mercedes-Benz / MB_D-PP-3，/info 为 MB_D-PP-4）：
        // 音频 SETUP 后补发 RECORD，16–559 ms 内屏幕口 EOF/reset；同一报告里不补发 RECORD 的视频会话正常。
        // 只登记报告实际出现的型号与 AirPlay 栈版本，不把其他 320.17.7 接收端一并纳入。
        val mercedesSingleRecord =
            normalize(signals.manufacturer) == "mercedes-benz" &&
                normalize(signals.model) in MERCEDES_D_PP_MODELS &&
                normalize(signals.srcvers) == "320.17.7"
        // ORA 03 重连时音频 SETUP 后立即补发 RECORD，约 6 s 后车机拆链。
        // 同一车机较晚手动开启音频时补发 RECORD 有成功记录，故只调整初始连接。
        val oraSkipInitialAudioRecord =
            normalize(signals.manufacturer) == "gwm" &&
                isOra03Receiver(signals.model, airPlayFirmware, signals.srcvers)
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
                if (cherySingleRecord) add("chery.tiggo.320.17.7.single-record")
                if (mercedesSingleRecord) add("mercedes.mb_d-pp.320.17.7.single-record")
                if (oraSkipInitialAudioRecord) add("ora.n220346.2.no-initial-audio-record")
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
            singleRecordPerSession = cherySingleRecord || mercedesSingleRecord,
            skipRecordAfterInitialAudioSetup = oraSkipInitialAudioRecord,
            arrizo8ConnectWindowHint = cherySingleRecord,
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

    /** issue #55 报告中同一台奔驰的 iAP2（MB_D-PP-3）与 `/info`（MB_D-PP-4）自报型号。 */
    private val MERCEDES_D_PP_MODELS = setOf("mb_d-pp-3", "mb_d-pp-4")

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase(java.util.Locale.ROOT)
}
