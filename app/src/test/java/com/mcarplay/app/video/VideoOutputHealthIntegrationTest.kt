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

/**
 * 视频输出停滞看门狗在真实发送端里的行为：独立于阻塞 socket 写、注入时钟、暂停/关闭边界。
 *
 * 纯状态机的分支由 [VideoOutputHealthTest] 覆盖；这里钉住接线事实——写阻塞时看门狗确实能
 * 越过卡住的写线程看到停滞、且**不**补关键帧；主屏暂停期间不累积；stop() 结束线程。
 */
class VideoOutputHealthIntegrationTest {
    @Test
    fun sessionEndSummaryKeepsTheWarningsRecordedDuringTheSession() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            assertTrue(sender.start {})
            receiver.accept().use {
                assertTrue(sender.submitExternalFrame(keyFrame(1)))
                assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })
                // 进入一次已报警的停滞，让本轮确有记录
                clock.addAndGet(6_000)
                assertTrue(awaitLog(logs, "视频输出停滞") { it.contains("无输入") })
            }
            sender.stop()
            val summaryLine = logs.firstOrNull { it.contains("输出健康看门狗") }
            assertTrue("stop() must log the session-end summary", summaryLine != null)
            assertTrue(
                "The session-end summary must not say 无停滞 after a recorded stall: $summaryLine",
                summaryLine!!.contains("无输入"),
            )
            assertEquals("Stopping must still clear the in-memory state", "无停滞", sender.outputHealthSummary())
        }
    }

    @Test
    fun lateSampleAfterFinalizationCanNeitherLogNorRepopulateState() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            assertTrue(sender.start {})
            receiver.accept().use {
                assertTrue(sender.submitExternalFrame(keyFrame(1)))
                assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })
                clock.addAndGet(6_000)
                assertTrue(awaitLog(logs, "视频输出停滞") { it.contains("无输入") })
            }
            sender.stop()
            val summaryLine = logs.filter { it.contains("输出健康看门狗") }
            assertEquals("stop() must log exactly one session-end summary", 1, summaryLine.size)
            val stallLinesAfterStop = logs.count { it.contains("视频输出停滞") }
            val requestAfterStop = sender.outputHealthKeyFrameRequests()

            // 模拟已经越过循环条件、在 stop() 之后才拿到锁的监测轮次：不得改状态、不得打日志
            clock.addAndGet(60_000)
            sender.sampleOutputHealthForTest()

            assertEquals(
                "A late monitor iteration must not log after finalization",
                stallLinesAfterStop,
                logs.count { it.contains("视频输出停滞") },
            )
            assertEquals("A late monitor iteration must not re-arm a request", requestAfterStop, sender.outputHealthKeyFrameRequests())
            assertEquals("A late monitor iteration must not repopulate state", "无停滞", sender.outputHealthSummary())
        }
    }

    @Test
    fun sourceInputArrivingDuringABlockedWriteSurvivesTheSuccessfulWrite() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            try {
                assertTrue(sender.start {})
                val accepted = receiver.accept()
                try {
                    // 大帧让 drain 卡在 write 里
                    assertTrue(sender.submitExternalFrame(hugeFrame(4 * 1024 * 1024)))
                    assertTrue(
                        "the drain must be blocked in write",
                        awaitCondition { sender.isHealthWriteInFlightForTest() },
                    )

                    // 写还被卡着时，源线程又送入一帧：这一事件发生在写入开始之后，
                    // 写成功时绝不能把它当成「写入前」而抹掉
                    sender.markSourceInputForTest()

                    // 放行写，让这次写成功完成
                    val reader =
                        Thread {
                            runCatching {
                                val input = accepted.getInputStream()
                                val buffer = ByteArray(64 * 1024)
                                while (input.read(buffer) >= 0) Unit
                            }
                        }.apply {
                            isDaemon = true
                            start()
                        }
                    assertTrue("the blocked write must complete", awaitCondition { sender.framesSentTotal >= 1 })
                    reader.interrupt()

                    // 写成功后无新帧 6 s：必须看到「写阻塞」（有输入但没写出），
                    // 而不是误判成「无输入」
                    clock.addAndGet(6_000)
                    sender.sampleOutputHealthForTest()
                    assertTrue(
                        "input that arrived during the write must not be erased: ${logs.filter { it.contains("视频输出停滞") }}",
                        logs.any { it.contains("视频输出停滞") && it.contains("写阻塞") },
                    )
                    assertFalse(
                        "the watchdog must not classify it as NO_SOURCE_INPUT",
                        logs.any { it.contains("视频输出停滞") && it.contains("无输入") },
                    )
                } finally {
                    runCatching { accepted.close() }
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun finalizationWinsOverConcurrentSamplingWithoutLateLogs() {
        repeat(30) { attempt ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
                val clock = AtomicLong(1_000)
                val keyFrameRequests = AtomicInteger()
                val logs = CopyOnWriteArrayList<String>()
                val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })
                    clock.addAndGet(6_000)
                    assertTrue(awaitLog(logs, "视频输出停滞") { it.contains("无输入") })
                }

                // 采样线程与 stop() 竞争：无论谁先拿到 outputHealth 锁，
                // 定稿之后都不得再出现停滞日志、不得重新置位请求、状态必须为空
                val start = CountDownLatch(1)
                val contenders =
                    List(4) { index ->
                        Thread {
                            start.await()
                            if (index == 0) {
                                sender.stop()
                            } else {
                                sender.sampleOutputHealthForTest()
                            }
                        }.apply {
                            isDaemon = true
                            start()
                        }
                    }
                start.countDown()
                contenders.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
                sender.stop()

                val summaryLines = logs.filter { it.contains("输出健康看门狗") }
                assertEquals(
                    "attempt $attempt: the session-end summary must be logged exactly once",
                    1,
                    summaryLines.size,
                )
                assertTrue(
                    "attempt $attempt: concurrent stop() lost the recorded stall: ${summaryLines.first()}",
                    summaryLines.first().contains("无输入"),
                )
                assertEquals("attempt $attempt: state must stay cleared", "无停滞", sender.outputHealthSummary())
                assertEquals("attempt $attempt: no request may survive finalization", 0, sender.outputHealthKeyFrameRequests())
            }
        }
    }

    @Test
    fun concurrentStopsShareOneFinalSummaryThatKeepsTheRecordedStall() {
        repeat(20) { attempt ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
                val clock = AtomicLong(1_000)
                val keyFrameRequests = AtomicInteger()
                val logs = CopyOnWriteArrayList<String>()
                val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })
                    // 让本轮确有记录，且监测线程此刻可能正卡在采样锁上
                    clock.addAndGet(6_000)
                    assertTrue(awaitLog(logs, "视频输出停滞") { it.contains("无输入") })
                }

                // 透传与转码 drain 线程会并发 stop()：两者必须拿到同一份真实摘要
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

                val summaryLines = logs.filter { it.contains("输出健康看门狗") }
                assertEquals("attempt $attempt: the session-end summary must be logged exactly once", 1, summaryLines.size)
                assertTrue(
                    "attempt $attempt: concurrent stop() lost the recorded stall: ${summaryLines.first()}",
                    summaryLines.first().contains("无输入"),
                )
            }
        }
    }

    @Test
    fun recoveryAfterAWarnedStallIsReportedWithItsMaximumDuration() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })

                    // 进入一次已报警的停滞
                    clock.addAndGet(6_000)
                    assertTrue(awaitLog(logs, "视频输出停滞") { it.contains("无输入") })

                    // 再写一帧：恢复必须由下一次采样报告，而不是被写路径静默重置
                    assertTrue(sender.submitExternalFrame(keyFrame(2)))
                    assertTrue("the second frame must be written", awaitCondition { sender.framesSentTotal >= 2 })
                    assertTrue(
                        "A stall that recovers must emit 视频输出恢复 with its maximum duration",
                        awaitLog(logs, "视频输出恢复") { it.contains("最长") },
                    )
                    assertEquals("无停滞", sender.outputHealthSummary())
                    assertEquals(0, sender.outputHealthKeyFrameRequests())
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun startupWithoutAnyVideoFrameIsNotDiagnosed() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    // 从未成功写出过视频帧：没有证据区分启动慢与编码器故障，不得判定
                    clock.addAndGet(60_000)
                    Thread.sleep(400)
                    assertFalse(
                        "A startup with no frame yet must not be diagnosed as a stall",
                        logs.any { it.contains("视频输出停滞") },
                    )
                    assertEquals(0, sender.outputHealthKeyFrameRequests())
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun stallAfterStreamingStartedIsReportedAsNoInputWithoutRequestingAKeyFrame() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })

                    // 已有输出后停 6 s 且没有新帧：这是「无输入」，不是编码器故障
                    clock.addAndGet(6_000)
                    assertTrue(
                        "health monitor must observe the idle source",
                        awaitLog(logs, "视频输出停滞") { it.contains("无输入") },
                    )
                    assertEquals("No input must not be treated as an encoder fault", 0, keyFrameRequests.get())
                    assertEquals(0, sender.outputHealthKeyFrameRequests())
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun blockedSocketWriteIsReportedAndNeverRequestsAKeyFrame() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            try {
                // start() 内部发起连接；必须先 start 再 accept，否则 accept 会永久等待
                assertTrue(sender.start {})
                // 接收端 accept 后从不读取：发送端 socket 写最终会阻塞在满窗口上
                val accepted = receiver.accept()
                try {
                    // 先成功写一小帧，让看门狗进入「已有输出」状态
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })

                    // 远超发送缓冲的访问单元：drain 线程会卡在这次 write 内
                    assertTrue(sender.submitExternalFrame(hugeFrame(4 * 1024 * 1024)))
                    // 写阻塞后才推进时钟：若看门狗把停滞误判成编码器故障，就会请求关键帧
                    clock.addAndGet(6_000)
                    assertTrue(
                        "A blocked write must be observable without the write thread returning",
                        awaitLog(logs, "视频输出停滞") { it.contains("写阻塞") },
                    )
                    assertEquals("A blocked write must never request a key frame", 0, keyFrameRequests.get())
                    assertEquals(0, sender.outputHealthKeyFrameRequests())
                    assertTrue(
                        "The summary must classify the stall as write-blocked",
                        sender.outputHealthSummary().contains("写阻塞"),
                    )
                } finally {
                    runCatching { accepted.close() }
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun pausedScreenDoesNotAccumulateAStallAcrossALongPause() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val ownsScreen = AtomicBoolean(true)
            val logs = CopyOnWriteArrayList<String>()
            val sender =
                passthroughSender(receiver, clock, keyFrameRequests, logs, ownsScreen = ownsScreen)
            try {
                assertTrue(sender.start {})
                receiver.accept().use {
                    // 先正常出一帧，让看门狗进入「已有输出」状态
                    assertTrue(sender.submitExternalFrame(keyFrame(1)))
                    assertTrue("the first frame must be written", awaitCondition { sender.framesSentTotal >= 1 })

                    // 主屏归车机：再送一帧让门控真正进入 SUSPENDED
                    ownsScreen.set(false)
                    assertTrue(sender.submitExternalFrame(pFrame(2)))
                    assertTrue("the gate must observe the pause", awaitCondition { sender.screenFramesSkippedTotal >= 1 })

                    // 暂停 60 s（远超 20 s 判死阈值），不得累积任何停滞
                    clock.addAndGet(60_000)
                    Thread.sleep(400)
                    assertFalse(
                        "A main-screen pause must not be diagnosed as transport stalling",
                        logs.any { it.contains("视频输出停滞") || it.contains("自动补救已用尽") },
                    )
                    assertEquals("无停滞", sender.outputHealthSummary())

                    // 恢复后也不得把暂停时长当成停滞立刻 GIVE_UP
                    ownsScreen.set(true)
                    clock.addAndGet(400)
                    Thread.sleep(400)
                    assertEquals("无停滞", sender.outputHealthSummary())
                    assertEquals(0, keyFrameRequests.get())
                }
            } finally {
                sender.stop()
            }
        }
    }

    @Test
    fun stoppingTheSenderEndsTheHealthMonitorAndIssuesNoLateRequest() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { receiver ->
            val clock = AtomicLong(1_000)
            val keyFrameRequests = AtomicInteger()
            val logs = CopyOnWriteArrayList<String>()
            val sender = passthroughSender(receiver, clock, keyFrameRequests, logs)
            assertTrue(sender.start {})
            receiver.accept().use {
                assertTrue("health monitor must run while streaming", awaitCondition { sender.isHealthMonitorAlive() })
            }
            sender.stop()
            assertTrue(
                "health monitor must terminate when the sender stops",
                awaitCondition(timeoutMs = 3_000) { !sender.isHealthMonitorAlive() },
            )

            // 已停止的会话里推进时间：不得再请求关键帧，也不得新增停滞日志
            clock.addAndGet(60_000)
            val before = logs.count { it.contains("视频输出停滞") }
            Thread.sleep(400)
            assertEquals(before, logs.count { it.contains("视频输出停滞") })
            assertEquals(0, keyFrameRequests.get())
        }
    }

    private fun passthroughSender(
        receiver: ServerSocket,
        clock: AtomicLong,
        keyFrameRequests: AtomicInteger,
        logs: MutableList<String>,
        ownsScreen: AtomicBoolean = AtomicBoolean(true),
    ): VideoSender =
        VideoSender(
            host = "127.0.0.1",
            dataPort = receiver.localPort,
            frameCipher = StreamFrameCipher.ChaCha(ByteArray(32)),
            width = 640,
            height = 480,
            log = { line -> logs += line },
            externalVideoSource =
                object : ExternalVideoSource {
                    override fun requestKeyFrame(): Boolean {
                        keyFrameRequests.incrementAndGet()
                        return true
                    }
                },
            phoneOwnsScreen = { ownsScreen.get() },
            uptimeMs = { clock.get() },
        )

    private fun awaitLog(
        logs: List<String>,
        mustContain: String,
        predicate: (String) -> Boolean,
    ): Boolean = awaitCondition { logs.any { it.contains(mustContain) && predicate(it) } }

    private fun awaitCondition(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun keyFrame(timestampUs: Long): ExternalVideoFrame =
        ExternalVideoFrame(
            accessUnit(byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())),
            keyFrame = true,
            timestampUs = timestampUs,
            sps = byteArrayOf(0x67, 0x42, 0, 0x1F),
            pps = byteArrayOf(0x68, 0xCE.toByte(), 6, 0xE2.toByte()),
        )

    private fun pFrame(timestampUs: Long): ExternalVideoFrame =
        ExternalVideoFrame(
            accessUnit(byteArrayOf(0x41, 0x9A.toByte(), 0x22)),
            keyFrame = false,
            timestampUs = timestampUs,
        )

    private fun accessUnit(slice: ByteArray): ByteArray =
        listOf(
            byteArrayOf(0x67, 0x42, 0, 0x1F),
            byteArrayOf(0x68, 0xCE.toByte(), 6, 0xE2.toByte()),
            slice,
        ).fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }

    private fun hugeFrame(bytes: Int): ExternalVideoFrame {
        val payload = ByteArray(bytes) { (it and 0x7F).toByte() }
        payload[0] = 0x65
        val data =
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1F) +
                byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 6, 0xE2.toByte()) +
                byteArrayOf(0, 0, 0, 1) +
                payload
        return ExternalVideoFrame(
            data = data,
            keyFrame = true,
            timestampUs = 0,
            sps = byteArrayOf(0x67, 0x42, 0, 0x1F),
            pps = byteArrayOf(0x68, 0xCE.toByte(), 6, 0xE2.toByte()),
        )
    }
}
