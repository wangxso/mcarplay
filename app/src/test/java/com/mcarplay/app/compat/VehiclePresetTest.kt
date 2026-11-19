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
        assertTrue(policy.releaseBluetoothAfterSetup)
        assertTrue(policy.skipIapTunnel)
        assertTrue(policy.iphoneAuthSequence)
        assertEquals(32, policy.pinnedMfiChallengeLength)
        assertTrue("manual.chery" in policy.rules)
    }

    @Test
    fun genericPresetDisablesDetectedExceptions() {
        val policy = resolve(chery, VehiclePreset.GENERIC)
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

    @Test
    fun mercedesPresetAppliesSingleRecordOnlyWhenIdentityIsUnknownOrMercedes() {
        // 手动预设的用途：自动判定拿不到型号/厂商。身份为空时放行。
        val unknownPolicy = resolve(unknown, VehiclePreset.MERCEDES_MB_DPP)
        assertTrue("manual.mercedes-mb-dpp" in unknownPolicy.rules)
        assertTrue(unknownPolicy.singleRecordPerSession)
        // 已知是 Mercedes-Benz（即使型号/固件各不相同）也放行——手动预设负责补上自动规则没命中的部分。
        val mercedes = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_D-PP-9")
        assertTrue(resolve(mercedes, VehiclePreset.MERCEDES_MB_DPP).singleRecordPerSession)
        assertTrue(resolve(mercedes.copy(manufacturer = " mercedes-benz "), VehiclePreset.MERCEDES_MB_DPP).singleRecordPerSession)
        // 已知是别家车机：守卫拒绝，保持原策略，只记录 blocked。
        listOf(chery, ReceiverProfile.Signals(manufacturer = "Volkswagen", model = "VW4131")).forEach { other ->
            val policy = resolve(other, VehiclePreset.MERCEDES_MB_DPP)
            assertFalse("${other.manufacturer} 不应套用奔驰单次 RECORD", policy.singleRecordPerSession)
            assertTrue("manual.mercedes-mb-dpp-blocked" in policy.rules)
            assertFalse("manual.mercedes-mb-dpp" in policy.rules)
        }
    }

    @Test
    fun mercedesPresetDoesNotDragInCheryExceptions() {
        val policy = resolve(unknown, VehiclePreset.MERCEDES_MB_DPP)
        assertFalse("手动奔驰预设不得关闭蓝牙", policy.releaseBluetoothAfterSetup)
        assertFalse(policy.skipIapTunnel)
        assertFalse(policy.iphoneAuthSequence)
        assertNull(policy.pinnedMfiChallengeLength)
        assertFalse("手动奔驰预设不得启用艾瑞泽 8 提示", policy.arrizo8ConnectWindowHint)
    }

    @Test
    fun genericPresetTurnsOffManualMercedesSingleRecord() {
        val mercedes = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_D-PP-3", srcvers = "320.17.7")
        assertTrue(resolve(mercedes, VehiclePreset.MERCEDES_MB_DPP).singleRecordPerSession)
        assertFalse(resolve(mercedes, VehiclePreset.GENERIC).singleRecordPerSession)
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
        // 不得顺手带上 MB_D-PP 的单次 RECORD：那是另一代际、另一份报告的证据。
        assertFalse(unknownPolicy.singleRecordPerSession)
        assertFalse(unknownPolicy.releaseBluetoothAfterSetup)
        assertFalse(unknownPolicy.skipIapTunnel)
        assertEquals(32, unknownPolicy.pinnedMfiChallengeLength)
        assertTrue("manual.mercedes-mb-e.mfi-32" in unknownPolicy.rules)
        assertTrue("MB_E-P 需要先 RECORD", unknownPolicy.recordBeforeScreenSetup)
        assertTrue("MB_E-P 需要绝对会话 URI", unknownPolicy.absoluteSessionUri)
        assertFalse(
            "MB_E-P 不应提议未实现的 mainBuffered",
            unknownPolicy.offerMainBufferedAudio,
        )
        assertTrue("manual.mercedes-mb-e.record-first" in unknownPolicy.rules)
        assertTrue("manual.mercedes-mb-e.absolute-session-uri" in unknownPolicy.rules)
        assertTrue("manual.mercedes-mb-e.no-main-buffered" in unknownPolicy.rules)
        // 已知是奔驰（型号/固件不同）也放行——手动预设正是给自动判定认不出的车机用的。
        val mbep = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_E-P-4", srcvers = "480.7.4")
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).preferVehicleResolution)
        assertFalse(resolve(mbep, VehiclePreset.MERCEDES_MB_E).usePanelVideoGeometry)
        assertEquals(32, resolve(mbep, VehiclePreset.MERCEDES_MB_E).pinnedMfiChallengeLength)
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).recordBeforeScreenSetup)
        assertTrue(resolve(mbep, VehiclePreset.MERCEDES_MB_E).absoluteSessionUri)
        assertFalse(resolve(mbep, VehiclePreset.MERCEDES_MB_E).offerMainBufferedAudio)
    }

    @Test
    fun mercedesMbEPresetRefusesOtherManufacturersAndNeverAutoEnables() {
        // 自动判定永远不开实验开关：没有可靠的正向证据能触发它。
        val mbep = ReceiverProfile.Signals(manufacturer = "Mercedes-Benz", model = "MB_E-P-4", srcvers = "480.7.4")
        assertFalse(resolve(mbep, VehiclePreset.AUTO).usePanelVideoGeometry)
        assertFalse(resolve(unknown, VehiclePreset.AUTO).usePanelVideoGeometry)
        assertFalse(resolve(mbep, VehiclePreset.AUTO).preferVehicleResolution)
        assertFalse(resolve(unknown, VehiclePreset.AUTO).preferVehicleResolution)
        assertFalse(resolve(mbep, VehiclePreset.AUTO).recordBeforeScreenSetup)
        assertFalse(resolve(mbep, VehiclePreset.AUTO).absoluteSessionUri)
        assertTrue(resolve(mbep, VehiclePreset.AUTO).offerMainBufferedAudio)
        // 已知是别家车机：守卫拒绝，只记录 blocked。
        listOf(chery, ReceiverProfile.Signals(manufacturer = "Volkswagen", model = "VW4131")).forEach { other ->
            val policy = resolve(other, VehiclePreset.MERCEDES_MB_E)
            assertFalse("${other.manufacturer} 不应套用奔驰实验开关", policy.usePanelVideoGeometry)
            assertFalse("${other.manufacturer} 不应套用奔驰尺寸协商", policy.preferVehicleResolution)
            assertFalse("${other.manufacturer} 不应套用 MBUX 会话时序", policy.recordBeforeScreenSetup)
            assertFalse("${other.manufacturer} 不应套用 MBUX 绝对 URI", policy.absoluteSessionUri)
            assertTrue("${other.manufacturer} 不应撤回 mainBuffered", policy.offerMainBufferedAudio)
            assertFalse("${other.manufacturer} 不应套用奔驰 MFi 固定档", "manual.mercedes-mb-e.mfi-32" in policy.rules)
            assertTrue("manual.mercedes-mb-e-blocked" in policy.rules)
        }
        // 通用预设关闭实验开关。
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).usePanelVideoGeometry)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).preferVehicleResolution)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).recordBeforeScreenSetup)
        assertFalse(resolve(unknown, VehiclePreset.GENERIC).absoluteSessionUri)
        assertTrue(resolve(unknown, VehiclePreset.GENERIC).offerMainBufferedAudio)
    }

    @Test
    fun parseFallsBackToAuto() {
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse(null))
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse("nope"))
        assertEquals(VehiclePreset.CHERY, VehiclePreset.parse("CHERY"))
        // 旧版本写入的已知值继续可解析；未知/缺失回退 AUTO，不会误开新预设。
        assertEquals(VehiclePreset.MERCEDES_MB_DPP, VehiclePreset.parse("MERCEDES_MB_DPP"))
        // 实验选项同样按名字可解析（设置里写过它的旧版本必须继续生效）。
        assertEquals(VehiclePreset.MERCEDES_MB_E, VehiclePreset.parse("MERCEDES_MB_E"))
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse("MERCEDES_BENZ"))
    }
}
