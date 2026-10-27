package com.mcarplay.app.airplay

import com.mcarplay.app.compat.InfoRequest
import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceiverDetectionInfoTest {
    @Test
    fun detectingReceiverUsesPostOnlyAfterExplicitGetMethodRejection() {
        exchange(listOf(405, 200), InfoRequest.GET_EMPTY_PLIST, true) { client ->
            assertNotNull(client.loadInfoWithDiagnostics())
            assertEquals(InfoRequest.POST_EMPTY_PLIST, client.negotiatedInfoRequest)
        }.also { assertEquals(listOf("GET", "POST"), it) }
    }

    @Test
    fun cachedPostCanRecoverToGetWhenReceiverChanges() {
        exchange(listOf(405, 200), InfoRequest.POST_EMPTY_PLIST, false) { client ->
            assertNotNull(client.loadInfoWithDiagnostics())
            assertEquals(InfoRequest.GET_EMPTY_PLIST, client.negotiatedInfoRequest)
        }.also { assertEquals(listOf("POST", "GET"), it) }
    }

    @Test
    fun forbiddenAndInvalidStateNeverEnablePostOrExtraSideConnection() {
        for (code in listOf(403, 455)) {
            exchange(listOf(code, code), InfoRequest.GET_EMPTY_PLIST, true) { client ->
                assertNull(client.loadInfoWithDiagnostics())
                assertEquals(InfoRequest.GET_EMPTY_PLIST, client.negotiatedInfoRequest)
            }.also { assertEquals(listOf("GET", "GET"), it) }
        }
    }

    private fun exchange(
        statuses: List<Int>,
        initial: InfoRequest,
        detecting: Boolean,
        run: (AirPlayClient) -> Unit,
    ): List<String> {
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
                                    check(header.size() <= 16_384)
                                }
                                val lines = header.toString("UTF-8").split("\r\n")
                                val headers =
                                    lines.drop(1).filter { ':' in it }.associate { line ->
                                        line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                                    }
                                val length = headers["content-length"]!!.toInt()
                                val body = ByteArray(length)
                                var offset = 0
                                while (offset < length) {
                                    val count = input.read(body, offset, length - offset)
                                    check(count > 0)
                                    offset += count
                                }
                                assertEquals("application/x-apple-binary-plist", headers["content-type"])
                                assertEquals(emptyMap<String, Any>(), BinaryPlist.parse(body))
                                val response =
                                    if (status == 200) {
                                        val display = mapOf("widthPixels" to 1920L, "heightPixels" to 1080L)
                                        BinaryPlist.encode(mapOf("displays" to listOf(display)))
                                    } else {
                                        ByteArray(0)
                                    }
                                peer.getOutputStream().apply {
                                    val responseHeader =
                                        "RTSP/1.0 $status response\r\nCSeq: ${headers["cseq"]}\r\n" +
                                            "Content-Length: ${response.size}\r\n\r\n"
                                    write(responseHeader.toByteArray())
                                    write(response)
                                    flush()
                                }
                                lines.first().substringBefore(' ')
                            }
                        }
                    },
                )
            val client =
                AirPlayClient(
                    Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, server.localPort, null, null, null, listOf("127.0.0.1")),
                    log = {},
                    initialInfoRequest = initial,
                    detecting = detecting,
                )
            try {
                assertTrue(client.reconnect())
                run(client)
                return received.get(5, TimeUnit.SECONDS)
            } finally {
                client.close()
                executor.shutdownNow()
            }
        }
    }
}
