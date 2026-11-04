package com.mcarplay.app.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LogStreamGateTest {
    /** 连接替身：记录发出的 seq，可按需失败。 */
    private class FakeConn(
        val name: String,
    ) {
        val sentSeqs = CopyOnWriteArrayList<Long>()
        val batches = CopyOnWriteArrayList<Int>()

        @Volatile
        var failNext = 0

        fun send(text: String): Boolean {
            if (failNext > 0) {
                failNext--
                return false
            }
            val lines = JSONObject(text).getJSONArray("lines")
            batches.add(lines.length())
            for (i in 0 until lines.length()) sentSeqs.add(lines.getJSONObject(i).getLong("seq"))
            return true
        }
    }

    private fun lines(range: LongRange) = range.map { it to "line $it" }

    private fun gate(
        ledger: LogSeqLedger = LogSeqLedger(),
        chunk: Int = 200,
    ) = LogStreamGate<FakeConn>(ledger, { c, t -> c.send(t) }, chunk)

    @Test
    fun endDoesNotWaitForAFlushBlockedInSocketWrite() {
        // PR #30 评审：冲刷线程持发送锁阻塞在死连接写入上，读线程超时后进 onClosed
        // 撤销日志流——若撤销要等发送锁，读线程就回不去关闭 socket，写入永远解不开。
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val conn = FakeConn("a")
        val blockWrites = AtomicBoolean(false)
        val gate =
            LogStreamGate<FakeConn>(LogSeqLedger(), { c, t ->
                if (blockWrites.get()) {
                    writeEntered.countDown()
                    releaseWrite.await()
                }
                c.send(t)
            })
        gate.begin(conn) { lines(1L..3L) }
        blockWrites.set(true)
        val flusher = Thread { gate.flush(lines(4L..5L)) }.apply { start() }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))

        val ended = CountDownLatch(1)
        Thread {
            gate.end(conn)
            ended.countDown()
        }.start()
        assertTrue("end() must not wait for the send lock", ended.await(1, TimeUnit.SECONDS))
        assertNull(gate.current)

        // socket 关闭/写超时解除写入后，冲刷线程正常退出
        releaseWrite.countDown()
        flusher.join(2_000)
        assertTrue(!flusher.isAlive)
    }

    @Test
    fun flushBeforeWelcomeIsDiscardedAndReplayedOnBegin() {
        val conn = FakeConn("a")
        val gate = gate()
        assertEquals(0, gate.flush(lines(1L..3L)))
        assertEquals(emptyList<Long>(), conn.sentSeqs)
        gate.begin(conn) { lines(1L..3L) }
        assertEquals((1L..3L).toList(), conn.sentSeqs)
    }

    @Test
    fun replayAndLiveFlushOfTheSameLinesSendEachSeqOnce() {
        val conn = FakeConn("a")
        val gate = gate()
        gate.begin(conn) { lines(1L..10L) }
        // pending 里还躺着 9、10（tap 同时写入 ring 与 pending）
        gate.flush(lines(9L..12L))
        assertEquals((1L..12L).toList(), conn.sentSeqs)
    }

    @Test
    fun reconnectReplaysOnlyWhatTheOldConnectionDidNotSend() {
        val ledger = LogSeqLedger()
        val gate = gate(ledger, chunk = 2)
        val first = FakeConn("a")
        gate.begin(first) { lines(1L..4L) }
        first.failNext = 1
        gate.flush(lines(5L..6L)) // 发送失败：不记账
        gate.end(first)
        assertEquals(0, gate.flush(lines(7L..7L))) // 断线期间：丢弃，留在 ring

        val second = FakeConn("b")
        gate.begin(second) { lines(1L..7L) }
        assertEquals((1L..4L).toList(), first.sentSeqs)
        assertEquals((5L..7L).toList(), second.sentSeqs)
    }

    @Test
    fun endOnlyClearsItsOwnConnection() {
        val gate = gate()
        val old = FakeConn("old")
        val current = FakeConn("new")
        gate.begin(old) { emptyList() }
        gate.begin(current) { emptyList() }
        gate.end(old) // 旧连接迟到的断线回调
        assertEquals(current, gate.current)
    }

    @Test
    fun replayWithNothingNewStillSendsAnEmptyBatch() {
        val conn = FakeConn("a")
        val gate = gate()
        gate.begin(conn) { lines(1L..2L) }
        gate.replay(conn) { lines(1L..2L) }
        assertEquals(listOf(2, 0), conn.batches)
    }
}
