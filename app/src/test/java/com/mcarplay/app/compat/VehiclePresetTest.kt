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
        assertNull(policy.pinnedMfiChallengeLength)
        assertTrue("manual.generic" in policy.rules)
    }

    @Test
    fun otherPresetsApplyTheirFlags() {
        assertTrue(resolve(unknown, VehiclePreset.VOLKSWAGEN).reconnectOnStreamingInvite)
        val win = resolve(unknown, VehiclePreset.WINPLAY)
        assertEquals(ReceiverCompatibility.BluetoothRole.NETWORK_HANDOFF, win.bluetoothRole)
        assertTrue(win.requiresRealBluetoothIdentity)
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

    @Test
    fun parseFallsBackToAuto() {
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse(null))
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse("nope"))
        assertEquals(VehiclePreset.CHERY, VehiclePreset.parse("CHERY"))
        // 旧版本写入的已知值继续可解析；未知/缺失回退 AUTO，不会误开新预设。
        assertEquals(VehiclePreset.MERCEDES_MB_DPP, VehiclePreset.parse("MERCEDES_MB_DPP"))
        assertEquals(VehiclePreset.AUTO, VehiclePreset.parse("MERCEDES_BENZ"))
    }
}
