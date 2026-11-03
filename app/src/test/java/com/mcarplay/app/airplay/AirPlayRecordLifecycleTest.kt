package com.mcarplay.app.airplay

import com.mcarplay.app.audio.AudioProfile
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 实际 RTSP 回环：检查发送数量、停止和重连，而非仅检查策略枚举。 */
class AirPlayRecordLifecycleTest {
    @Test
    fun observedCheryStackSendsOnlyOneRecordPerSession() {
        Peer().use { peer ->
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                assertTrue(client.record())
                assertTrue(client.record()) // 音频 SETUP 后或动态补启
                assertEquals("RECORD", peer.next()!!.method)
                assertNull(peer.next(300))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun concurrentRecordCallersShareTheInFlightRequest() {
        Peer().use { peer ->
            peer.reply = {}
            val client = client(peer.port)
            val executor = Executors.newFixedThreadPool(2)
            try {
                assertTrue(client.reconnect())
                val first = executor.submit(Callable { client.record(2_000) })
                val request = peer.next()!!
                val calling = CountDownLatch(1)
                val second =
                    executor.submit(
                        Callable {
                            calling.countDown()
                            client.record(2_000)
                        },
                    )
                assertTrue(calling.await(1, TimeUnit.SECONDS))
                request.respond(200)
                assertTrue(first.get(2, TimeUnit.SECONDS))
                assertTrue(second.get(2, TimeUnit.SECONDS))
                assertEquals("RECORD", request.method)
                assertNull(peer.next(300))
            } finally {
                client.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun timeoutAndLateSuccessDoNotPermitAnotherRecordOnTheSameConnection() {
        Peer().use { peer ->
            peer.reply = {}
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                assertFalse(client.record(150))
                val request = peer.next()!!
                request.respond(200) // 车机可能已启动，只是应答晚于本端超时
                assertFalse(client.record(150))
                assertFalse(client.sessionActive)
                assertNull(peer.next(300))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun closeWakesWaitingRecordCallersAndAllowsOneOnANewConnection() {
        Peer().use { peer ->
            peer.reply = {}
            val client = client(peer.port)
            val executor = Executors.newFixedThreadPool(2)
            try {
                assertTrue(client.reconnect())
                val first = executor.submit(Callable { client.record(5_000) })
                val old = peer.next()!!
                val waiting = CountDownLatch(1)
                val second =
                    executor.submit(
                        Callable {
                            waiting.countDown()
                            client.record(5_000)
                        },
                    )
                assertTrue(waiting.await(1, TimeUnit.SECONDS))
                client.close()
                assertFalse(first.get(2, TimeUnit.SECONDS))
                assertFalse(second.get(2, TimeUnit.SECONDS))
                peer.reply = { it.respond(200) }
                assertTrue(client.reconnect())
                runCatching { old.respond(200) }
                assertTrue(client.record())
                val current = peer.next()!!
                assertEquals("RECORD", current.method)
                assertTrue(current.connection != old.connection)
                assertTrue(client.sessionActive)
                assertNull(peer.next(300))
            } finally {
                client.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun otherReceiversRetainTheirExistingRepeatedRecordBehavior() {
        Peer().use { peer ->
            val client = client(peer.port, chery = false)
            try {
                assertTrue(client.reconnect())
                assertTrue(client.record())
                assertTrue(client.record())
                assertEquals("RECORD", peer.next()!!.method)
                assertEquals("RECORD", peer.next()!!.method)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun rejectedRecordCannotBeRepeatedInTheSameSession() {
        Peer().use { peer ->
            peer.reply = { it.respond(500) }
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                assertFalse(client.record())
                assertFalse(client.record())
                assertEquals("RECORD", peer.next()!!.method)
                assertNull(peer.next(300))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun oldModesAndAudioTasksCannotSendOnAReplacementConnection() {
        Peer().use { peer ->
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                val old = client.connectionGeneration
                client.close()
                assertTrue(client.reconnect())
                assertFalse(client.assertModes(expectedEpoch = old))
                assertNull(client.runAudioStream(ByteArray(32), AudioProfile("media", 1L shl 15, 48_000, 25), expectedEpoch = old))
                client.postControlIap(byteArrayOf(1), expectedEpoch = old)
                assertNull(peer.next(300))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun stoppingRejectsBusinessRequestsAndLetsTeardownProceed() {
        Peer().use { peer ->
            peer.reply = { if (it.path != "/feedback") it.respond(200) }
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                assertTrue(client.record())
                assertEquals("RECORD", peer.next()!!.method)
                client.startFeedbackLoop()
                val feedback = peer.next()!!
                assertEquals("/feedback", feedback.path)
                client.beginOrderlyStop()
                assertFalse(client.record())
                client.postControlIap(byteArrayOf(1)) // 迟到的普通 iAP 帧不应穿过 STOPPING
                assertNull(peer.next(300))
                assertTrue(
                    client.sendOrderlyIapAnnouncement {
                        client.postControlIap(byteArrayOf(2))
                        true
                    },
                )
                assertEquals("/command", peer.next()!!.path)
                assertTrue(client.stopLikeIphone())
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertEquals("TEARDOWN", peer.next()!!.method)
                assertNull(peer.next(300))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun oldFeedbackLoopDoesNotFollowAReconnectedSocket() {
        Peer().use { peer ->
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                client.startFeedbackLoop()
                assertEquals("/feedback", peer.next()!!.path)
                client.close()
                assertTrue(client.reconnect())
                // 不在新连接启动 feedback：旧线程即使醒来，也不得发送。
                assertNull(peer.next(1_300))
            } finally {
                client.close()
            }
        }
    }

    private fun client(
        port: Int,
        chery: Boolean = true,
    ): AirPlayClient {
        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "test",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = port,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = if (chery) "320.17.7" else null,
                ipAddresses = listOf("127.0.0.1"),
                receiverSignals =
                    if (chery) ReceiverProfile.Signals(manufacturer = "CHERY", model = "Tiggo") else ReceiverProfile.Signals(),
            )
        return AirPlayClient(endpoint, log = {})
    }

    private class Request(
        val method: String,
        val path: String,
        val cseq: String,
        val connection: Socket,
    ) {
        fun respond(status: Int) {
            synchronized(connection) {
                connection.getOutputStream().apply {
                    write("RTSP/1.0 $status response\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    flush()
                }
            }
        }
    }

    private class Peer : Closeable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val connections = CopyOnWriteArrayList<Socket>()
        private val received = LinkedBlockingQueue<Request>()
        val port: Int get() = server.localPort

        @Volatile
        var reply: (Request) -> Unit = { it.respond(200) }

        init {
            executor.execute {
                try {
                    while (!server.isClosed) {
                        val socket = server.accept()
                        connections += socket
                        executor.execute { readRequests(socket) }
                    }
                } catch (_: SocketException) {
                    // 关闭测试服务器。
                }
            }
        }

        private fun readRequests(socket: Socket) {
            try {
                val input = socket.getInputStream()
                while (!socket.isClosed) {
                    val header = ByteArrayOutputStream()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        if (next < 0) throw EOFException()
                        header.write(next)
                        tail = (tail + next.toChar()).takeLast(4)
                    }
                    val lines = header.toString("UTF-8").split("\r\n")
                    val first = lines.first().split(" ")
                    val headers =
                        lines.drop(1).filter { ':' in it }.associate {
                            it.substringBefore(':').lowercase() to
                                it.substringAfter(':').trim()
                        }
                    val body = ByteArray(headers["content-length"]!!.toInt())
                    var offset = 0
                    while (offset < body.size) {
                        val count = input.read(body, offset, body.size - offset)
                        if (count < 0) throw EOFException()
                        offset += count
                    }
                    val request = Request(first[0], first[1], headers["cseq"]!!, socket)
                    received.put(request)
                    reply(request)
                }
            } catch (_: java.io.IOException) {
                // 客户端断开是正常结束。
            }
        }

        fun next(timeoutMillis: Long = 2_000): Request? = received.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        override fun close() {
            server.close()
            connections.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
