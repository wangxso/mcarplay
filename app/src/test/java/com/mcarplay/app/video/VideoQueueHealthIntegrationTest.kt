package com.mcarplay.app.video

import com.mcarplay.app.airplay.AirPlayCrypto
import com.mcarplay.app.airplay.StreamFrameCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * #75（视频输出停滞看门狗）与 #76（8 帧透传队列 + 队满补要关键帧）在同一发送端里的交互。
 *
 * 两者共用 drain 线程与 `phoneOwnsScreen`/门控：看门狗从健康线程采样，队列与补要来自
 * CarLife 读线程，写路径同时服务两条诊断。这里的断言只钉住「合并后仍然成立」的行为事实：
 *  - 拥塞（队满）时的补要必须走补要线程，看门狗不得因此误报或重复补帧；
 *  - 写阻塞时看门狗判 `写阻塞` 且不补帧，而队满补要仍独立工作；
 *  - 主屏暂停期间两者都不得活动；
 *  - stop() 必须同时结束健康线程与补要线程，且不再请求关键帧。
 *
 * 纯状态机分别由 [VideoOutputHealthTest] / [PassthroughFrameQueueTest] 覆盖。
 */
class VideoQueueHealthIntegrationTest {
    private val key = ByteArray(32) { (it * 17 + 3).toByte() }

