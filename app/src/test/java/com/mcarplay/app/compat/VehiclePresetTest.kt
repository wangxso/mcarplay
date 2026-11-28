package com.mcarplay.app.compat

import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehiclePresetTest {
    private val unknown = ReceiverProfile.Signals()
    private val chery = ReceiverProfile.Signals(manufacturer = "CHERY", model = "Tiggo")

    private fun resolve(
        signals: ReceiverProfile.Signals,
        preset: VehiclePreset,
    ) = ReceiverCompatibility.resolve(signals, preset = preset)

    @Test
    fun autoLeavesDetectedPolicyUntouched() {
        assertEquals(
            ReceiverCompatibility.resolve(chery, preset = VehiclePreset.AUTO),
            ReceiverCompatibility.applyPreset(ReceiverCompatibility.resolve(chery, preset = VehiclePreset.AUTO), VehiclePreset.AUTO, chery),
        )
        assertFalse(resolve(unknown, VehiclePreset.AUTO).releaseBluetoothAfterSetup)
    }

    @Test
    fun cheryPresetEnablesCheryRulesOnUnknownReceiver() {
        val policy = resolve(unknown, VehiclePreset.CHERY)
        assertFalse(policy.releaseBluetoothAfterSetup)
        assertFalse("chery.disable-bluetooth" in policy.rules)
        assertTrue(policy.skipIapTunnel)
        assertTrue(policy.iphoneAuthSequence)
        assertEquals(32, policy.pinnedMfiChallengeLength)
        assertTrue("manual.chery" in policy.rules)

        val optInUnknown = ReceiverCompatibility.resolve(unknown, preset = VehiclePreset.CHERY, cheryDisableBluetooth = true)
        assertTrue(optInUnknown.releaseBluetoothAfterSetup)
        assertEquals(1, optInUnknown.rules.count { it == "chery.disable-bluetooth" })
        assertTrue("manual.chery" in optInUnknown.rules)

        val optInDetected = ReceiverCompatibility.resolve(chery, preset = VehiclePreset.CHERY, cheryDisableBluetooth = true)
        assertTrue(optInDetected.releaseBluetoothAfterSetup)
        assertEquals(1, optInDetected.rules.count { it == "chery.disable-bluetooth" })
    }

    @Test
    fun genericPresetDisablesDetectedExceptions() {
        val policy = ReceiverCompatibility.resolve(chery, preset = VehiclePreset.GENERIC, cheryDisableBluetooth = true)
        assertFalse(policy.releaseBluetoothAfterSetup)
        assertFalse(policy.sendAppleClientHeaders)
        assertFalse(policy.preferVehicleResolution)
        assertNull(policy.pinnedMfiChallengeLength)
        assertTrue("manual.generic" in policy.rules)
    }

    @Test
    fun otherPresetsApplyTheirFlags() {
        assertTrue(resolve(unknown, VehiclePreset.VOLKSWAGEN).reconnectOnStreamingInvite)
        val win = resolve(unknown, VehiclePreset.WINPLAY)
        assertEquals(ReceiverCompatibility.BluetoothRole.NETWORK_HANDOFF, win.bluetoothRole)
        assertTrue(win.requiresRealBluetoothIdentity)
        assertTrue(win.preferVehicleResolution)
        assertTrue(resolve(unknown, VehiclePreset.DIPLAY).allowBluetoothRetireRetry)
        assertEquals(ReceiverProfile.LinkGeneration.CLASSIC_MIB3, resolve(unknown, VehiclePreset.CLASSIC_MIB3).linkGeneration)
    }

    /**
     * issue #62（`MB_E-P-4 / 480.7.4`，车主称 2025 GLC）实验选项：保持 `displays[0]` 几何，
     * 打开 CarWith 功能协商里的 `DISABLE_RESOLUTION_OPTION=1`，并将隧道 MFi 固定为 32B；
     * 且**只在手动选择时**生效。
     */
    @Test
    fun mercedesMbEPresetTogglesVehicleResolutionAndTunnelMfi() {
        val unknownPolicy = resolve(unknown, VehiclePreset.MERCEDES_MB_E)
        assertFalse(unknownPolicy.usePanelVideoGeometry)
        assertTrue(unknownPolicy.preferVehicleResolution)
        assertTrue("manual.mercedes-mb-e.vehicle-resolution" in unknownPolicy.rules)
        assertFalse(unknownPolicy.releaseBluetoothAfterSetup)
        assertTrue("MB_E-P 对 type-130 不回包并 reset 控制通道", unknownPolicy.skipIapTunnel)
        assertEquals(32, unknownPolicy.pinnedMfiChallengeLength)
        assertTrue("manual.mercedes-mb-e.mfi-32" in unknownPolicy.rules)
        assertTrue("MB_E-P 需要绝对会话 URI", unknownPolicy.absoluteSessionUri)
        assertFalse(
            "MB_E-P 不应提议未实现的 mainBuffered",
            unknownPolicy.offerMainBufferedAudio,
        )
        assertFalse("MB_E-P 固定 H.264，不提议 HEVC", unknownPolicy.offerHevc)
        assertTrue("MB_E-P 对齐 HyperCar 的二次资源声明", unknownPolicy.repeatScreenModesDeclaration)
        assertTrue("MB_E-P 对齐 HyperCar，跳过屏幕 HID 武装", unknownPolicy.skipScreenHidArming)
        assertTrue("manual.mercedes-mb-e.absolute-session-uri" in unknownPolicy.rules)
        assertTrue("manual.mercedes-mb-e.no-iap-tunnel" in unknownPolicy.rules)
        assertTrue("manual.mercedes-mb-e.no-main-buffered" in unknownPolicy.rules)
        assertTrue("manual.mercedes-mb-e.no-hevc" in unknownPolicy.rules)
        assertTrue("manual.mercedes-mb-e.repeat-modes" in unknownPolicy.rules)
        assertTrue("manual.mercedes-mb-e.no-hid-arming" in unknownPolicy.rules)
        // 已知是奔驰（型号/固件不同）也放行——手动预设正是给自动判定认不出的车机用的。
        val mbep = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_E-P-4", srcvers = "480.7.4")
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).preferVehicleResolution)
        assertFalse(resolve(mbep, VehiclePreset.MERCEDES_MB_E).usePanelVideoGeometry)
        assertEquals(32, resolve(mbep, VehiclePreset.MERCEDES_MB_E).pinnedMfiChallengeLength)
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).absoluteSessionUri)
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).skipIapTunnel)
        assertFalse(resolve(mbep, VehiclePreset.MERCEDES_MB_E).offerMainBufferedAudio)
        assertFalse(resolve(mbep, VehiclePreset.MERCEDES_MB_E).offerHevc)
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).repeatScreenModesDeclaration)
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).skipScreenHidArming)
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).rebuildScreenOnResourceReturn)
    }

    @Test
    fun mercedesMbEPresetRefusesOtherManufacturersButVehicleResolutionIsUniversal() {
        // 自动判定永远不开 displayPanels 实验开关：没有可靠的正向证据能触发它。
        val mbep = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_E-P-4", srcvers = "480.7.4")
        assertFalse(resolve(mbep, VehiclePreset.AUTO).usePanelVideoGeometry)
        assertFalse(resolve(unknown, VehiclePreset.AUTO).usePanelVideoGeometry)
        assertTrue(resolve(mbep, VehiclePreset.AUTO).preferVehicleResolution)
        assertTrue(resolve(unknown, VehiclePreset.AUTO).preferVehicleResolution)
        assertFalse(resolve(mbep, VehiclePreset.AUTO).absoluteSessionUri)
        assertTrue(resolve(mbep, VehiclePreset.AUTO).offerMainBufferedAudio)
        assertTrue(resolve(mbep, VehiclePreset.AUTO).offerHevc)
        assertFalse(resolve(mbep, VehiclePreset.AUTO).repeatScreenModesDeclaration)
        assertFalse(resolve(mbep, VehiclePreset.AUTO).skipScreenHidArming)
        assertFalse(resolve(mbep, VehiclePreset.AUTO).rebuildScreenOnResourceReturn)
        // 已知是别家车机：守卫拒绝，只记录 blocked。
        listOf(chery, ReceiverProfile.Signals(manufacturer = "Volkswagen", model = "VW4131")).forEach { other ->
            val policy = resolve(other, VehiclePreset.MERCEDES_MB_E)
            assertFalse("${other.manufacturer} 不应套用奔驰实验开关", policy.usePanelVideoGeometry)
            assertTrue("${other.manufacturer} 的尺寸协商来自通用策略", policy.preferVehicleResolution)
            assertFalse("${other.manufacturer} 不应套用 MBUX 绝对 URI", policy.absoluteSessionUri)
            assertFalse(
                "${other.manufacturer} 不应套用 MBUX type-130 规则",
                "manual.mercedes-mb-e.no-iap-tunnel" in policy.rules,
            )
            assertTrue("${other.manufacturer} 不应撤回 mainBuffered", policy.offerMainBufferedAudio)
            assertTrue("${other.manufacturer} 不应撤回 HEVC", policy.offerHevc)
            assertFalse("${other.manufacturer} 不应重发资源声明", policy.repeatScreenModesDeclaration)
            assertFalse("${other.manufacturer} 不应跳过 HID 武装", policy.skipScreenHidArming)
            assertFalse("${other.manufacturer} 不应套用 MBUX 主屏重建", policy.rebuildScreenOnResourceReturn)
            assertFalse("${other.manufacturer} 不应套用奔驰 MFi 固定档", "manual.mercedes-mb-e.mfi-32" in policy.rules)
            assertTrue("manual.mercedes-mb-e-blocked" in policy.rules)
        }
        // 通用预设关闭实验开关与尺寸协商策略。
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).usePanelVideoGeometry)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).preferVehicleResolution)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).absoluteSessionUri)
        assertTrue(resolve(unknown, VehiclePreset.GENERIC).offerMainBufferedAudio)
        assertTrue(resolve(unknown, VehiclePreset.GENERIC).offerHevc)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).repeatScreenModesDeclaration)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).skipScreenHidArming)
    }

    @Test
    fun parseFallsBackToAuto() {
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse(null))
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse("nope"))
        assertEquals(VehiclePreset.CHERY, VehiclePreset.parse("CHERY"))
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse("MERCEDES_MB_DPP"))
        // 实验选项同样按名字可解析（设置里写过它的旧版本必须继续生效）。
        assertEquals(VehiclePreset.MERCEDES_MB_E, VehiclePreset.parse("MERCEDES_MB_E"))
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse("MERCEDES_BENZ"))
    }
}
