package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 2026-11-02 艾瑞泽 8 车机日志里真 iPhone 的断开顺序：先拆屏幕流，再拆整个会话。 */
class AirPlayOrderlyStopTest {
    private data class Request(
        val method: String,
        val body: ByteArray,
    )

    @Test
    fun stopsScreenStreamThenSessionLikeIphone() {
        val requests =
            exchange(listOf(200, 200, 200)) { client ->
                assertTrue(client.record())
                assertTrue(client.sessionActive)
                assertTrue(client.stopLikeIphone())
            }
        assertEquals(listOf("RECORD", "TEARDOWN", "TEARDOWN"), requests.map { it.method })
        assertEquals(mapOf("streams" to listOf(mapOf("type" to 110L))), BinaryPlist.parse(requests[1].body))
        assertEquals(0, requests[2].body.size)
    }

    @Test
    fun unconfirmedTeardownLetsCallerFallBackToLeavingTheHotspot() {
        val requests =
            exchange(listOf(200, 200, 500)) { client ->
                assertTrue(client.record())
                assertFalse(client.stopLikeIphone())
            }
        assertEquals(3, requests.size)
    }

    @Test
    fun inactiveSessionSendsNothing() {
        val requests =
            exchange(emptyList()) { client ->
                assertFalse(client.sessionActive)
                assertFalse(client.stopLikeIphone())
            }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun disableBluetoothCarriesLowercaseDeviceId() {
        val client = AirPlayClient(endpoint(0), log = {})
        val body = BinaryPlist.parse(client.disableBluetoothBody("02:AB:CD:12:34:56"))
        assertEquals(mapOf("type" to "disableBluetooth", "params" to mapOf("deviceID" to "02:ab:cd:12:34:56")), body)
    }

    /** 无 context 时取不到本机蓝牙地址：不发请求，也不把随后的蓝牙断开当成预期。 */
    @Test
    fun placeholderIdentityNeverReleasesBluetooth() {
        val requests =
            exchange(emptyList()) { client ->
                assertFalse(client.releaseReceiverBluetooth())
                assertFalse(client.bluetoothReleaseRequested)
            }
        assertTrue(requests.isEmpty())
    }

    private fun endpoint(port: Int) = Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, port, null, null, null, listOf("127.0.0.1"))

    private fun exchange(
        statuses: List<Int>,
        run: (AirPlayClient) -> Unit,
    ): List<Request> {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val received =
                executor.submit(
                    Callable {
                        server.accept().use { peer ->
                            peer.soTimeout = 5_000
                            val input = peer.getInputStream()
                            statuses.map { status ->
                                val header = ByteArrayOutputStream()
                                var tail = ""
                                while (!tail.endsWith("\r\n\r\n")) {
                                    val next = input.read()
                                    check(next >= 0)
                                    header.write(next)
                                    tail = (tail + next.toChar()).takeLast(4)
                                }
                                val lines = header.toString("UTF-8").split("\r\n")
                                val headers =
                                    lines.drop(1).filter { ':' in it }.associate { line ->
                                        line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                                    }
                                val body = ByteArray(headers["content-length"]!!.toInt())
                                var offset = 0
                                while (offset < body.size) {
                                    val count = input.read(body, offset, body.size - offset)
                                    check(count > 0)
                                    offset += count
                                }
                                peer.getOutputStream().apply {
                                    write(
                                        "RTSP/1.0 $status response\r\nCSeq: ${headers["cseq"]}\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                                    )
                                    flush()
                                }
                                Request(lines.first().substringBefore(' '), body)
                            }
                        }
                    },
                )
            val client = AirPlayClient(endpoint(server.localPort), log = {})
            try {
                assertTrue(client.reconnect())
                run(client)
                return received.get(10, TimeUnit.SECONDS)
            } finally {
                client.close()
                executor.shutdownNow()
            }
        }
    }
}
