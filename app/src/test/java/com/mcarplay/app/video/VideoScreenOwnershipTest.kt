package com.mcarplay.app.video

import com.mcarplay.app.airplay.StreamFrameCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class VideoScreenOwnershipTest {
    @Test
    fun senderPausesWhileCarOwnsScreenAndResumesOnTheNextIdr() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val phoneOwnsScreen = AtomicBoolean(true)
            val keyFrameRequests = AtomicInteger()
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
                            override fun requestKeyFrame(): Boolean {
                                keyFrameRequests.incrementAndGet()
                                return true
                            }
                        },
                    phoneOwnsScreen = { phoneOwnsScreen.get() },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(0)))
                    awaitFrames(sender, 1)

                    phoneOwnsScreen.set(false)
                    assertTrue(sender.submitExternalFrame(pFrame(1)))
                    assertTrue(sender.submitExternalFrame(keyFrame(2)))
                    Thread.sleep(150)
                    assertEquals("No video frame may reach the car while it owns the screen", 1L, sender.framesSentTotal)

                    phoneOwnsScreen.set(true)
                    assertTrue(sender.submitExternalFrame(pFrame(3)))
                    Thread.sleep(150)
                    assertEquals("A P frame after screen return must wait for a new IDR", 1L, sender.framesSentTotal)
                    assertTrue("Screen return must ask CarWith for a fresh IDR", keyFrameRequests.get() > 0)

                    assertTrue(sender.submitExternalFrame(keyFrame(4)))
                    awaitFrames(sender, 2)
                    assertTrue("P frames may resume after the first new IDR", sender.submitExternalFrame(pFrame(5)))
                    awaitFrames(sender, 3)
                }
            } finally {
                sender.stop()
                assertTrue(ended.await(2, TimeUnit.SECONDS))
            }
        }
    }

    private fun awaitFrames(
        sender: VideoSender,
        expected: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && sender.framesSentTotal < expected) {
            Thread.sleep(10)
        }
        assertEquals(expected, sender.framesSentTotal)
    }

    private fun keyFrame(timestampUs: Long): ExternalVideoFrame =
        ExternalVideoFrame(
            accessUnit(byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())),
            keyFrame = true,
            timestampUs = timestampUs,
            sps = byteArrayOf(0x67, 0x42, 0, 0x1f),
            pps = byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte()),
        )

    private fun pFrame(timestampUs: Long): ExternalVideoFrame =
        ExternalVideoFrame(
            accessUnit(byteArrayOf(0x41, 0x9a.toByte(), 0x22)),
            keyFrame = false,
            timestampUs = timestampUs,
        )

    private fun accessUnit(slice: ByteArray): ByteArray =
        listOf(
            byteArrayOf(0x67, 0x42, 0, 0x1f),
            byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte()),
            slice,
        ).fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
}
