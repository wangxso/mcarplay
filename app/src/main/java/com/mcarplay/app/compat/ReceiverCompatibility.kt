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
    )

    data class PairingFallback(
        val useBtRetireHack: Boolean,
        val usePlainSession: Boolean,
    )

    fun resolve(
        signals: ReceiverProfile.Signals,
        mediaCapabilities: String = "",
        receiverInstance: String? = null,
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
        // WinPlay V1.2.4 的源码与 2026-10-27 开关对照；不推断后续版本的交接时序。
        val winPlay124 = winPlay && normalize(signals.firmwareVersion) == "1.2.4"
        // DiPlay v0.2.21 自报 firmware=0.1.0（并非应用版本）。同时核对 EA 与名称，
        // 避免把 CARLINKIT 认证证书或单个显示名当成 DiPlay 软件身份。
        val diPlayWire010 = diPlay && normalize(signals.firmwareVersion) == "0.1.0"
        val rules =
            buildList {
                add("protocol.${pairing.name.lowercase()}")
                if (winPlay124) add("winplay.1.2.4.network-handoff")
                if (diPlayWire010) add("diplay.wire-0.1.0.bt-retire-retry")
                if (family == Family.CLASSIC_MIB3) add("iap2.classic-mib3")
                if (winPlay && !winPlay124) add("winplay.unverified-firmware")
                if (diPlay && !diPlayWire010) add("diplay.unverified-firmware")
            }
        return Policy(
            family = family,
            pairingMode = pairing,
            bluetoothRole = if (winPlay124) BluetoothRole.NETWORK_HANDOFF else BluetoothRole.KEEP_DURING_BRINGUP,
            linkGeneration = generation,
            allowBluetoothRetireRetry = diPlayWire010,
            requiresRealBluetoothIdentity = winPlay124,
            memoryKey = memoryKey(signals, mediaCapabilities, receiverInstance),
            rules = rules,
        )
    }

    fun bluetoothLossAction(
        policy: Policy,
        videoRunning: Boolean,
        networkHandoffReady: Boolean,
    ): BluetoothLossAction =
        if (videoRunning || (policy.bluetoothRole == BluetoothRole.NETWORK_HANDOFF && networkHandoffReady)) {
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
    ): PairingFallback {
        if (keysEstablished || pairingForbidden) return PairingFallback(false, false)
        return PairingFallback(
            useBtRetireHack = policy.allowBluetoothRetireRetry && !noPairing && !pairSetupRejected && attemptedPairing,
            // 超时/复位不证明支持明文；只有能力明确声明或端点明确不支持配对才允许降级。
            usePlainSession = noPairing || pairingUnsupported || policy.pairingMode == PairingMode.ADVERTISED_PLAIN,
        )
    }

    /** 只输出固定策略标签，不写入 SSID、MAC、序列号或原始身份文本。 */
    fun describe(policy: Policy): String =
        "COMPAT: family=${policy.family} pairing=${policy.pairingMode} bluetooth=${policy.bluetoothRole} " +
            "link=${policy.linkGeneration} btRetireRetry=${policy.allowBluetoothRetireRetry} rules=${policy.rules.joinToString(",") }"

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

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase(java.util.Locale.ROOT)
}
