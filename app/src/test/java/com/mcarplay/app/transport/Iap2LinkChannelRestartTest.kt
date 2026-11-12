package com.mcarplay.app.transport

import com.mcarplay.app.iap2.Iap2LinkEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2LinkChannelRestartTest {
    @Test
    fun newSynClosesTheChannelAndSurfacesTheRestartReason() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone-restart")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory-restart")
        try {
            assertTrue(phoneChannel.awaitReady(5_000))
            assertTrue(accessoryChannel.awaitReady(5_000))
            // 链路先承载一条控制消息：此后的新 SYN 不再可能只是协商期重试
            // （协商期同表重试应重新回显，见 Iap2LinkEngineTest）。
            assertTrue(phoneChannel.sendControl(byteArrayOf(9, 9, 9)))
            assertNotNull("控制消息应先送达", accessoryChannel.recvControl(2_000))

            // 车机侧用新的 SYN 序号重开协商。链路必须明确终止，而不是让上层请求
            // 继续排队到未确认超时。
            pair.sideB.write(synFrame(peerSequence = 161))

            assertTrue("新 SYN 应关闭旧链路", phoneChannel.awaitTerminated(2_000))
            assertTrue(
                "关闭原因必须可被上层看到：${phoneChannel.terminationReason}",
                phoneChannel.terminationReason?.contains("restarted iAP2 negotiation") == true,
            )
            assertFalse("旧链路不能继续发送", phoneChannel.sendControl(byteArrayOf(1, 2, 3)))
        } finally {
            phoneChannel.close()
            accessoryChannel.close()
        }
    }

    /** Construct a marker + SYN frame with a changed sequence and repaired header checksum. */
    private fun synFrame(peerSequence: Int): ByteArray {
        val template = Iap2LinkEngine()
        template.start(0)
        val raw = template.takeOutput()
        val synOffset = Iap2LinkEngine.MARKER.size
        raw[synOffset + 5] = peerSequence.toByte()
        var sum = 0
        for (index in synOffset until synOffset + 8) sum += raw[index].toInt() and 0xFF
        raw[synOffset + 8] = (-sum and 0xFF).toByte()
        return raw
    }
}
