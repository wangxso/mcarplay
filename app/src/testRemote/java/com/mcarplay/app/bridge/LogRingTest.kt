package com.mcarplay.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRingTest {
    private fun gate(
        ledger: LogSeqLedger,
        sent: MutableList<Long>,
    ) = LogStreamGate<Unit>(ledger, { _, text ->
        val lines = org.json.JSONObject(text).getJSONArray("lines")
        for (i in 0 until lines.length()) sent.add(lines.getJSONObject(i).getLong("seq"))
        true
    })

    @Test
    fun lateTapAcrossFullBufferReplayIsStillSent() {
        // PR #30 评审：seq 1 已分配但线程在调用 tap 前被挂起，其他线程用 2..N+1
        // 塞满缓冲；按缓冲最小 seq 推进下界会把 1 永久判为已发。
        val ledger = LogSeqLedger()
        val sent = mutableListOf<Long>()
        val gate = gate(ledger, sent)
        val ring = LogRing(capacity = 5)
        (2L..6L).forEach { ring.add(it, "line $it") }
        gate.begin(Unit) { ring.since(0) } // 缓冲已满时的回补
        ring.add(7, "line 7") // 触发淘汰
        assertFalse(ledger.isSent(1))

        // seq 1 迟到：实时冲刷必须把它和 7 一起发出去
        ring.add(1, "line 1")
        gate.flush(listOf(7L to "line 7", 1L to "line 1"))
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L, 7L, 1L), sent)
    }

    @Test
    fun lineEvictedFromRingWhileStillPendingIsStillFlushed() {
        // PR #30 评审：两次冲刷之间积累的行多于缓冲容量，被淘汰的行仍在 pending 里；
        // 淘汰若记账为「已处理」，flush 会把它永久过滤掉。
        val ledger = LogSeqLedger()
        val sent = mutableListOf<Long>()
        val gate = gate(ledger, sent)
        val ring = LogRing(capacity = 5)
        gate.begin(Unit) { ring.since(0) }
        val pending = mutableListOf<Pair<Long, String>>()
        (1L..8L).forEach {
            ring.add(it, "line $it")
            pending.add(it to "line $it")
        }
        assertEquals((4L..8L).toList(), ring.since(0).map { it.first })

        gate.flush(pending)
        assertEquals((1L..8L).toList(), sent)
    }

    @Test
    fun evictionDoesNotTouchTheLedger() {
        val ledger = LogSeqLedger()
        val ring = LogRing(capacity = 3)
        (1L..5L).forEach { ring.add(it, "line $it") }
        assertEquals(0, ledger.floor)
        assertEquals(listOf(3L, 4L, 5L), ledger.unsent(ring.since(0)) { it.first }.map { it.first })
    }

    @Test
    fun sinceFiltersBySeqInArrivalOrder() {
        val ring = LogRing(capacity = 10)
        listOf(1L, 3L, 2L, 4L).forEach { ring.add(it, "line $it") }
        assertEquals(listOf(3L, 4L), ring.since(2).map { it.first })
    }
}
