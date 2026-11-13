package com.mcarplay.app.compat

import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.net.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverCompatibilityTest {
    private val winPlay =
        ReceiverProfile.Signals(
            name = "WinPlay",
            model = "WinPlay",
            manufacturer = "Roylyl",
            firmwareVersion = "1.2.4",
            features = AirPlayFeatures.HK_PAIRING_AND_ENCRYPT,
            externalAccessoryProtocols = setOf("io.github.roylyl.winplay"),
        )
    private val diPlay =
        ReceiverProfile.Signals(
            name = "DiPlay",
            model = "custom-hu",
            manufacturer = "custom-maker",
            firmwareVersion = "0.1.0",
            externalAccessoryProtocols = setOf("com.shilapi.xcertplay"),
        )
    private val mib3 =
        ReceiverProfile.Signals(
            model = "AUDI 5163",
            manufacturer = "DELPHI",
            sendIds = setOf(0x4301, 0xea03),
            receiveIds = setOf(0x5703, 0x4e09, 0x4e0b, 0x4e0d, 0x4e0e),
        )

    @Test
    fun receiverMatrixKeepsRulesSeparate() {
        val windows = ReceiverCompatibility.resolve(winPlay)
        assertEquals(ReceiverCompatibility.Family.WINPLAY, windows.family)
        assertEquals(ReceiverCompatibility.BluetoothRole.NETWORK_HANDOFF, windows.bluetoothRole)
        assertTrue(windows.requiresRealBluetoothIdentity)
        assertFalse(windows.allowBluetoothRetireRetry)
        assertTrue(windows.rules.contains("winplay.1.2.4.network-handoff"))
        val windows125 = ReceiverCompatibility.resolve(winPlay.copy(firmwareVersion = "1.2.5"))
        assertEquals(ReceiverCompatibility.BluetoothRole.NETWORK_HANDOFF, windows125.bluetoothRole)
        assertTrue(windows125.requiresRealBluetoothIdentity)
        assertTrue(windows125.rules.contains("winplay.1.2.5.network-handoff"))
        assertFalse(windows125.rules.contains("winplay.unverified-firmware"))
        val android = ReceiverCompatibility.resolve(diPlay)
        assertEquals(ReceiverCompatibility.Family.DIPLAY, android.family)
        assertTrue(android.allowBluetoothRetireRetry)
        assertEquals(ReceiverCompatibility.Family.CLASSIC_MIB3, ReceiverCompatibility.resolve(mib3).family)
        assertFalse(ReceiverCompatibility.resolve(mib3).allowBluetoothRetireRetry)
    }

    @Test
    fun namesAndUnverifiedVersionsCannotEnableDestructiveExceptions() {
        val spoofed = ReceiverCompatibility.resolve(diPlay.copy(externalAccessoryProtocols = emptySet()))
        assertEquals(ReceiverCompatibility.Family.GENERIC, spoofed.family)
        assertFalse(spoofed.allowBluetoothRetireRetry)
        assertFalse(ReceiverCompatibility.resolve(diPlay.copy(firmwareVersion = "0.2.0")).allowBluetoothRetireRetry)
        val nextWindows = ReceiverCompatibility.resolve(winPlay.copy(firmwareVersion = "1.3.0"))
        assertEquals(ReceiverCompatibility.BluetoothRole.KEEP_DURING_BRINGUP, nextWindows.bluetoothRole)
        assertTrue(nextWindows.rules.contains("winplay.unverified-firmware"))
    }

    @Test
    fun protocolCapabilitiesTakePrecedenceOverReceiverNameAndStackVersion() {
        val plain = ReceiverCompatibility.resolve(winPlay.copy(features = 0, srcvers = "950.7.1"))
        assertEquals(ReceiverCompatibility.PairingMode.ADVERTISED_PLAIN, plain.pairingMode)
        val modernOldVersion = ReceiverCompatibility.resolve(winPlay.copy(srcvers = "210.81"))
        assertEquals(ReceiverCompatibility.PairingMode.PAIR_AND_ENCRYPT, modernOldVersion.pairingMode)
        assertEquals(ReceiverCompatibility.PairingMode.PROBE, ReceiverCompatibility.resolve(ReceiverProfile.Signals()).pairingMode)
    }

    @Test
    fun liveMediaSurvivesBluetoothLossForEveryFamily() {
        listOf(winPlay, diPlay, mib3, ReceiverProfile.Signals()).forEach { signals ->
            val action = ReceiverCompatibility.bluetoothLossAction(ReceiverCompatibility.resolve(signals), true, false)
            assertEquals(ReceiverCompatibility.BluetoothLossAction.KEEP_NETWORK_SESSION, action)
        }
    }

    @Test
    fun onlyConfirmedBootstrapReceiverCanReleaseBluetoothBeforeVideoStarts() {
        listOf(diPlay, mib3, ReceiverProfile.Signals()).forEach { signals ->
            val action = ReceiverCompatibility.bluetoothLossAction(ReceiverCompatibility.resolve(signals), false, true)
            assertEquals(ReceiverCompatibility.BluetoothLossAction.END_SESSION, action)
        }
        val windows = ReceiverCompatibility.resolve(winPlay)
        assertEquals(
            ReceiverCompatibility.BluetoothLossAction.KEEP_NETWORK_SESSION,
            ReceiverCompatibility.bluetoothLossAction(windows, false, true),
        )
        assertEquals(
            ReceiverCompatibility.BluetoothLossAction.END_SESSION,
            ReceiverCompatibility.bluetoothLossAction(windows, false, false),
        )
    }

    /** 艾瑞泽 8（iAP2 自报 Cloudrive / Tiggo / CHERY）：真 iPhone 每次会话都请车机关蓝牙（2026-11-02）。 */
    @Test
    fun onlyCheryReleasesBluetoothAfterSetup() {
        val chery = ReceiverProfile.Signals(name = "Cloudrive", model = "Tiggo", manufacturer = "CHERY")
        val policy = ReceiverCompatibility.resolve(chery)
        assertTrue(policy.releaseBluetoothAfterSetup)
        assertTrue("chery.disable-bluetooth" in policy.rules)
        assertTrue(policy.reportWifiMacInSetup)
        assertTrue("chery.real-wifi-mac" in policy.rules)
        assertTrue(policy.skipIapTunnel)
        assertTrue("chery.no-iap-tunnel" in policy.rules)
        assertTrue(policy.sendAppleClientHeaders)
        assertTrue("chery.apple-client-headers" in policy.rules)
        assertTrue(policy.iphoneAuthSequence)
        assertTrue("chery.iphone-auth-sequence" in policy.rules)
        assertEquals(32, policy.pinnedMfiChallengeLength)
        assertTrue("chery.mfi-32-only" in policy.rules)
        listOf(winPlay, diPlay, mib3, ReceiverProfile.Signals(), ReceiverProfile.Signals(name = "CHERY")).forEach { signals ->
            val other = ReceiverCompatibility.resolve(signals)
            assertFalse(other.releaseBluetoothAfterSetup)
            assertFalse(other.reportWifiMacInSetup)
            assertFalse(other.skipIapTunnel)
            assertFalse(other.sendAppleClientHeaders)
            assertFalse(other.iphoneAuthSequence)
            assertNull(other.pinnedMfiChallengeLength)
        }
    }

    @Test
    fun requestedBluetoothReleaseKeepsSessionBeforeVideoStarts() {
        val policy = ReceiverCompatibility.resolve(ReceiverProfile.Signals(manufacturer = "CHERY"))
        assertEquals(
            ReceiverCompatibility.BluetoothLossAction.KEEP_NETWORK_SESSION,
            ReceiverCompatibility.bluetoothLossAction(policy, false, false, bluetoothReleased = true),
        )
        assertEquals(
            ReceiverCompatibility.BluetoothLossAction.END_SESSION,
            ReceiverCompatibility.bluetoothLossAction(policy, false, false, bluetoothReleased = false),
        )
    }

    @Test
    fun singleRecordIsLimitedToTheObservedCheryStack() {
        val observed = ReceiverProfile.Signals(manufacturer = "CHERY", model = "Tiggo", srcvers = "320.17.7")
        val policy = ReceiverCompatibility.resolve(observed)
        assertTrue(policy.singleRecordPerSession)
        assertTrue("chery.tiggo.320.17.7.single-record" in policy.rules)
        listOf(
            observed.copy(manufacturer = "OTHER"),
            observed.copy(model = "OTHER"),
            observed.copy(srcvers = "320.17.8"),
            observed.copy(srcvers = null),
            mib3,
        ).forEach { assertFalse(ReceiverCompatibility.resolve(it).singleRecordPerSession) }
    }

    @Test
    fun mercedesSingleRecordIsLimitedToTheReportedIdentity() {
        // issue #55：iAP2 自报 model=MB_D-PP-3，`/info` 为 MB_D-PP-4，srcvers 均为 320.17.7。
        val iap2 = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_D-PP-3", srcvers = "320.17.7")
        val info = iap2.copy(model = "MB_D-PP-4")
        for (observed in listOf(iap2, info, iap2.copy(manufacturer = " mercedes-benz ", model = " mb_d-pp-4 "))) {
            val policy = ReceiverCompatibility.resolve(observed)
            assertTrue("${observed.model} 应命中单次 RECORD", policy.singleRecordPerSession)
            assertTrue("mercedes.mb_d-pp.320.17.7.single-record" in policy.rules)
            assertFalse("单次 RECORD 不应扩大到其他 Chery 专属规则", policy.arrizo8ConnectWindowHint)
        }
        listOf(
            iap2.copy(manufacturer = "OTHER"),
            iap2.copy(model = "MB_D-PP-5"),
            iap2.copy(srcvers = "320.17.8"),
            iap2.copy(srcvers = null),
            iap2.copy(manufacturer = null),
            ReceiverProfile.Signals(model = "VW4131", manufacturer = "Volkswagen", srcvers = "320.17.7"),
        ).forEach {
            assertFalse(
                "${it.manufacturer}/${it.model}/${it.srcvers} 不应命中奔驰单次 RECORD",
                ReceiverCompatibility.resolve(it).singleRecordPerSession,
            )
        }
        // 手动选择「通用」关闭全部按车机登记的例外（与艾瑞泽 8 一致）。
        assertFalse(ReceiverCompatibility.resolve(iap2, preset = VehiclePreset.GENERIC).singleRecordPerSession)
    }

    @Test
    fun manualMercedesPresetIsGuardedByReportedManufacturer() {
        // 手动预设只补「自动判定认不出」的部分，不能改写别家车机，也不能伪造 BMW 例外。
        val cheryOther = ReceiverProfile.Signals(manufacturer = "CHERY", model = "Tiggo", srcvers = "320.18.1")
        val blocked = ReceiverCompatibility.resolve(cheryOther, preset = VehiclePreset.MERCEDES_MB_DPP)
        assertFalse("手动奔驰预设不得套到奇瑞", blocked.singleRecordPerSession)
        assertTrue("manual.mercedes-mb-dpp-blocked" in blocked.rules)
        // 预设是叠加而非覆盖：已被自动规则命中的奇瑞仍保留自己的单次 RECORD。
        val cheryObserved = cheryOther.copy(srcvers = "320.17.7")
        assertTrue(ReceiverCompatibility.resolve(cheryObserved, preset = VehiclePreset.MERCEDES_MB_DPP).singleRecordPerSession)
        // 身份为空是手动预设的正当用途：允许补上单次 RECORD。
        val blank = ReceiverCompatibility.resolve(ReceiverProfile.Signals(), preset = VehiclePreset.MERCEDES_MB_DPP)
        assertTrue(blank.singleRecordPerSession)
        // 已知 Mercedes-Benz 但型号/固件未登记时由手动预设补齐；不能顺带启用 Chery 的蓝牙/MFi 规则。
        val unrecognized = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_D-PP-9", srcvers = "320.18.1")
        val manual = ReceiverCompatibility.resolve(unrecognized, preset = VehiclePreset.MERCEDES_MB_DPP)
        assertTrue("manual.mercedes-mb-dpp" in manual.rules)
        assertFalse(manual.releaseBluetoothAfterSetup)
        assertNull(manual.pinnedMfiChallengeLength)
        // 自动规则本身仍只认报告过的型号与 320.17.7；手动预设不会把自动规则扩到品牌。
        assertFalse(ReceiverCompatibility.resolve(unrecognized, preset = VehiclePreset.AUTO).singleRecordPerSession)
    }

    @Test
    fun arrizo8HintSharesTheCheryIdentityNormalization() {
        // 自报值带空格/大小写差异时，单 RECORD 与 30 s 提示必须同进同退。
        val observed = ReceiverProfile.Signals(manufacturer = " chery ", model = "TIGGO ", srcvers = " 320.17.7")
        val policy = ReceiverCompatibility.resolve(observed)
        assertTrue(policy.arrizo8ConnectWindowHint)
        assertEquals(policy.singleRecordPerSession, policy.arrizo8ConnectWindowHint)
        assertFalse(ReceiverCompatibility.resolve(observed.copy(srcvers = "320.17.8")).arrizo8ConnectWindowHint)
        assertTrue(
            "手动车型只改适配开关，不改车型提示",
            ReceiverCompatibility.resolve(observed, preset = VehiclePreset.GENERIC).arrizo8ConnectWindowHint,
        )
    }

    @Test
    fun timeoutAndResetNeverAuthorizePlaintextOrUnknownBluetoothRetirement() {
        listOf(winPlay, mib3, ReceiverProfile.Signals()).forEach { signals ->
            val decision = failedPairing(signals)
            assertFalse(decision.useBtRetireHack)
            assertFalse(decision.usePlainSession)
        }
        assertTrue(failedPairing(diPlay).useBtRetireHack)
        assertFalse(failedPairing(diPlay).usePlainSession)
    }

    @Test
    fun forbiddenPairingBlocksEveryFallbackIncludingKnownBenchException() {
        listOf(winPlay, diPlay, mib3, ReceiverProfile.Signals()).forEach { signals ->
            val decision = failedPairing(signals, forbidden = true, unsupported = true)
            assertFalse(decision.useBtRetireHack)
            assertFalse(decision.usePlainSession)
        }
    }

    @Test
    fun explicitUnsupportedPairingPermitsPlainSession() {
        assertTrue(failedPairing(ReceiverProfile.Signals(), unsupported = true).usePlainSession)
        assertTrue(failedPairing(ReceiverProfile.Signals(features = 0)).usePlainSession)
    }

    @Test
    fun audioMemoryIsScopedToFirmwareStackAndCapabilities() {
        val original = ReceiverCompatibility.resolve(winPlay, "102|media|8388608", "receiver-a").memoryKey
        assertNotEquals(
            original,
            ReceiverCompatibility.resolve(winPlay.copy(firmwareVersion = "1.3.0"), "102|media|8388608", "receiver-a").memoryKey,
        )
        assertNotEquals(
            original,
            ReceiverCompatibility.resolve(winPlay.copy(srcvers = "951.1"), "102|media|8388608", "receiver-a").memoryKey,
        )
        assertNotEquals(original, ReceiverCompatibility.resolve(winPlay, "100|media|32768", "receiver-a").memoryKey)
        assertNotEquals(original, ReceiverCompatibility.resolve(winPlay, "102|media|8388608", "receiver-b").memoryKey)
        assertEquals(
            original,
            ReceiverCompatibility.resolve(winPlay.copy(name = "renamed-device"), "102|media|8388608", "receiver-a").memoryKey,
        )
        assertNull(ReceiverCompatibility.resolve(winPlay).memoryKey)
        assertNull(ReceiverCompatibility.resolve(ReceiverProfile.Signals(name = "unknown-receiver")).memoryKey)
    }

    @Test
    fun switchingReceiverNeverKeepsPreviousSoftwareRules() {
        ReceiverCompatibility.resolve(winPlay)
        val unknown = ReceiverCompatibility.resolve(ReceiverProfile.Signals())
        assertEquals(ReceiverCompatibility.Family.GENERIC, unknown.family)
        assertFalse(unknown.requiresRealBluetoothIdentity)
        assertFalse(unknown.allowBluetoothRetireRetry)
        assertEquals(ReceiverCompatibility.BluetoothRole.KEEP_DURING_BRINGUP, unknown.bluetoothRole)
    }

    @Test
    fun policyDiagnosticsDoNotContainRawDeviceIdentity() {
        val policy = ReceiverCompatibility.resolve(winPlay.copy(name = "private-hotspot", manufacturer = "AA:BB:CC:DD:EE:FF"))
        val text = ReceiverCompatibility.describe(policy)
        assertFalse(text.contains("private-hotspot"))
        assertFalse(text.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(text.contains("protocol.pair_and_encrypt"))
    }

    private fun failedPairing(
        signals: ReceiverProfile.Signals,
        forbidden: Boolean = false,
        unsupported: Boolean = false,
    ): ReceiverCompatibility.PairingFallback =
        ReceiverCompatibility.pairingFallback(
            ReceiverCompatibility.resolve(signals),
            keysEstablished = false,
            noPairing = false,
            pairSetupRejected = false,
            pairingForbidden = forbidden,
            attemptedPairing = true,
            pairingUnsupported = unsupported,
        )

    @Test
    fun onlyVolkswagenModelsReconnectOnStreamingInvites() {
        val id7 =
            ReceiverCompatibility.resolve(
                ReceiverProfile.Signals(model = "VW4131", manufacturer = "Volkswagen", srcvers = "320.17.7"),
            )
        assertTrue(id7.reconnectOnStreamingInvite)
        assertTrue(id7.rules.contains("vw.streaming-invite-reconnect"))
        val passat =
            ReceiverCompatibility.resolve(
                ReceiverProfile.Signals(model = "VW4211", manufacturer = "Toppower", srcvers = "320.17.3"),
            )
        assertTrue(passat.reconnectOnStreamingInvite)
        for (other in listOf(
            winPlay,
            diPlay,
            mib3,
            ReceiverProfile.Signals(model = "Tiggo", manufacturer = "CHERY", srcvers = "320.17.7"),
        )) {
            val policy = ReceiverCompatibility.resolve(other)
            assertFalse(policy.reconnectOnStreamingInvite)
            assertFalse(policy.rules.contains("vw.streaming-invite-reconnect"))
        }
        assertFalse(ReceiverCompatibility.resolve(ReceiverProfile.Signals(model = "VW")).reconnectOnStreamingInvite)
        assertFalse(ReceiverCompatibility.resolve(ReceiverProfile.Signals(model = "VW41310")).reconnectOnStreamingInvite)
        assertFalse(ReceiverCompatibility.resolve(ReceiverProfile.Signals()).reconnectOnStreamingInvite)
    }
}
