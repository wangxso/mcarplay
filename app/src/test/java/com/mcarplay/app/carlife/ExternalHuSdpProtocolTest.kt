package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExternalHuSdpProtocolTest {
    @Test
    fun headerUsesLittleEndianLengthAndXorChecksum() {
        val frame = ExternalHuSdpProtocol.encodeFrame(ExternalHuSdpProtocol.CONNECT_TYPE)

        assertEquals(16, frame.size)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(ExternalHuSdpProtocol.CONNECT_TYPE, buffer.int)
        assertEquals(16, buffer.int)
        assertEquals(16 xor ExternalHuSdpProtocol.CONNECT_TYPE, buffer.int)
        assertEquals(0, buffer.int)
    }

    @Test
    fun partialHeaderWaitsForMoreBytes() {
        val frame = ExternalHuSdpProtocol.encodeFrame(ExternalHuSdpProtocol.CONNECT_TYPE)
        assertNull(ExternalHuSdpProtocol.decodeFrame(frame.copyOf(15)))
    }

    @Test
    fun bodyRoundTripsAndChecksumIsValidated() {
        val body = """{"status":0}""".toByteArray()
        val encoded = ExternalHuSdpProtocol.encodeFrame(ExternalHuSdpProtocol.REQUEST_BUILD_NET, body)
        val decoded = ExternalHuSdpProtocol.decodeFrame(encoded)

        assertEquals(ExternalHuSdpProtocol.REQUEST_BUILD_NET, decoded?.kind)
        assertArrayEquals(body, decoded?.body)
        encoded[7] = 0x7F.toByte()
        assertTrue(
            runCatching { ExternalHuSdpProtocol.decodeFrame(encoded) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun p2pAdvertisementIsClassifiedSeparatelyFromCompletion() {
        val p2p =
            """
            {"status":1,"carNetDeviceInfo":{"name":"HU-TEST","mac":"02:00:00:00:00:01","ssid":"DIRECT-x","pwd":"pass","mode":8}}
            """.trimIndent().toByteArray()
        val result = ExternalHuSdpProtocol.classifyBuildNet(p2p)

        assertEquals(ExternalHuSdpProtocol.Decision.P2P_STARTED, result.decision)
        assertEquals(8, result.network?.mode)
        assertEquals("DIRECT-x", result.network?.ssid)
    }

    @Test
    fun pendingAndSuccessAreDistinct() {
        assertEquals(
            ExternalHuSdpProtocol.Decision.WAIT,
            ExternalHuSdpProtocol.classifyBuildNet("""{"status":-1}""".toByteArray()).decision,
        )
        assertEquals(
            ExternalHuSdpProtocol.Decision.SUCCESS,
            ExternalHuSdpProtocol.classifyBuildNet("""{"status":0}""".toByteArray()).decision,
        )
    }

    @Test
    fun p2pModeOnlyStartsForNeedPhoneApStatus() {
        val body =
            """
            {"status":1,"carNetDeviceInfo":{"name":"HU-TEST","mac":"02:00:00:00:00:01","ssid":"DIRECT-x","mode":8}}
            """.trimIndent().toByteArray()

        val result = ExternalHuSdpProtocol.classifyBuildNet(body)

        assertEquals(ExternalHuSdpProtocol.Decision.P2P_STARTED, result.decision)
    }

    @Test
    fun successNeverBecomesP2pStartedEvenWithP2pNetwork() {
        val body =
            """
            {"status":0,"carNetDeviceInfo":{"name":"HU-TEST","mac":"02:00:00:00:00:01","ssid":"DIRECT-x","mode":8}}
            """.trimIndent().toByteArray()

        assertEquals(ExternalHuSdpProtocol.Decision.SUCCESS, ExternalHuSdpProtocol.classifyBuildNet(body).decision)
    }

    @Test
    fun nonP2pModeDoesNotReportP2pStarted() {
        val body =
            """
            {"status":1,"carNetDeviceInfo":{"name":"HU-TEST","mac":"02:00:00:00:00:01","ssid":"HU-AP","mode":1}}
            """.trimIndent().toByteArray()

        assertEquals(ExternalHuSdpProtocol.Decision.WAIT, ExternalHuSdpProtocol.classifyBuildNet(body).decision)
    }

    @Test
    fun carInfoBodyDoesNotLeakPassword() {
        val body =
            ExternalHuSdpProtocol.carInfoBody(
                ExternalHuSdpProtocol.HuAdvertisement(
                    network =
                        ExternalHuSdpProtocol.CarNetwork(
                            name = "HU-TEST",
                            mac = "02:00:00:00:00:01",
                            ssid = "DIRECT-x",
                            password = "secret",
                            mode = 8,
                        ),
                ),
            )
        val text = String(body)

        assertTrue(text.contains("\"mode\":8"))
        assertTrue(text.contains("\"packagename\":\"net.easyconn.carman.neutral\""))
        assertTrue(text.contains("\"flavor\":1"))
        assertTrue(text.contains("\"HUName\":\"HU-TEST\""))
        assertTrue(text.contains("\"HUID\":\"02:00:00:00:00:01\""))
        assertTrue(text.contains("\"status\":1"))
        assertTrue(!text.contains("secret"))
    }

    @Test
    fun buildNetFinishResponseUsesStatusZero() {
        val response =
            ExternalHuSdpProtocol.encodeFrame(
                ExternalHuSdpProtocol.BUILD_NET_FINISH + 1,
                ExternalHuSdpProtocol.joinJsonStatus(0),
            )
        val decoded = ExternalHuSdpProtocol.decodeFrame(response)

        assertEquals(0x00080031, decoded?.kind)
        assertEquals(0, ExternalHuSdpProtocol.statusOf(decoded?.body ?: ByteArray(0)))
    }

    @Test
    fun discoveryResponseUsesStringTrue() {
        val body = ExternalHuSdpProtocol.joinJsonStatus("true")

        assertEquals("true", org.json.JSONObject(String(body)).optString("status"))
    }

    @Test
    fun prefixMasksHandleBoundaryValues() {
        assertEquals("0.0.0.0", ExternalHuSdpProtocol.prefixToMask(0))
        assertEquals("255.255.255.0", ExternalHuSdpProtocol.prefixToMask(24))
        assertEquals("255.255.255.255", ExternalHuSdpProtocol.prefixToMask(32))
    }
}
