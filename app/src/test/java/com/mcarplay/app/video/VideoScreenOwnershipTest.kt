package com.mcarplay.app.video

import com.mcarplay.app.airplay.StreamFrameCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class VideoScreenOwnershipTest {
    @Test
    fun senderPausesWhileCarOwnsScreenAndRetriesTheIdrRequest() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val phoneOwnsScreen = AtomicBoolean(true)
            val nowMs = AtomicLong()
            val keyFrameRequestCount = AtomicInteger()
            val firstRequestFailed = CountDownLatch(1)
            val secondRequestSucceeded = CountDownLatch(1)
            val screenPaused = CountDownLatch(1)
            val screenResumed = CountDownLatch(1)
            val screenStates = CopyOnWriteArrayList<Boolean>()
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
                            override fun requestKeyFrame(): Boolean =
                                when (keyFrameRequestCount.incrementAndGet()) {
                                    1 -> {
                                        firstRequestFailed.countDown()
                                        false
                                    }

                                    2 -> {
                                        secondRequestSucceeded.countDown()
                                        true
                                    }

                                    else -> true
                                }
                        },
                    phoneOwnsScreen = { phoneOwnsScreen.get() },
                    onScreenOutputSuspended = { suspended ->
                        screenStates.add(suspended)
                        if (suspended) {
                            screenPaused.countDown()
                        } else if (screenPaused.count == 0L) {
                            screenResumed.countDown()
                        }
                    },
                    uptimeMs = { nowMs.get() },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(0)))
                    awaitFrames(sender, 1)

                    phoneOwnsScreen.set(false)
                    assertTrue(sender.submitExternalFrame(pFrame(1)))
                    assertTrue(sender.submitExternalFrame(keyFrame(2)))
                    awaitSkipped(sender, 2)
                    assertTrue(screenPaused.await(2, TimeUnit.SECONDS))
                    assertEquals("No video frame may reach the car while it owns the screen", 1L, sender.framesSentTotal)

                    phoneOwnsScreen.set(true)
                    nowMs.set(1_000)
                    assertTrue(sender.submitExternalFrame(pFrame(3)))
                    assertTrue(firstRequestFailed.await(2, TimeUnit.SECONDS))
                    awaitSkipped(sender, 3)
                    assertEquals("A P frame after screen return must wait for a new IDR", 1L, sender.framesSentTotal)

                    nowMs.addAndGet(ScreenOutputGate.DEFAULT_KEY_FRAME_RETRY_MS)
                    assertTrue(sender.submitExternalFrame(pFrame(4)))
                    assertTrue("A failed IDR request must be retried", secondRequestSucceeded.await(2, TimeUnit.SECONDS))
                    awaitSkipped(sender, 4)
                    assertEquals("Retrying the IDR request must not leak video frames", 1L, sender.framesSentTotal)

                    assertTrue(sender.submitExternalFrame(keyFrame(5)))
                    awaitFrames(sender, 2)
                    assertTrue("Screen diagnostics must resume only after the fresh IDR", screenResumed.await(2, TimeUnit.SECONDS))
                    assertTrue(screenStates.contains(true))
                    assertFalse(screenStates.last())
                    assertTrue("P frames may resume after the first new IDR", sender.submitExternalFrame(pFrame(6)))
                    awaitFrames(sender, 3)
                }
            } finally {
                sender.stop()
                assertTrue(ended.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun encoderPathResumesWithAFreshVideoConfig() {
        val ownsScreen = AtomicBoolean(false)
        val sender = admissionOnlySender(ownsScreen)

        assertFalse(sender.admitScreenFrame(keyFrame = true, passthrough = false))
        assertFalse("Pausing must not arm a resume reprime", sender.isEncoderReprimePending)

        ownsScreen.set(true)
        assertFalse("P frames after return must wait for a fresh IDR", sender.admitScreenFrame(keyFrame = false, passthrough = false))
        assertTrue("Encoder path must re-send VideoConfig when output resumes", sender.isEncoderReprimePending)
        assertFalse("Encoder path must not arm the passthrough parameter-set reprime", sender.isPassthroughReprimePending)

        assertTrue(sender.admitScreenFrame(keyFrame = true, passthrough = false))
    }

    private fun admissionOnlySender(phoneOwnsScreen: AtomicBoolean): VideoSender =
        VideoSender(
            "127.0.0.1",
            1,
            StreamFrameCipher.ChaCha(ByteArray(32)),
            640,
            480,
            log = {},
            externalVideoSource =
                object : ExternalVideoSource {
                    override fun requestKeyFrame(): Boolean = true
                },
            phoneOwnsScreen = { phoneOwnsScreen.get() },
            uptimeMs = { 0 },
        )

    private fun awaitSkipped(
        sender: VideoSender,
        expected: Long,
    ) {
        awaitCondition { sender.screenFramesSkippedTotal >= expected }
        assertEquals(expected, sender.screenFramesSkippedTotal)
    }

    private fun awaitFrames(
        sender: VideoSender,
        expected: Long,
    ) {
        awaitCondition { sender.framesSentTotal >= expected }
        assertEquals(expected, sender.framesSentTotal)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && !condition()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for video sender state", condition())
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
