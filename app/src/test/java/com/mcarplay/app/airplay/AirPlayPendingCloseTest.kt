package com.mcarplay.app.airplay

import com.mcarplay.app.CtrlInviteEvidence
import com.mcarplay.app.EndedScreen
import com.mcarplay.app.SessionHandoffOwnership
import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.video.ExternalVideoSource
import com.mcarplay.app.video.VideoSender
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 控制通道在请求等待应答时被对端关闭：请求必须立刻收到关闭标记，而不是等满 RTSP 超时。 */
class AirPlayPendingCloseTest {
    @Test
    fun screenEofDuringAudioSetupDefersTheSingleInviteUntilHandoffRelease() {
        val executor = Executors.newSingleThreadExecutor()
        val handoff = SessionHandoffOwnership(AtomicBoolean(false))
        val owner = ReceiverObservation("receiver", epoch = 1, session = 1)
        val nextOwner = owner.copy(session = 2)
        val ended = CompletableFuture<EndedScreen<String>>()
        val reconnected = CompletableFuture<CtrlInviteEvidence<String>>()
        val key = ByteArray(32)

        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
                receiver.soTimeout = 5_000
                val client = AirPlayClient(endpoint(receiver.localPort), log = {})
                val sender =
                    VideoSender(
                        "127.0.0.1",
                        receiver.localPort,
                        StreamFrameCipher.ChaCha(key),
                        640,
                        480,
                        log = {},
                        externalVideoSource =
                            object : ExternalVideoSource {
                                override fun requestKeyFrame() = false
                            },
                        onEnded = { ended.complete(EndedScreen(client, null, handoff)) },
                    )
                try {
                    assertTrue(handoff.begin(owner))
                    assertTrue(client.reconnect())
                    receiver.accept().use { control ->
                        control.soTimeout = 5_000
                        val record = executor.submit<Boolean> { client.record() }
                        assertTrue(readRequest(control.getInputStream()).startsWith("RECORD "))
                        control.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        assertTrue(record.get(5, TimeUnit.SECONDS))

                        assertTrue(sender.start {})
                        receiver.accept().use { screen ->
                            val audio =
                                executor.submit(
                                    Callable {
                                        try {
                                            client.runAudioStream(key, client.audioCandidates().single())
                                        } finally {
                                            check(handoff.endIfOwnedBy(owner))
                                        }
                                    },
                                )
                            assertTrue(readRequest(control.getInputStream()).startsWith("SETUP "))
                            screen.shutdownOutput()
                            val endedScreen = ended.get(5, TimeUnit.SECONDS)
                            assertFalse(sender.isRunning)

                            fun reconnect(invite: CtrlInviteEvidence<String>) {
                                if (endedScreen.beginReconnect(nextOwner, invite, ::reconnect)) reconnected.complete(invite)
                            }

                            val invite = CtrlInviteEvidence(1L, owner.binding, "127.0.0.1", "car-wifi", owner)
                            reconnect(invite)
                            assertFalse(audio.isDone)
                            assertFalse(reconnected.isDone)
                            assertTrue(handoff.owns(owner))

                            control.getOutputStream().write("RTSP/1.0 453 Busy\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            assertNull(audio.get(5, TimeUnit.SECONDS))
                            assertSame(invite, reconnected.get(5, TimeUnit.SECONDS))
                            assertTrue(handoff.endIfOwnedBy(nextOwner))
                        }
                    }
                } finally {
                    client.close()
                    sender.stop()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun controlChannelCloseNotifiesAfterLastRequestCompletes() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val closePeer = CountDownLatch(1)
            val controlClosed = CountDownLatch(1)
            val peerTask =
                executor.submit {
                    server.accept().use { peer ->
                        peer.soTimeout = 5_000
                        readRequest(peer.getInputStream())
                        peer.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        check(closePeer.await(5, TimeUnit.SECONDS))
                    }
                }
            val client = AirPlayClient(endpoint(server.localPort), log = {})
            client.onControlChannelClosed = { controlClosed.countDown() }
            try {
                assertTrue(client.reconnect())
                assertTrue(client.record())
                assertFalse(client.controlChannelClosed)
                closePeer.countDown()
                assertTrue(controlClosed.await(5, TimeUnit.SECONDS))
                assertTrue(client.controlChannelClosed)
                peerTask.get(5, TimeUnit.SECONDS)
            } finally {
                closePeer.countDown()
                client.close()
                executor.shutdownNow()
            }
        }
    }

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
            val controlClosed = CountDownLatch(1)
            client.onControlChannelClosed = { controlClosed.countDown() }
            try {
                assertTrue(client.reconnect())
                val started = System.nanoTime()
                assertFalse(client.record())
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("关闭后应快速失败，实际 ${elapsedMs}ms", elapsedMs < 3_000)
                val entries = synchronized(logs) { logs.toList() }
                assertTrue("日志应包含控制通道关闭：$entries", entries.any { "控制通道已关闭" in it })
                assertTrue(controlClosed.await(5, TimeUnit.SECONDS))
                assertTrue(client.controlChannelClosed)
                closed.get(5, TimeUnit.SECONDS)
            } finally {
                client.close()
                executor.shutdownNow()
            }
        }
    }

    private fun endpoint(port: Int) = Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, port, null, null, null, listOf("127.0.0.1"))

    private fun readRequest(input: InputStream): String {
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
        return lines.first()
    }
}
