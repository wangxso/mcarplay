package com.mcarplay.app.diagnostics

import com.mcarplay.app.airplay.StreamFrameCipher
import com.mcarplay.app.video.ExternalVideoFrame
import com.mcarplay.app.video.ExternalVideoSource
import com.mcarplay.app.video.VideoSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoDiagnosticsTransportTest {
    @Test
    fun connectionAloneIsNotAFrameAndObserverFailureCannotBreakVideo() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 2000
            val observed = CountDownLatch(2)
            val ended = CountDownLatch(1)
            val sender =
                VideoSender(
                    "127.0.0.1",
                    receiver.localPort,
                    StreamFrameCipher.ChaCha(ByteArray(32)),
                    640,
                    480,
                    log = {},
                    onEnded = { ended.countDown() },
                    externalVideoSource =
                        object : ExternalVideoSource {
                            override fun requestKeyFrame(): Boolean = true
                        },
                    onExternalFrameSent = {
                        observed.countDown()
                        throw IllegalStateException("telemetry failure")
                    },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use { socket ->
                    socket.soTimeout = 2000
                    assertEquals(0L, sender.framesSentTotal)
                    val accessUnit =
                        listOf(
                            0,
                            0,
                            0,
                            1,
                            0x67,
                            0x42,
                            0,
                            0x1f,
                            0,
                            0,
                            0,
                            1,
                            0x68,
                            0xce,
                            6,
                            0xe2,
                            0,
                            0,
                            0,
                            1,
                            0x65,
                            0x88,
                            0x84,
                        ).map { it.toByte() }.toByteArray()
                    repeat(2) { assertTrue(sender.submitExternalFrame(ExternalVideoFrame(accessUnit, true, it * 50_000L))) }
                    val input = DataInputStream(socket.getInputStream())
                    repeat(3) { index ->
                        val header = ByteArray(128)
                        input.readFully(header)
                        val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                        assertTrue(length in 1..4096)
                        input.readFully(ByteArray(length))
                        assertEquals(if (index == 0) 1 else 0, header[4].toInt())
                    }
                    assertTrue(observed.await(2, TimeUnit.SECONDS))
                    assertEquals(2L, sender.framesSentTotal)
                }
            } finally {
                sender.stop()
                assertTrue(ended.await(2, TimeUnit.SECONDS))
            }
        }
    }
}
