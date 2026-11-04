package com.mcarplay.app.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WsClientTest {
    /**
     * PR #30 评审：静默断链时对端不再读，发送缓冲写满后 write 无限期阻塞并占住
     * writeLock；读超时远未到，重连循环回不来。写入期限到后必须关闭 socket：
     * 阻塞的 sendText 返回 false，读循环随之收尾，connect() 返回。
     */
    @Test(timeout = 20_000)
    fun blockedWriteIsAbortedByTheWriteDeadline() {
        val server = ServerSocket()
        server.receiveBufferSize = 4 * 1024
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val accepted = CountDownLatch(1)
        val serverThread =
            thread(isDaemon = true) {
                val peer = server.accept()
                // 读完握手、回 101，然后再也不读
                val input = peer.getInputStream()
                val tail = ArrayDeque<Int>()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    tail.addLast(b)
                    if (tail.size > 4) tail.removeFirst()
                    if (tail.toList() == listOf(13, 10, 13, 10)) break
                }
                peer.getOutputStream().apply {
                    write("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                accepted.countDown()
                // 测试结束时被 interrupt 唤醒
                runCatching { Thread.sleep(60_000) }
                peer.close()
            }

        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val client =
            WsClient(
                "ws://127.0.0.1:${server.localPort}/",
                object : WsClient.Listener {
                    override fun onOpen() = opened.countDown()

                    override fun onText(text: String) = Unit

                    override fun onClosed(reason: String) = closed.countDown()
                },
                readTimeoutMs = 60_000,
                writeTimeoutMs = 500,
            )
        val connector = thread(isDaemon = true) { runCatching { client.connect() } }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertTrue(accepted.await(5, TimeUnit.SECONDS))

        // 远超 loopback 收发缓冲：对端不读时必然阻塞
        val big = "x".repeat(4_000_000)
        val startedAt = System.nanoTime()
        var ok = true
        repeat(8) { if (ok) ok = client.sendText(big) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertFalse("a write to a peer that stopped reading must fail", ok)
        assertTrue("write deadline must fire well before the read timeout (took ${elapsedMs}ms)", elapsedMs < 10_000)
        assertTrue("read loop must end once the socket is closed", closed.await(5, TimeUnit.SECONDS))
        connector.join(5_000)
        assertFalse("connect() must return so the bridge can reconnect", connector.isAlive)

        serverThread.interrupt()
        server.close()
    }
}