    @Test
    fun receiverEofEndsAnIdleVideoSenderWithoutWaitingForAnotherWrite() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 2_000
            val ended = CountDownLatch(1)
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
                            override fun requestKeyFrame(): Boolean = false
                        },
                    onEnded = { ended.countDown() },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use { car ->
                    // FIN arrives while the peer still accepts writes and no new video frames are queued.
                    car.shutdownOutput()
                    assertTrue(ended.await(2, TimeUnit.SECONDS))
                    assertFalse(sender.isRunning)
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun queueOverflowDuringATransportStallAsksForOneIdrWithoutDuplicatingTheWatchdogsRequest() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val requests = CopyOnWriteArrayList<String>()
            val logs = CopyOnWriteArrayList<String>()
            val drainEntered = CountDownLatch(1)
            val releaseDrain = CountDownLatch(1)
            val drainCalls = AtomicInteger()
            val sender =
                sender(
                    receiver = receiver,
                    clock = clock,
                    logs = logs,
                    onKeyFrame = { requests.add(Thread.currentThread().name) },
                    phoneOwnsScreen = {
                        // 只阻塞推流线程的第 2 次门控调用（第 1 帧必须正常写出，看门狗才会进入
                        // 「已有输出」状态）。健康线程也调用这个回调，不能让它抢走门控。
                        if (Thread.currentThread().name == DRAIN_THREAD && drainCalls.incrementAndGet() == 2) {
                            drainEntered.countDown()
                            // 必须一直卡到测试主动放行：否则超时放行后 drain 写出新帧，
                            // 会把看门狗的停滞基线刷新掉，测试随机器速度变得不确定。
                            releaseDrain.await(30, TimeUnit.SECONDS)
                        }
                        true
                    },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use { car ->
                    car.soTimeout = 2_000
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    awaitCondition { sender.framesSentTotal >= 1 }

                    // 推流线程卡在门控里（不是被丢弃：帧已取出并停在 gate 上），队列堆满 → 队满补要
                    assertTrue(sender.submitExternalFrame(pFrame(2)))
                    assertTrue(drainEntered.await(2, TimeUnit.SECONDS))
                    fillToOverflow(sender)
                    awaitCondition { requests.size == 1 }
                    assertTrue(
                        "the overflow request must run on its own worker, not the producer: $requests",
                        requests.isNotEmpty() && requests.single().contains("video-overflow-keyframe"),
                    )

                    // 同一时刻看门狗也看到「长时间没有写出」：它不能因此再补一次，
                    // 也不能把队列拥塞误判成编码器故障。
                    // 队列拥塞期间看门狗不得额外补帧：关键帧请求只能来自队满补要这一条路径。
                    clock.addAndGet(6_000)
                    assertTrue(
                        "the watchdog must observe the stall while the drain is still blocked; logs=$logs",
                        awaitLog(logs, "视频输出停滞"),
                    )
                    assertEquals("a stalled watchdog must never add a key-frame request", 1, requests.size)
                    assertEquals(0, sender.outputHealthKeyFrameRequests())

                    // 队列仍可正常工作：必须在放行门控**之前**先提交新 IDR，否则 countDown()
                    // 会立刻唤醒 drain，让它先取走一个排队中的旧 P 帧再轮到替换 IDR，
                    // 断言序列就随调度变化（2026-11-16 审查）。先提交 → 队列内容被替换为
                    // 唯一的新 IDR，再放行，写出序列才是确定的。
                    assertTrue(sender.submitExternalFrame(keyFrame(99)))
                    releaseDrain.countDown()
                    val frames = readVideoFrames(car, expected = 3)
                    assertEquals("the newest IDR must be the last one written", 99, frames.last())
                }
            } finally {
                releaseDrain.countDown()
                sender.stop()
            }
        }
    }

    @Test
    fun aBlockedWriteIsClassifiedAsWriteBlockedWhileTheQueueStillReportsItsOwnOverflow() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val requests = CopyOnWriteArrayList<String>()
            val logs = CopyOnWriteArrayList<String>()
            val sender =
                sender(
                    receiver = receiver,
                    clock = clock,
                    logs = logs,
                    onKeyFrame = { requests.add(Thread.currentThread().name) },
                )
            try {
                assertTrue(sender.start {})
                // 接收端 accept 后不读：发送端写最终阻塞在满窗口上
                val accepted = receiver.accept()
                try {
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    awaitCondition { sender.framesSentTotal >= 1 }

                    // 远超发送缓冲：drain 卡在这次 write 内
                    assertTrue(sender.submitExternalFrame(hugeFrame(4 * 1024 * 1024)))
                    assertTrue("the drain must block inside write", awaitCondition { sender.isHealthWriteInFlightForTest() })

                    // 写阻塞期间队满：队列语义与补要仍然独立于卡住的写线程
                    fillToOverflow(sender)
                    awaitCondition { requests.size == 1 }
                    assertTrue(
                        "a blocked write must not stop the overflow worker: $requests",
                        requests.single().contains("video-overflow-keyframe"),
                    )

                    // 看门狗必须把这次停滞判成写阻塞，而不是编码器无输出（两者处理相反）
                    clock.addAndGet(6_000)
                    assertTrue(
                        "the watchdog must classify the in-flight write",
                        awaitLog(logs, "视频输出停滞") { it.contains("写阻塞") },
                    )
                    assertFalse(
                        "a blocked write must not be classified as an encoder fault",
                        logs.any { it.contains("视频输出停滞") && it.contains("编码器无输出") },
                    )
                    assertEquals("the watchdog must not request a key frame", 0, sender.outputHealthKeyFrameRequests())
                    assertEquals("only the queue overflow may request", 1, requests.size)
                } finally {
                    runCatching { accepted.close() }
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun aMainScreenPauseKeepsBothTheWatchdogAndTheOverflowWorkerQuiet() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val requests = CopyOnWriteArrayList<String>()
            val logs = CopyOnWriteArrayList<String>()
            val ownsScreen = AtomicBoolean(true)
            val sender =
                sender(
                    receiver = receiver,
                    clock = clock,
                    logs = logs,
                    ownsScreen = ownsScreen,
                    onKeyFrame = { requests.add(Thread.currentThread().name) },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    awaitCondition { sender.framesSentTotal >= 1 }

                    // 主屏归车机：门控进入暂停
                    ownsScreen.set(false)
                    assertTrue(sender.submitExternalFrame(pFrame(2)))
                    awaitCondition { sender.screenFramesSkippedTotal >= 1 }

                    // 暂停 60 s（远超 20 s 判死）：不得产生停滞诊断，也不得补帧
                    clock.addAndGet(60_000)
                    Thread.sleep(400)
                    assertFalse(
                        "a paused screen must not be diagnosed as a stall; logs=$logs",
                        logs.any { it.contains("视频输出停滞") || it.contains("自动补救已用尽") },
                    )
                    assertEquals("无停滞", sender.outputHealthSummary())
                    assertEquals(0, sender.outputHealthKeyFrameRequests())
                    assertTrue("a paused screen must not trigger overflow requests", requests.isEmpty())

                    // 队列在暂停期间仍保留帧（门控丢弃才是暂停的语义），恢复后继续发送
                    ownsScreen.set(true)
                    clock.addAndGet(400)
                    assertTrue(sender.submitExternalFrame(keyFrame(3)))
                    awaitCondition { sender.framesSentTotal >= 2 }
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun stopEndsBothBackgroundThreadsAndLeavesNoPendingRequest() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val requests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender =
                sender(
                    receiver = receiver,
                    clock = clock,
                    logs = logs,
                    onKeyFrame = { requests.incrementAndGet() },
                )
            assertTrue(sender.start {})
            receiver.accept().use {
                assertTrue("health monitor must run while streaming", awaitCondition { sender.isHealthMonitorAlive() })
                assertTrue("overflow worker must run while streaming", awaitCondition { sender.isOverflowRecoveryAlive() })
            }

            // 并发 stop（透传与转码 drain 都会调用）不得留下线程，也不得重复摘要
            val start = CountDownLatch(1)
            val stoppers =
                List(2) {
                    Thread {
                        start.await()
                        sender.stop()
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            start.countDown()
            stoppers.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

            assertTrue("health monitor must end with the sender", awaitCondition(timeoutMs = 3_000) { !sender.isHealthMonitorAlive() })
            assertFalse("overflow worker must end with the sender", sender.isOverflowRecoveryAlive())

            val summaries = logs.count { it.contains("输出健康看门狗") }
            assertEquals("the session-end summary must be logged exactly once", 1, summaries)

            // 已停止的会话里推进时间并继续提交：不得再请求关键帧
            clock.addAndGet(60_000)
            runCatching { sender.submitExternalFrame(keyFrame(9)) }
            Thread.sleep(400)
            assertEquals("no request may survive stop()", 0, requests.get())
        }
    }

    @Test
    fun overflowingThenStallingDoesNotProduceAKeyFrameStorm() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val requests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val drainCalls = AtomicInteger()
            val drainEntered = CountDownLatch(1)
            val releaseDrain = CountDownLatch(1)
            val sender =
                sender(
                    receiver = receiver,
                    clock = clock,
                    logs = logs,
                    onKeyFrame = { requests.incrementAndGet() },
                    // 必须真的卡住 drain：默认 phoneOwnsScreen = { true } 时帧会被正常写出，
                    // 队满变成与写出竞速，无法保证填满队列（2026-11-16 审查）。
                    phoneOwnsScreen = {
                        if (Thread.currentThread().name == DRAIN_THREAD && drainCalls.incrementAndGet() == 2) {
                            drainEntered.countDown()
                            releaseDrain.await(30, TimeUnit.SECONDS)
                        }
                        true
                    },
                )
            try {
                assertTrue(sender.start {})
                receiver.accept().use { car ->
                    car.soTimeout = 2_000
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })

                    // 确认 drain 已被卡住（第 2 次门控调用），再制造队满：否则这轮突发
                    // 只是和自由排空的写线程竞速，不保证填满队列。
                    assertTrue(sender.submitExternalFrame(pFrame(2)))
                    assertTrue("the drain must be blocked before overflowing", drainEntered.await(2, TimeUnit.SECONDS))

                    // 推流线程卡住后反复制造队满：1 s 限流必须让请求保持在 1 次
                    repeat(3) {
                        fillToOverflow(sender)
                        repeat(10) { sender.submitExternalFrame(pFrame(100 + it)) }
                        Thread.sleep(50)
                    }
                    assertTrue("overflow must produce a request", awaitCondition { requests.get() >= 1 })
                    assertEquals("queue overflow must be throttled to one request per second", 1, requests.get())

                    Thread.sleep(300)
                    assertEquals("no burst of requests while still inside the window", 1, requests.get())
                }
            } finally {
                releaseDrain.countDown()
                sender.stop()
            }
        }
    }

    /** 填满 8 帧队列，再故意多提交一帧触发队满（P 帧）。 */
    private fun fillToOverflow(sender: VideoSender) {
        repeat(PassthroughFrameQueue.DEFAULT_CAPACITY) { index -> sender.submitExternalFrame(pFrame(20 + index)) }
        sender.submitExternalFrame(pFrame(999))
    }

    private fun sender(
        receiver: ServerSocket,
        clock: AtomicLong,
        logs: MutableList<String>,
        ownsScreen: AtomicBoolean = AtomicBoolean(true),
        onKeyFrame: () -> Unit = {},
        phoneOwnsScreen: () -> Boolean = { ownsScreen.get() },
    ): VideoSender =
        VideoSender(
            host = "127.0.0.1",
            dataPort = receiver.localPort,
            frameCipher = StreamFrameCipher.ChaCha(key),
            width = 640,
            height = 480,
            log = { line -> logs += line },
            externalVideoSource =
                object : ExternalVideoSource {
                    override fun requestKeyFrame(): Boolean {
                        onKeyFrame()
                        return true
                    }
                },
            phoneOwnsScreen = phoneOwnsScreen,
            uptimeMs = { clock.get() },
        )

    /** VideoSender 里推流线程的名字：只有它能被测试夹具阻塞，健康线程必须立即返回。 */
    private companion object {
        const val DRAIN_THREAD = "video-external-drain"
    }

    private fun awaitLog(
        logs: List<String>,
        mustContain: String,
        predicate: (String) -> Boolean = { true },
    ): Boolean = awaitCondition { logs.any { it.contains(mustContain) && predicate(it) } }

    private fun awaitCondition(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline && !condition()) {
            Thread.sleep(10)
        }
        return condition()
    }

    private fun readVideoFrames(
        socket: Socket,
        expected: Int,
    ): List<Int> {
        val input: InputStream = socket.getInputStream()
        val tags = ArrayList<Int>()
        var counter = 0L
        while (tags.size < expected) {
            val header = readFully(input, 128) ?: break
            val bodySize = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val opcode = header[4].toInt() and 0xFF
            val body = readFully(input, bodySize) ?: break
            if (opcode != 0) continue
            val plain = AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)
            counter++
            sliceTagOf(plain)?.let { tags += it }
        }
        return tags
    }

    private fun sliceTagOf(plain: ByteArray): Int? {
        var offset = 0
        while (offset + 4 <= plain.size) {
            val length = ByteBuffer.wrap(plain, offset, 4).int
            offset += 4
            if (length <= 0 || offset + length > plain.size) return null
            val type = plain[offset].toInt() and 0x1F
            if (type == 5 || type == 1) return plain[offset + 1].toInt() and 0xFF
            offset += length
        }
        return null
    }

    private fun readFully(
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
            if (count < 0) return null
            read += count
        }
        return buffer
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

    private fun hugeFrame(size: Int): ExternalVideoFrame =
        ExternalVideoFrame(
            accessUnit(ByteArray(size) { index -> if (index % 97 == 0) 0x41 else 0x11 }),
            keyFrame = false,
            timestampUs = 0,
        )

    private fun accessUnit(slice: ByteArray): ByteArray =
        listOf(
            byteArrayOf(0x67, 0x42, 0, 0x1f),
            byteArrayOf(0x68, 0xce.toByte(), 6, 0xe2.toByte()),
            slice,
        ).fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
}
