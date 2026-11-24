package com.mcarplay.app.compat

import com.mcarplay.app.iap2.Iap2LinkEngine
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
        assertTrue(windows.preferVehicleResolution)
        assertFalse(windows.allowBluetoothRetireRetry)
        assertTrue(windows.rules.contains("winplay.1.2.4.network-handoff"))
        assertTrue(windows.rules.contains("winplay.vehicle-resolution"))
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

    /** issue #127：出画协商对所有接收端自动启用，只有手动「通用」预设关闭。 */
    @Test
    fun vehicleResolutionIsAutomaticForEveryReceiverExceptTheGenericPreset() {
        listOf(winPlay, diPlay, mib3, ReceiverProfile.Signals(), ReceiverProfile.Signals(manufacturer = "CHERY", model = "Tiggo"))
            .forEach { signals ->
                assertTrue(
                    "${signals.model}/${signals.manufacturer} 应走通用出画协商",
                    ReceiverCompatibility.resolve(signals).preferVehicleResolution,
                )
                assertFalse(
                    "手动通用应关闭出画协商",
                    ReceiverCompatibility.resolve(signals, preset = VehiclePreset.GENERIC).preferVehicleResolution,
                )
            }
        assertTrue(
            ReceiverCompatibility.resolve(ReceiverProfile.Signals(), preset = VehiclePreset.MERCEDES_MB_E).preferVehicleResolution,
        )
        assertTrue(
            ReceiverCompatibility.resolve(ReceiverProfile.Signals(), preset = VehiclePreset.WINPLAY).preferVehicleResolution,
        )
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
        assertTrue("WinPlay 身份仍明确，尺寸协商不依赖固件号", nextWindows.preferVehicleResolution)
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
    fun ora03DirectDiscoveryRequiresMatchingBluetoothAndMdnsEvidenceInAutoMode() {
        val ora =
            ReceiverProfile.Signals(
                model = "ORA ES11",
                manufacturer = "GWM",
                sendIds = setOf(0xaa06, 0x5703),
                receiveIds = setOf(0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4e0d, 0x4e0e, 0x5702),
            )

        fun prefersDirect(
            signals: ReceiverProfile.Signals = ora,
            model: String? = "ORA ES11",
            firmware: String? = "N220346.2",
            sourceVersion: String? = "365.10.2",
            preset: VehiclePreset = VehiclePreset.AUTO,
        ) = ReceiverCompatibility.prefersOra03DirectDiscovery(signals, model, firmware, sourceVersion, preset)

        assertTrue(prefersDirect())
        assertTrue(prefersDirect(sourceVersion = " 365.10.2 "))
        assertFalse(prefersDirect(signals = ora.copy(manufacturer = null)))
        assertFalse(prefersDirect(signals = ora.copy(model = "Other")))
        assertFalse(prefersDirect(signals = ora.copy(sendIds = ora.sendIds.orEmpty() + 0x4301)))
        assertFalse(prefersDirect(signals = ora.copy(receiveIds = emptySet())))
        assertFalse(prefersDirect(model = null))
        assertFalse(prefersDirect(firmware = "N220346.3"))
        assertFalse(prefersDirect(sourceVersion = "365.10.3"))
        assertFalse(prefersDirect(preset = VehiclePreset.GENERIC))
    }

    @Test
    fun ora03IdentityRecognizesOnlyTheObservedVehicleAndGenericSignatures() {
        val expected = ReceiverCompatibility.ReceiverIdentity.EXPECTED
        val generic = ReceiverCompatibility.ReceiverIdentity.GENERIC
        val other = ReceiverCompatibility.ReceiverIdentity.OTHER
        assertEquals(expected, ReceiverCompatibility.classifyReceiverIdentity("GWM", "ORA ES11", "N220346.2", "365.10.2"))
        assertEquals(expected, ReceiverCompatibility.classifyReceiverIdentity("GWM", "ORA ES11", "N220346.2", " 365.10.2 "))
        assertEquals(generic, ReceiverCompatibility.classifyReceiverIdentity("OEM", "OEM MODEL", "0.0.1", "365.10.2"))
        assertEquals(generic, ReceiverCompatibility.classifyReceiverIdentity("OEM", "OEM MODEL", "0.0.1", " 365.10.2 "))
        assertEquals(other, ReceiverCompatibility.classifyReceiverIdentity("OEM", "OEM MODEL", "0.0.1", "365.10.3"))
        assertEquals(other, ReceiverCompatibility.classifyReceiverIdentity("OEM", "OEM MODEL", null, "365.10.2"))
        assertEquals(other, ReceiverCompatibility.classifyReceiverIdentity("OEM", "Other", "0.0.1", "365.10.2"))
        assertEquals(other, ReceiverCompatibility.classifyReceiverIdentity(null, "OEM MODEL", "0.0.1", "365.10.2"))
    }

    @Test
    fun genericIdentityWarnsOnlyAfterConfirmationAndClearsOnNewEvidence() {
        val expected = ReceiverCompatibility.ReceiverIdentity.EXPECTED
        val generic = ReceiverCompatibility.ReceiverIdentity.GENERIC
        val other = ReceiverCompatibility.ReceiverIdentity.OTHER
        var known = false

        fun observe(identity: ReceiverCompatibility.ReceiverIdentity?): ReceiverCompatibility.ReceiverIdentityDecision =
            ReceiverCompatibility.receiverIdentityDecision(known, identity).also { known = known || it.rememberExpected }

        assertFalse(observe(generic).showWarning)
        assertFalse(known)
        val confirmation = observe(expected)
        assertTrue(confirmation.rememberExpected)
        assertFalse(confirmation.showWarning)
        assertTrue(observe(generic).showWarning)
        assertFalse(observe(expected).showWarning)
        assertTrue(observe(generic).showWarning)
        assertFalse(observe(other).showWarning)
        assertTrue(observe(generic).showWarning)
        assertFalse(observe(null).showWarning)
        assertTrue(known)
    }

    @Test
    fun genericPresetStillUsesAdvertisedStandaloneMfiAuthSetup() {
        val standaloneMfi = AirPlayFeatures.HK_PAIRING_AND_ENCRYPT or AirPlayFeatures.MFI_SAP
        val receiver = ReceiverProfile.Signals(features = standaloneMfi)
        val generic = ReceiverCompatibility.resolve(receiver, preset = VehiclePreset.GENERIC)
        assertTrue(generic.authSetupBeforeSession)
        assertFalse(generic.iphoneAuthSequence)
        assertTrue(generic.rules.contains("protocol.mfi-sap-auth-setup"))

        val unified = receiver.copy(features = standaloneMfi or AirPlayFeatures.UNIFIED_PAIRING_AND_MFI)
        assertFalse(ReceiverCompatibility.resolve(unified, preset = VehiclePreset.CHERY).authSetupBeforeSession)
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

    /** 艾瑞泽 8（iAP2 自报 Cloudrive / Tiggo / CHERY）：奇瑞其余适配默认生效，disableBluetooth 默认关、仅开关打开时启用。 */
    @Test
    fun onlyCheryReleasesBluetoothAfterSetup() {
        val chery = ReceiverProfile.Signals(name = "Cloudrive", model = "Tiggo", manufacturer = "CHERY")
        val defaultPolicy = ReceiverCompatibility.resolve(chery)
        assertFalse(defaultPolicy.releaseBluetoothAfterSetup)
        assertFalse("chery.disable-bluetooth" in defaultPolicy.rules)
        assertTrue(defaultPolicy.reportWifiMacInSetup)
        assertTrue("chery.real-wifi-mac" in defaultPolicy.rules)
        assertTrue(defaultPolicy.skipIapTunnel)
        assertTrue("chery.no-iap-tunnel" in defaultPolicy.rules)
        assertTrue(defaultPolicy.sendAppleClientHeaders)
        assertTrue("chery.apple-client-headers" in defaultPolicy.rules)
        assertTrue(defaultPolicy.iphoneAuthSequence)
        assertTrue("chery.iphone-auth-sequence" in defaultPolicy.rules)
        assertEquals(32, defaultPolicy.pinnedMfiChallengeLength)
        assertTrue("chery.mfi-32-only" in defaultPolicy.rules)

        val optInPolicy = ReceiverCompatibility.resolve(chery, cheryDisableBluetooth = true)
        assertTrue(optInPolicy.releaseBluetoothAfterSetup)
        assertTrue("chery.disable-bluetooth" in optInPolicy.rules)

        listOf(winPlay, diPlay, mib3, ReceiverProfile.Signals(), ReceiverProfile.Signals(name = "CHERY")).forEach { signals ->
            val other = ReceiverCompatibility.resolve(signals, cheryDisableBluetooth = true)
            assertFalse(other.releaseBluetoothAfterSetup)
            assertFalse("chery.disable-bluetooth" in other.rules)
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
    fun gs3PresetUsesMarkerFirstAndKeepsNetworkSessionDuringSetupHandoff() {
        val gs3 = ReceiverCompatibility.resolve(ReceiverProfile.Signals(), preset = VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW)
        assertEquals(Iap2LinkEngine.MAX_OUTGOING_LIMIT, gs3.iap2InitialMaxOutgoing)
        assertTrue(gs3.waitForIap2PeerMarker)
        assertTrue(gs3.keepNetworkSessionDuringSetupHandoffOnBluetoothLoss)
        assertTrue(gs3.claimScreenOnExplicitStart)
        assertTrue("manual.trumpchi-gs3.iap2-marker-first" in gs3.rules)
        assertTrue("manual.trumpchi-gs3.explicit-start-screen-claim" in gs3.rules)
        assertEquals(
            ReceiverCompatibility.BluetoothLossAction.END_SESSION,
            ReceiverCompatibility.bluetoothLossAction(gs3, videoRunning = false, networkHandoffReady = false),
        )
        assertEquals(
            ReceiverCompatibility.BluetoothLossAction.KEEP_NETWORK_SESSION,
            ReceiverCompatibility.bluetoothLossAction(
                gs3,
                videoRunning = false,
                networkHandoffReady = false,
                setupHandoffStarted = true,
            ),
        )

        listOf(VehiclePreset.AUTO, VehiclePreset.GENERIC).forEach { preset ->
            val policy = ReceiverCompatibility.resolve(ReceiverProfile.Signals(), preset = preset)
            assertEquals(Iap2LinkEngine.DEFAULT_MAX_OUTGOING, policy.iap2InitialMaxOutgoing)
            assertFalse(policy.waitForIap2PeerMarker)
            assertFalse(policy.keepNetworkSessionDuringSetupHandoffOnBluetoothLoss)
            assertFalse(policy.claimScreenOnExplicitStart)
            assertEquals(
                preset.name,
                ReceiverCompatibility.BluetoothLossAction.END_SESSION,
                ReceiverCompatibility.bluetoothLossAction(
                    policy,
                    videoRunning = false,
                    networkHandoffReady = false,
                    setupHandoffStarted = true,
                ),
            )
        }
    }

    @Test
    fun arrizo8HintMatchesTheObservedCheryIdentity() {
        val observed = ReceiverProfile.Signals(manufacturer = " chery ", model = "TIGGO ", srcvers = " 320.17.7")
        val policy = ReceiverCompatibility.resolve(observed)
        assertTrue(policy.arrizo8ConnectWindowHint)
        listOf(
            observed.copy(manufacturer = "OTHER"),
            observed.copy(model = "OTHER"),
            observed.copy(srcvers = "320.17.8"),
            observed.copy(srcvers = null),
        ).forEach { assertFalse(ReceiverCompatibility.resolve(it).arrizo8ConnectWindowHint) }
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
        // issue #127：支持报告里应能直接看到出画协商策略是否开启。
        assertTrue("诊断行应包含 vehicleResolution=true", text.contains("vehicleResolution=true"))
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

    // ---- Phase 2e：回屏策略只由「手动大众 + 保存值 + context 可用」决定 ----

    @Test
    fun screenReturnMatrixCoversAllNinePresetsWithLazyPreferenceRead() {
        assertEquals(
            "车型预设清单变化必须同步更新回屏资格矩阵",
            setOf(
                VehiclePreset.AUTO,
                VehiclePreset.GENERIC,
                VehiclePreset.CHERY,
                VehiclePreset.VOLKSWAGEN,
                VehiclePreset.WINPLAY,
                VehiclePreset.DIPLAY,
                VehiclePreset.CLASSIC_MIB3,
                VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW,
                VehiclePreset.MERCEDES_MB_E,
            ),
            VehiclePreset.entries.toSet(),
        )
        val base = ReceiverCompatibility.resolve(ReceiverProfile.Signals())
        VehiclePreset.entries.forEach { preset ->
            var reads = 0
            val overlaid =
                ReceiverCompatibility.withScreenReturnPreference(base, preset) {
                    reads++
                    ScreenReturnStrategy.DISCONNECT_AFTER_FAILURES
                }
            if (preset == VehiclePreset.VOLKSWAGEN) {
                assertEquals("手动大众必须采用保存值", ScreenReturnStrategy.DISCONNECT_AFTER_FAILURES, overlaid.screenReturn)
                assertEquals("手动大众只读一次偏好", 1, reads)
            } else {
                assertEquals("$preset 必须为 OFF", ScreenReturnStrategy.OFF, overlaid.screenReturn)
                assertEquals("$preset 不得调用惰性偏好读取", 0, reads)
            }
        }
        // context 不可用时 Controller 的回调直接返回 OFF，兼容层不额外读 prefs。
        val noContext = ReceiverCompatibility.withScreenReturnPreference(base, VehiclePreset.VOLKSWAGEN) { ScreenReturnStrategy.OFF }
        assertEquals(ScreenReturnStrategy.OFF, noContext.screenReturn)
    }

    @Test
    fun screenReturnIgnoresDetectedManufacturerAndAutoMode() {
        val observed =
            listOf(
                ReceiverProfile.Signals(),
                ReceiverProfile.Signals(model = "Tiggo", manufacturer = "CHERY"),
                ReceiverProfile.Signals(model = "VW4131", manufacturer = "Volkswagen"),
                ReceiverProfile.Signals(model = "VW4211", manufacturer = "Toppower"),
            )
        observed.forEach { signals ->
            // 自动模式即使自报 VWxxxx，也不启用回屏策略，且不读取偏好。
            val auto = ReceiverCompatibility.resolve(signals, preset = VehiclePreset.AUTO)
            var reads = 0
            val overlaid =
                ReceiverCompatibility.withScreenReturnPreference(auto, VehiclePreset.AUTO) {
                    reads++
                    ScreenReturnStrategy.REBUILD_RETRY
                }
            assertEquals(ScreenReturnStrategy.OFF, overlaid.screenReturn)
            assertEquals(0, reads)
            // 邀请重连资格保持自动规则，与回屏偏好分开。
            assertEquals(signals.model?.startsWith("VW") == true, auto.reconnectOnStreamingInvite)
        }
        // 手动大众即使信号明确是别家车机，回屏仍按用户选择执行。
        val chery = ReceiverProfile.Signals(model = "Tiggo", manufacturer = "CHERY")
        val manual = ReceiverCompatibility.resolve(chery, preset = VehiclePreset.VOLKSWAGEN)
        val overlaid =
            ReceiverCompatibility.withScreenReturnPreference(manual, VehiclePreset.VOLKSWAGEN) {
                ScreenReturnStrategy.REBUILD_RETRY
            }
        assertEquals(ScreenReturnStrategy.REBUILD_RETRY, overlaid.screenReturn)
        assertTrue(manual.reconnectOnStreamingInvite)
        assertTrue("manual.volkswagen" in manual.rules)
    }

    @Test
    fun screenReturnOverlayLeavesEveryOtherPolicyFieldAlone() {
        val signalsList =
            listOf(winPlay, diPlay, mib3, ReceiverProfile.Signals(), ReceiverProfile.Signals(manufacturer = "CHERY", model = "Tiggo"))
        VehiclePreset.entries.forEach { preset ->
            signalsList.forEach { signals ->
                val base = ReceiverCompatibility.resolve(signals, preset = preset)
                assertEquals("base policy 默认不回屏", ScreenReturnStrategy.OFF, base.screenReturn)
                val overlaid =
                    ReceiverCompatibility.withScreenReturnPreference(base, preset) {
                        ScreenReturnStrategy.REBUILD_RETRY
                    }
                val expected =
                    base.copy(
                        screenReturn =
                            if (preset == VehiclePreset.VOLKSWAGEN) {
                                ScreenReturnStrategy.REBUILD_RETRY
                            } else {
                                ScreenReturnStrategy.OFF
                            },
                    )
                assertEquals("preset=$preset signals=${signals.model}", expected, overlaid)
                assertEquals(base.rules, overlaid.rules)
                assertEquals(base.memoryKey, overlaid.memoryKey)
                assertEquals(base.reconnectOnStreamingInvite, overlaid.reconnectOnStreamingInvite)
                assertEquals(ReceiverCompatibility.describe(base), ReceiverCompatibility.describe(overlaid))
                assertFalse("describe 不得追加回屏字段", ReceiverCompatibility.describe(overlaid).contains("screenReturn"))
            }
        }
    }

    @Test
    fun autoRequestCarplayUiIsLimitedToObservedBicvMicaReceiver() {
        val bicv =
            ReceiverProfile.Signals(
                manufacturer = "Bicv",
                model = "GSE,EV3,SUV",
                srcvers = "475.8.3",
                firmwareVersion = "MICA3A01",
            )
        val policy = ReceiverCompatibility.resolve(bicv)

        assertTrue(policy.autoRequestCarplayUi)
        assertTrue(policy.rules.contains("bicv.gse-ev3.475.8.3.auto-request-ui"))

        for (signals in listOf(
            bicv.copy(srcvers = "475.8.4"),
            bicv.copy(model = "GSE,EV3"),
            bicv.copy(manufacturer = "other"),
            bicv.copy(firmwareVersion = "MICA3A02"),
            winPlay,
        )) {
            val other = ReceiverCompatibility.resolve(signals)
            assertFalse(other.autoRequestCarplayUi)
            assertFalse(other.rules.contains("bicv.gse-ev3.475.8.3.auto-request-ui"))
        }
        assertFalse(ReceiverCompatibility.resolve(bicv, preset = VehiclePreset.GENERIC).autoRequestCarplayUi)
    }
}
