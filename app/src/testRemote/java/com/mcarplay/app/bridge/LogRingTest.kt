package com.mcarplay.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRingTest {
    @Test
    fun lateTapAcrossFullBufferReplayIsStillSent() {
        // PR #30 评审：seq 1 已分配但线程在调用 tap 前被挂起，其他线程用 2..N+1
        // 塞满缓冲；按缓冲最小 seq 推进下界会把 1 永久判为已发。
        val ledger = LogSeqLedger()
        val ring = LogRing(capacity = 5, ledger = ledger)
        (2L..6L).forEach { ring.add(it, "line $it") }
        // 缓冲已满时的回补
        ledger.unsent(ring.since(0)) { it.first }.forEach { ledger.markSent(it.first) }
        // 再来一行触发淘汰（2 已发出）
        ring.add(7, "line 7")
        assertFalse(ledger.isSent(1))

        // seq 1 迟到：实时冲刷/下次回补都必须把它发出去
        ring.add(1, "line 1")
        assertEquals(listOf(7L, 1L), ledger.unsent(ring.since(0)) { it.first }.map { it.first })
    }

    @Test
    fun evictedUnsentLinesAreMarkedDropped() {
        val ledger = LogSeqLedger()
        val ring = LogRing(capacity = 3, ledger = ledger)
        // 断线期间缓冲滚动：1、2 未发出就被淘汰，再也发不出去
        (1L..5L).forEach { ring.add(it, "line $it") }
        assertEquals(2, ledger.floor)
        assertEquals(listOf(3L, 4L, 5L), ledger.unsent(ring.since(0)) { it.first }.map { it.first })
    }

    @Test
    fun sinceFiltersBySeqInArrivalOrder() {
        val ring = LogRing(capacity = 10, ledger = LogSeqLedger())
        listOf(1L, 3L, 2L, 4L).forEach { ring.add(it, "line $it") }
        assertEquals(listOf(3L, 4L), ring.since(2).map { it.first })
    }
}
