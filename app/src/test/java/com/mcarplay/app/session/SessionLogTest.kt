package com.mcarplay.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SessionLogTest {
    @Test
    fun appendSanitizesNulToVisibleSymbolBeforeAllSinksAndBuffer() {
        val logcatLines = mutableListOf<String>()
        val fileLines = mutableListOf<String>()
        val listenerLines = mutableListOf<String>()
        val log =
            SessionLog(
                logcat = logcatLines::add,
                fileSink = fileLines::add,
                nowMs = { 3_723_456L }, // 01:02:03.456 UTC
                timeZone = TimeZone.getTimeZone("UTC"),
            )
        log.listener = listenerLines::add

        log.append("CARLIFE: host=10.0.0.1\u0000\u0000tail")

        val expectedClean = "CARLIFE: host=10.0.0.1␀␀tail"
        val expectedLine = "[01:02:03.456] $expectedClean\n"
        assertEquals(listOf(expectedClean), logcatLines)
        assertEquals(listOf(expectedClean), fileLines)
        assertEquals(listOf(expectedLine), listenerLines)
        assertEquals(expectedLine, log.snapshot())
    }

    @Test
    fun disabledLogStillWritesLogcatAndFileButSkipsBufferAndListener() {
        val calls = mutableListOf<String>()
        val listenerLines = mutableListOf<String>()
        val log =
            SessionLog(
                logcat = { calls += "logcat:$it" },
                fileSink = { calls += "file:$it" },
                nowMs = { 0L },
                timeZone = TimeZone.getTimeZone("UTC"),
            )
        log.listener = listenerLines::add
        log.enabled = false

        log.append("SESSION: hidden\u0000line")

        assertEquals(listOf("logcat:SESSION: hidden␀line", "file:SESSION: hidden␀line"), calls)
        assertEquals("", log.snapshot())
        assertTrue(listenerLines.isEmpty())
    }

    @Test
    fun bufferTrimsTo20kWhenExceeding40kKeepingNewestTail() {
        val log =
            SessionLog(
                logcat = {},
                fileSink = {},
                nowMs = { 0L },
                timeZone = TimeZone.getTimeZone("UTC"),
            )
        val payload = "A".repeat(980)
        // 每行约 996 字符；45 行约 44,820 > 40,000
        repeat(45) { index ->
            log.append("LINE-$index-$payload")
        }

        val text = log.snapshot()
        assertTrue("截断后长度不应超过 40_000，当前=${text.length}", text.length <= SessionLog.MAX_BUFFER_LENGTH)
        assertTrue("截断后应保留最后一行", text.contains("LINE-44-"))
        assertFalse("截断后应丢弃最旧头部", text.contains("LINE-0-"))
    }

    @Test
    fun clearEmptiesBufferWithoutInvokingListener() {
        val listenerCount = AtomicInteger(0)
        val log =
            SessionLog(
                logcat = {},
                fileSink = {},
                nowMs = { 0L },
                timeZone = TimeZone.getTimeZone("UTC"),
            )
        log.listener = { listenerCount.incrementAndGet() }

        log.append("first")
        assertEquals(1, listenerCount.get())
        assertTrue(log.snapshot().contains("first"))

        log.clear()

        assertEquals("", log.snapshot())
        assertEquals("clear 不得触发 listener（2026-10-16 清除按钮无反应回归）", 1, listenerCount.get())
    }

    @Test
    fun listenerAndExternalSinksRunOutsideBufferLock() {
        lateinit var log: SessionLog
        val sinkSnapshot = AtomicReference<String>()
        val listenerSnapshot = AtomicReference<String>()
        val helper = Executors.newSingleThreadExecutor()
        try {
            log =
                SessionLog(
                    logcat = {
                        // 在另一线程读取 snapshot；若 logcat 持有缓冲锁则会死锁超时
                        val result = helper.submit<String> { log.snapshot() }.get(2, TimeUnit.SECONDS)
                        sinkSnapshot.set(result)
                    },
                    fileSink = {},
                    nowMs = { 0L },
                    timeZone = TimeZone.getTimeZone("UTC"),
                )
            log.listener = {
                val result = helper.submit<String> { log.snapshot() }.get(2, TimeUnit.SECONDS)
                listenerSnapshot.set(result)
            }

            log.append("reentrant-check")

            assertEquals("", sinkSnapshot.get())
            assertEquals("[00:00:00.000] reentrant-check\n", listenerSnapshot.get())
        } finally {
            helper.shutdownNow()
        }
    }

    @Test
    fun concurrentAppendsSnapshotsAndClearsStayWellFormed() {
        val log =
            SessionLog(
                logcat = {},
                fileSink = {},
                timeZone = TimeZone.getTimeZone("UTC"),
            )
        val threads = 8
        val perThread = 150
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { id ->
                pool.execute {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        repeat(perThread) { i ->
                            log.append("T$id-$i")
                            if (i % 30 == 0) log.snapshot()
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("并发写入超时", done.await(15, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            val text = log.snapshot()
            assertTrue("应产生非空日志快照", text.isNotEmpty())
            assertTrue("并发写入后长度不应越界", text.length <= SessionLog.MAX_BUFFER_LENGTH)
            // 除可能被从中间截断的首行外，其余每行均应匹配完整时间戳前缀
            val lines = text.lines().drop(1).filter { it.isNotEmpty() }
            val linePattern = Regex("""^\[\d{2}:\d{2}:\d{2}\.\d{3}] T\d+-\d+$""")
            lines.forEach { line ->
                assertTrue("日志行格式损坏：$line", linePattern.matches(line))
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
