package com.mcarplay.app.video

import com.mcarplay.app.airplay.AirPlayCrypto
import com.mcarplay.app.airplay.StreamFrameCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 透传队列缩短到 8 帧后的端到端行为：实际写进数据口的帧顺序、队满补要关键帧的时机，
 * 以及并发关闭时不把网络请求留在 CarLife 读线程上。
 *
 * 队列满时如果直接丢掉新 IDR、或让某个更早的旧关键帧留在队首，车机会把旧恢复点当新边界
 * 去解码一串缺参考的帧——本测试用真实 socket 上的解密结果钉住这一点。
 */
class VideoPassthroughQueueTest {
    private val key = ByteArray(32) { (it * 13 + 7).toByte() }

    @Test
    fun aNewerIdrReplacesQueuedStaleFramesAndIsTheOnlyRecoveryPoint() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val gateEntered = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val keyFrameRequests = AtomicInteger()
            val requestThreads = CopyOnWriteArrayList<String>()
            val sender =
                sender(
                    receiver = receiver,
                    externalSource = {
                        keyFrameRequests.incrementAndGet()
                        requestThreads.add(Thread.currentThread().name)
                        true
                    },
                    phoneOwnsScreen = { blockOnce(gateEntered, releaseGate) },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use { car ->
                    car.soTimeout = 2_000

                    // 第一帧把推流线程卡在入流门控里：此后队列只进不出。
                    assertTrue(sender.submitExternalFrame(keyFrame(0)))
                    assertTrue(gateEntered.await(2, TimeUnit.SECONDS))

                    repeat(PassthroughFrameQueue.DEFAULT_CAPACITY) { index ->
                        assertTrue(sender.submitExternalFrame(pFrame(1 + index)))
                    }
                    assertFalse("a P frame must be dropped once the queue is full", sender.submitExternalFrame(pFrame(50)))
                    awaitCondition { keyFrameRequests.get() == 1 }
                    assertEquals("overflow must request exactly once", 1, keyFrameRequests.get())
                    assertTrue(
                        "overflow must not request on the producer thread",
                        requestThreads.single().contains("video-overflow-keyframe"),
                    )

                    // 紧接着到来的新 IDR 必须替换全部旧帧并且不被丢弃。
                    assertTrue("a key frame must never be dropped", sender.submitExternalFrame(keyFrame(99)))

                    releaseGate.countDown()

                    val frames = readVideoFrames(car, expected = 2)
                    assertEquals("only the blocked frame and the newest IDR may be written", 2, frames.size)
                    assertEquals(listOf(0, 99), frames.map { it.sliceTag })
                }
            } finally {
                releaseGate.countDown()
                sender.stop()
            }
        }
    }

    @Test
    fun overflowKeyFrameRequestsAreThrottledToOnePerSecond() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val gateEntered = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val clock = AtomicLong()
            val keyFrameRequests = AtomicInteger()
            val sender =
                sender(
                    receiver = receiver,
                    externalSource = {
                        keyFrameRequests.incrementAndGet()
                        true
                    },
                    phoneOwnsScreen = { blockOnce(gateEntered, releaseGate) },
                    uptimeMillis = { clock.get() },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(0)))
                    assertTrue(gateEntered.await(2, TimeUnit.SECONDS))
                    fillQueue(sender)
                    repeat(20) { sender.submitExternalFrame(pFrame(100 + it)) }
                    awaitCondition { keyFrameRequests.get() == 1 }

                    // 同一秒内的连续队满不再发请求
                    clock.addAndGet(OverflowKeyFrameRecovery.DEFAULT_INTERVAL_MS - 1)
                    repeat(20) { sender.submitExternalFrame(pFrame(200 + it)) }
                    Thread.sleep(150)
                    assertEquals("overflow requests must be throttled", 1, keyFrameRequests.get())

                    // 跨过限流窗口后允许再来一次
                    clock.addAndGet(1)
                    sender.submitExternalFrame(pFrame(999))
                    awaitCondition { keyFrameRequests.get() == 2 }
                }
            } finally {
                releaseGate.countDown()
                sender.stop()
            }
        }
    }

    @Test
    fun concurrentProducersKeepEveryRequestOffProducerThreadsEvenWhenStopRaces() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val gateEntered = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val requestThreads = CopyOnWriteArrayList<String>()
            val failure = CopyOnWriteArrayList<Throwable>()
            val keyFrameRequests = AtomicInteger()
            val sender =
                sender(
                    receiver = receiver,
                    externalSource = {
                        keyFrameRequests.incrementAndGet()
                        requestThreads.add(Thread.currentThread().name)
                        true
                    },
                    phoneOwnsScreen = { blockOnce(gateEntered, releaseGate) },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    val producerNames = java.util.Collections.synchronizedSet(HashSet<String>())
                    val started = CountDownLatch(1)
                    val producers =
                        (0 until 4).map { producer ->
                            Thread {
                                producerNames.add(Thread.currentThread().name)
                                started.await()
                                repeat(400) { index ->
                                    try {
                                        val frame = if (index % 40 == 0) keyFrame(index) else pFrame(producer * 1_000 + index)
                                        sender.submitExternalFrame(frame)
                                    } catch (error: Throwable) {
                                        failure.add(error)
                                    }
                                }
                            }
                        }
                    producers.forEach { it.start() }
                    started.countDown()
                    // 队列很快打满并触发一次补要，然后在生产者仍在提交时停止。
                    awaitCondition { keyFrameRequests.get() >= 1 }
                    sender.stop()
                    producers.forEach { it.join(5_000) }

                    assertTrue("producers must not throw: $failure", failure.isEmpty())
                    assertFalse(sender.isRunning)
                    assertTrue(
                        "requests recorded: $requestThreads",
                        requestThreads.none { it in producerNames },
                    )
                    assertTrue(
                        "all overflow requests must run on the recovery worker: $requestThreads",
                        requestThreads.all { it.contains("video-overflow-keyframe") },
                    )
                    // 停止后不再需要补要：给在途请求一点收尾时间，再确认没有新请求。
                    Thread.sleep(200)
                    val afterStop = keyFrameRequests.get()
                    Thread.sleep(300)
                    assertEquals("stop must end key-frame recovery", afterStop, keyFrameRequests.get())
                }
            } finally {
                releaseGate.countDown()
                sender.stop()
            }
        }
    }

    @Test
    fun aDroppedFrameMarksTheDecoderChainBrokenAndPassthroughSendingClearsIt() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val gateEntered = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val sender =
                sender(
                    receiver = receiver,
                    externalSource = { true },
                    phoneOwnsScreen = { blockOnce(gateEntered, releaseGate) },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use { car ->
                    car.soTimeout = 2_000
                    assertTrue(sender.submitExternalFrame(keyFrame(0)))
                    assertTrue(gateEntered.await(2, TimeUnit.SECONDS))
                    assertFalse(sender.isPassthroughResyncPending)

                    // 推流线程卡在门控里：队列打满后这一帧被丢弃。
                    fillQueue(sender)
                    assertFalse(sender.submitExternalFrame(pFrame(77)))
                    assertTrue("a dropped access unit must break the decoder chain", sender.isPassthroughResyncPending)

                    // 放行后透传继续发送；透传路径不消费该标记，必须在发送时清掉，
                    // 否则它会残留到之后的转码切换，让解码器白等一次 IDR。
                    releaseGate.countDown()
                    awaitCondition { sender.framesSentTotal >= 9L }
                    awaitCondition { !sender.isPassthroughResyncPending }
                    // 队列已排空，后续帧应重新被接收（而不是继续报队满）。
                    assertTrue(sender.submitExternalFrame(pFrame(78)))
                    readVideoFrames(car, expected = 10)
                }
            } finally {
                releaseGate.countDown()
                sender.stop()
            }
        }
    }

    /** 第一次调用阻塞在门控上（模拟推流线程写不出去），之后立即放行。 */
    private fun blockOnce(
        entered: CountDownLatch,
        release: CountDownLatch,
    ): Boolean {
        if (entered.count == 1L) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        return true
    }

    private fun fillQueue(sender: VideoSender) {
        repeat(PassthroughFrameQueue.DEFAULT_CAPACITY) { index -> sender.submitExternalFrame(pFrame(index)) }
    }

    private fun sender(
        receiver: ServerSocket,
        externalSource: () -> Boolean,
        phoneOwnsScreen: () -> Boolean = { true },
        uptimeMillis: () -> Long = { 0L },
    ) = VideoSender(
        "127.0.0.1",
        receiver.localPort,
        StreamFrameCipher.ChaCha(key),
        640,
        480,
        log = {},
        externalVideoSource =
            object : ExternalVideoSource {
                override fun requestKeyFrame(): Boolean = externalSource()
            },
        phoneOwnsScreen = phoneOwnsScreen,
        uptimeMs = uptimeMillis,
    )

    private data class VideoFrame(
        val sliceTag: Int,
    )

    /** 从数据口读 [expected] 个视频帧（跳过 keepAlive/config），解密后返回每帧的切片 tag。 */
    private fun readVideoFrames(
        socket: Socket,
        expected: Int,
    ): List<VideoFrame> {
        val input: InputStream = socket.getInputStream()
        val frames = ArrayList<VideoFrame>()
        var counter = 0L
        while (frames.size < expected) {
            val header = readExactly(input, 128) ?: break
            val bodySize = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val opcode = header[4].toInt() and 0xFF
            val body = readExactly(input, bodySize) ?: break
            if (opcode != 0) continue
            val plain = AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)
            counter++
            frames += VideoFrame(sliceTagOf(plain))
        }
        return frames
    }

    /** 长度前缀 NAL 序列里取视频切片（type 1/5）的第二个字节，作为测试标记。 */
    private fun sliceTagOf(plain: ByteArray): Int {
        var offset = 0
        while (offset + 4 <= plain.size) {
            val length = ByteBuffer.wrap(plain, offset, 4).int
            offset += 4
            if (length <= 0 || offset + length > plain.size) return -1
            val type = plain[offset].toInt() and 0x1F
            if (type == 5 || type == 1) return plain[offset + 1].toInt() and 0xFF
            offset += length
        }
        return -1
    }

    private fun readExactly(
        input: InputStream,
        size: Int,
    ): ByteArray? {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count =
                try {
                    input.read(buffer, read, size - read)
                } catch (_: java.net.SocketTimeoutException) {
                    return null
                }
            if (count < 0) throw EOFException("data port closed after $read/$size bytes")
            read += count
        }
        return buffer
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && !condition()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for video sender state", condition())
    }

    private fun keyFrame(tag: Int): ExternalVideoFrame =
        ExternalVideoFrame(
            accessUnit(byteArrayOf(0x65, tag.toByte())),
            keyFrame = true,
            timestampUs = tag.toLong(),
            sps = byteArrayOf(0x67, 0x42, 0, 0x1f),
            pps = byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte()),
        )

    private fun pFrame(tag: Int): ExternalVideoFrame =
        ExternalVideoFrame(
            accessUnit(byteArrayOf(0x41, tag.toByte())),
            keyFrame = false,
            timestampUs = tag.toLong(),
        )

    private fun accessUnit(slice: ByteArray): ByteArray =
        listOf(
            byteArrayOf(0x67, 0x42, 0, 0x1f),
            byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte()),
            slice,
        ).fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
}
