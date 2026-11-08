package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 控制通道在请求等待应答时被对端关闭：请求必须立刻收到关闭标记，而不是等满 RTSP 超时。 */
class AirPlayPendingCloseTest {
    @Test
    fun pendingRecordFailsFastWhenControlChannelCloses() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val closed =
                executor.submit(
                    Callable {
                        server.accept().use { peer ->
                            peer.soTimeout = 5_000
                            readRequest(peer.getInputStream())
                        }
                    },
                )
            val logs = Collections.synchronizedList(mutableListOf<String>())
            val client = AirPlayClient(endpoint(server.localPort), log = { logs += it })
            try {
                assertTrue(client.reconnect())
                val started = System.nanoTime()
                assertFalse(client.record())
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("关闭后应快速失败，实际 ${elapsedMs}ms", elapsedMs < 3_000)
                assertTrue("日志应包含控制通道关闭：$logs", logs.any { "控制通道已关闭" in it })
                closed.get(5, TimeUnit.SECONDS)
            } finally {
                client.close()
                executor.shutdownNow()
            }
        }
    }

    private fun endpoint(port: Int) = Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, port, null, null, null, listOf("127.0.0.1"))

    private fun readRequest(input: InputStream) {
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
        val body = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            check(count > 0)
            offset += count
        }
    }
}
