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
        assertEquals("声明 4 个服务：HFP网关 / A2DP源 / iAP2(…caff) / 0x1800；无线 CarPlay iAP2 服务=有", text)
    }

    @Test
    fun keepsVendorUuidsAndDistinguishesMissingFromUnknown() {
        val vendor = UUID.fromString("5a3f0a2c-1b2c-4d5e-8f90-123456789abc")
        assertTrue(
            Iap2BluetoothClient
                .describeServices(
                    listOf(short(0x111F), vendor),
                    completed = true,
                ).endsWith("5a3f0a2c-1b2c-4d5e-8f90-123456789abc；无线 CarPlay iAP2 服务=无"),
        )
        val timedOut = Iap2BluetoothClient.describeServices(emptyList(), completed = false)
        assertTrue(timedOut.contains("SDP 未在"))
        assertTrue(timedOut.endsWith("服务=未确定（SDP 未返回）"))
    }
}
