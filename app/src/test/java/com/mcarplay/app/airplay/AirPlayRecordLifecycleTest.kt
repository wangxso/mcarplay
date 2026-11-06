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

    internal class Connection(
        val socket: Socket,
    ) {
        @Volatile
        var cipher: ControlCipher? = null
    }

    internal class Request(
        val method: String,
        val path: String,
        val cseq: String,
        val connection: Connection,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun respond(
            status: Int,
            body: ByteArray = ByteArray(0),
        ) {
            synchronized(connection) {
                val plaintext =
                    "RTSP/1.0 $status response\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
                connection.socket.getOutputStream().apply {
                    write(connection.cipher?.encrypt(plaintext) ?: plaintext)
                    flush()
                }
            }
        }
    }

    /** RECORD、配对及媒体协商共用的真实 TCP 回环；加密方向由接收端派生密钥配置。 */
    internal class Peer : Closeable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val connections = CopyOnWriteArrayList<Connection>()
        private val received = LinkedBlockingQueue<Request>()
        val port: Int get() = server.localPort

        @Volatile
        var reply: (Request) -> Unit = { it.respond(200) }

        init {
            executor.execute {
                try {
                    while (!server.isClosed) {
                        val connection = Connection(server.accept())
                        connections += connection
                        executor.execute { readRequests(connection) }
                    }
                } catch (_: SocketException) {
                    // 关闭测试服务器。
                }
            }
        }

        private fun readRequests(connection: Connection) {
            try {
                val socket = connection.socket
                val input = socket.getInputStream()
                var plaintext = ByteArray(0)
                var offset = 0

                fun readByte(): Int {
                    val cipher = connection.cipher ?: return input.read()
                    while (offset == plaintext.size) {
                        val head = input.readNBytes(2)
                        if (head.size != 2) throw EOFException()
                        val length = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
                        val encrypted = input.readNBytes(length + 16)
                        if (encrypted.size != length + 16) throw EOFException()
                        plaintext = cipher.decrypt(head + encrypted).data
                        offset = 0
                    }
                    return plaintext[offset++].toInt() and 0xFF
                }

                while (!socket.isClosed) {
                    val header = ByteArrayOutputStream()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) {
                        val next = readByte()
                        if (next < 0) throw EOFException()
                        header.write(next)
                        tail = (tail + next.toChar()).takeLast(4)
                        check(header.size() <= 16_384)
                    }
                    val lines = header.toString("UTF-8").split("\r\n")
                    val first = lines.first().split(" ")
                    val headers =
                        lines.drop(1).filter { ':' in it }.associate {
                            it.substringBefore(':').lowercase() to
                                it.substringAfter(':').trim()
                        }
                    val body =
                        ByteArray(headers.getValue("content-length").toInt()) {
                            val next = readByte()
                            if (next < 0) throw EOFException()
                            next.toByte()
                        }
                    val request = Request(first[0], first[1], headers.getValue("cseq"), connection, headers, body)
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
            connections.forEach { runCatching { it.socket.close() } }
            executor.shutdownNow()
        }
    }
}
