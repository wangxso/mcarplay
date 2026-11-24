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
     * 只打开 CarWith 功能协商里的 `DISABLE_RESOLUTION_OPTION=1`，且**只在手动选择时**生效。
     */
    @Test
    fun mercedesMbEPresetOnlyTogglesVehicleResolutionRequest() {
        val unknownPolicy = resolve(unknown, VehiclePreset.MERCEDES_MB_E)
        assertFalse(unknownPolicy.usePanelVideoGeometry)
        assertTrue(unknownPolicy.preferVehicleResolution)
        assertTrue("manual.mercedes-mb-e.vehicle-resolution" in unknownPolicy.rules)
        assertFalse(unknownPolicy.releaseBluetoothAfterSetup)
        assertFalse(unknownPolicy.skipIapTunnel)
        assertNull(unknownPolicy.pinnedMfiChallengeLength)
        // 已知是奔驰（型号/固件不同）也放行——手动预设正是给自动判定认不出的车机用的。
        val mbep = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_E-P-4", srcvers = "480.7.4")
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).preferVehicleResolution)
        assertFalse(resolve(mbep, VehiclePreset.MERCEDES_MB_E).usePanelVideoGeometry)
    }

    @Test
    fun mercedesMbEPresetRefusesOtherManufacturersButVehicleResolutionIsUniversal() {
        // 自动判定永远不开 displayPanels 实验开关：没有可靠的正向证据能触发它。
        val mbep = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_E-P-4", srcvers = "480.7.4")
        assertFalse(resolve(mbep, VehiclePreset.AUTO).usePanelVideoGeometry)
        assertFalse(resolve(unknown, VehiclePreset.AUTO).usePanelVideoGeometry)
        // issue #127：DISABLE_RESOLUTION_OPTION=1 已是通用自动策略；是否真正声明由本轮
        // /info displays[0] 的合法几何门控（不是这里按车型决定）。
        assertTrue(resolve(mbep, VehiclePreset.AUTO).preferVehicleResolution)
        assertTrue(resolve(unknown, VehiclePreset.AUTO).preferVehicleResolution)
        // 已知是别家车机：奔驰实验开关被拒绝，但通用尺寸协商策略保留。
        listOf(chery, ReceiverProfile.Signals(manufacturer = "Volkswagen", model = "VW4131")).forEach { other ->
            val policy = resolve(other, VehiclePreset.MERCEDES_MB_E)
            assertFalse("${other.manufacturer} 不应套用奔驰实验开关", policy.usePanelVideoGeometry)
            assertTrue("${other.manufacturer} 的尺寸协商来自通用策略", policy.preferVehicleResolution)
            assertTrue("manual.mercedes-mb-e-blocked" in policy.rules)
        }
        // 通用预设关闭实验开关与尺寸协商策略。
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).usePanelVideoGeometry)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).preferVehicleResolution)
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
