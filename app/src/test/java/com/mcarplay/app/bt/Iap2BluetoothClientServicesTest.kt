package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Iap2BluetoothClientServicesTest {
    private fun short(id: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", id))

    @Test
    fun namesStandardServicesAndFlagsIap2() {
        val text =
            Iap2BluetoothClient.describeServices(
                listOf(short(0x111F), short(0x110A), Iap2BluetoothClient.ACCESSORY_IAP2_UUID, short(0x1800)),
                completed = true,
            )
        assertEquals("声明 4 个服务：HFP网关 / A2DP源 / iAP2(…caff) / 0x1800；接收端 iAP2(…caff) 服务=有", text)
    }

    @Test
    fun keepsVendorUuidsAndDistinguishesMissingFromUnknown() {
        val vendor = UUID.fromString("5a3f0a2c-1b2c-4d5e-8f90-123456789abc")
        assertTrue(
            Iap2BluetoothClient
                .describeServices(
                    listOf(short(0x111F), vendor),
                    completed = true,
                ).endsWith("5a3f0a2c-1b2c-4d5e-8f90-123456789abc；接收端 iAP2(…caff) 服务=无（仍可由车机回连，不代表不支持无线 CarPlay）"),
        )
        val timedOut = Iap2BluetoothClient.describeServices(emptyList(), completed = false)
        assertTrue(timedOut.contains("SDP 未在"))
        assertTrue(timedOut.endsWith("服务=未确定（SDP 未返回）"))
    }

    /** 大众车机 SDP 不声明 …caff 但 RFCOMM 可连（2026-10-31）：自动模式不能只凭 SDP 跳过。 */
    @Test
    fun sdpGateKeepsCarAudioAndRememberedReceivers() {
        assertEquals(
            Iap2BluetoothClient.SdpGate.CONNECT,
            Iap2BluetoothClient.sdpGateDecision(hasIap2Service = true, deviceClass = 0x0404, rememberedReceiver = false),
        )
        assertEquals(
            Iap2BluetoothClient.SdpGate.CONNECT_DESPITE_SDP,
            Iap2BluetoothClient.sdpGateDecision(hasIap2Service = false, deviceClass = 0x0420, rememberedReceiver = false),
        )
        assertEquals(
            Iap2BluetoothClient.SdpGate.CONNECT_DESPITE_SDP,
            Iap2BluetoothClient.sdpGateDecision(hasIap2Service = false, deviceClass = null, rememberedReceiver = true),
        )
    }

    /** 帕萨特 2025 款 SDP 缓存原样（报告 db17225a…）：类别未知、未记忆，也必须放行。 */
    @Test
    fun sdpGateKeepsPassatServiceSignature() {
        val vendor = UUID.fromString("d31fbf50-5d57-2797-a240-41cd484388ec")
        val passat = listOf(short(0x110B), short(0x110E), short(0x111E), short(0x1133), short(0x180F), vendor)
        assertEquals(
            Iap2BluetoothClient.SdpGate.CONNECT_DESPITE_SDP,
            Iap2BluetoothClient.sdpGateDecision(
                hasIap2Service = false,
                deviceClass = null,
                rememberedReceiver = false,
                services = passat,
            ),
        )
        // 普通耳机：HFP + A2DP 接收 + AVRCP，没有 MAP
        val headset = listOf(short(0x110B), short(0x110E), short(0x111E))
        assertEquals(
            Iap2BluetoothClient.SdpGate.SKIP,
            Iap2BluetoothClient.sdpGateDecision(
                hasIap2Service = false,
                deviceClass = 0x0404,
                rememberedReceiver = false,
                services = headset,
            ),
        )
    }

    @Test
    fun sdpGateStillSkipsHeadsetsAndWatches() {
        // 0x0404 = 可穿戴耳机，0x0704 = 腕表
        for (deviceClass in listOf(0x0404, 0x0418, 0x0704, null)) {
            assertEquals(
                Iap2BluetoothClient.SdpGate.SKIP,
                Iap2BluetoothClient.sdpGateDecision(hasIap2Service = false, deviceClass = deviceClass, rememberedReceiver = false),
            )
        }
        assertEquals("车载音频", Iap2BluetoothClient.describeDeviceClass(0x0420))
        assertEquals("0x0404", Iap2BluetoothClient.describeDeviceClass(0x0404))
        assertEquals("未知", Iap2BluetoothClient.describeDeviceClass(null))
    }

    @Test
    fun offlineIap2ReceiverDoesNotHideExemptedHeadUnit() {
        // 旧接收端 SDP 缓存带 …caff 但已离线；帕萨特不声明 …caff，靠服务组合 / 记忆放行
        val gates =
            mapOf(
                "old-receiver" to Iap2BluetoothClient.SdpGate.CONNECT,
                "headset" to Iap2BluetoothClient.SdpGate.SKIP,
                "passat" to Iap2BluetoothClient.SdpGate.CONNECT_DESPITE_SDP,
            )
        assertEquals(
            listOf("old-receiver", "passat"),
            Iap2BluetoothClient.autoCandidates(listOf("headset", "passat", "old-receiver")) { gates.getValue(it) },
        )
    }

    @Test
    fun withoutCachedIap2EveryBondedDeviceStaysCandidate() {
        // 没有任何设备缓存了 …caff：沿用全部已配对设备，由连接循环里的 SDP 闸门逐台判断
        val bonded = listOf("headset", "passat")
        val gates = mapOf("headset" to Iap2BluetoothClient.SdpGate.SKIP, "passat" to Iap2BluetoothClient.SdpGate.CONNECT_DESPITE_SDP)
        assertEquals(bonded, Iap2BluetoothClient.autoCandidates(bonded) { gates.getValue(it) })
    }
}
