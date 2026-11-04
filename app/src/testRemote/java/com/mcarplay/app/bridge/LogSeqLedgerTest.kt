package com.mcarplay.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSeqLedgerTest {
    private fun seqs(vararg values: Long) = values.toList()

    @Test
    fun replayThenLiveFlushNeverResendsTheSameSeq() {
        val ledger = LogSeqLedger()
        // tap 同时写 ring 与 pending：welcome 回补整个 ring 后，pending 里还是同一批行
        val ring = (1L..10L).toList()
        val replay = ledger.unsent(ring) { it }
        replay.forEach(ledger::markSent)
        assertEquals(ring, replay)

        val pending = seqs(9, 10, 11, 12)
        assertEquals(seqs(11, 12), ledger.unsent(pending) { it })
    }

    @Test
    fun reconnectReplaySendsOnlyLinesNotYetSent() {
        val ledger = LogSeqLedger()
        (1L..100L).forEach(ledger::markSent)
        // 断线期间新增 101..120，重连后 welcome 回补整个 ring
        val ring = (21L..120L).toList()
        assertEquals((101L..120L).toList(), ledger.unsent(ring) { it })
    }

    @Test
    fun failedSendsStayUnsentForTheNextReplay() {
        val ledger = LogSeqLedger()
        (1L..5L).forEach(ledger::markSent)
        // 6..8 发送失败未记账；9 成功
        ledger.markSent(9)
        assertEquals(seqs(6, 7, 8), ledger.unsent((1L..9L).toList()) { it })
    }

    @Test
    fun outOfOrderTapArrivalDoesNotDropTheLateSeq() {
        val ledger = LogSeqLedger()
        // DebugFileLog 在写锁外分配 seq：11 可能先于 10 到达并发出
        (1L..9L).forEach(ledger::markSent)
        ledger.markSent(11)
        assertFalse(ledger.isSent(10))
        assertEquals(seqs(10), ledger.unsent(seqs(10, 11)) { it })
        ledger.markSent(10)
        assertEquals(11, ledger.floor)
        assertEquals(0, ledger.sparseCount)
    }

    @Test
    fun duplicateSeqsInsideOneBatchAreSentOnce() {
        val ledger = LogSeqLedger()
        assertEquals(seqs(3, 4), ledger.unsent(seqs(3, 3, 4, 3)) { it })
    }

    @Test
    fun advanceFloorDropsEvictedHolesAndNeverMovesBackwards() {
        val ledger = LogSeqLedger()
        ledger.markSent(50)
        ledger.markSent(51)
        assertEquals(2, ledger.sparseCount)
        ledger.advanceFloorTo(49)
        assertEquals(51, ledger.floor)
        assertEquals(0, ledger.sparseCount)
        ledger.advanceFloorTo(10)
        assertEquals(51, ledger.floor)
        assertTrue(ledger.isSent(30))
    }

    @Test
    fun permanentHoleDoesNotGrowTheSetBeyondTheCap() {
        val ledger = LogSeqLedger(maxSparse = 8)
        // seq 1 永远不出现：没有上限的话集合会随每条已发行增长
        (2L..100L).forEach(ledger::markSent)
        assertTrue(ledger.sparseCount <= 8)
        assertEquals(100, ledger.floor)
        assertTrue((1L..100L).all(ledger::isSent))
    }

    @Test
    fun freshProcessLedgerStartsEmpty() {
        // 进程重启后 seq 从 1 重新开始；账本是进程内状态，不受上一进程影响
        val ledger = LogSeqLedger()
        assertEquals(0, ledger.floor)
        assertFalse(ledger.isSent(1))
    }
}
