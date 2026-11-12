package com.mcarplay.app

import com.mcarplay.app.compat.ReceiverObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SessionHandoffOwnershipTest {
    @Test
    fun newerCandidateCannotCancelTheHandoffOwnedByAnotherLink() {
        val active = AtomicBoolean(false)
        val ownership = SessionHandoffOwnership(active)
        val owner = ReceiverObservation("receiver", epoch = 1, session = 3)
        val newerCandidate = ReceiverObservation("receiver", epoch = 1, session = 4)

        assertTrue(ownership.begin(owner))
        assertFalse(ownership.begin(newerCandidate))
        assertFalse("非归属链路关闭不能取消当前交接", ownership.endIfOwnedBy(newerCandidate))
        assertFalse("无法证明归属的关闭通知也不能取消已知归属的交接", ownership.endIfOwnedBy(null))
        assertTrue("归属链路的交接必须保持", active.get())
        assertTrue(ownership.endIfOwnedBy(owner))
        assertFalse(active.get())
    }

    @Test
    fun nextHandoffCanStartAfterOwnerReleases() {
        val active = AtomicBoolean(false)
        val ownership = SessionHandoffOwnership(active)
        val first = ReceiverObservation("receiver", epoch = 1, session = 1)
        val second = ReceiverObservation("receiver", epoch = 1, session = 2)

        assertTrue(ownership.begin(first))
        assertTrue(ownership.endIfOwnedBy(first))
        assertTrue(ownership.begin(second))
        assertTrue(ownership.endIfOwnedBy(second))
    }

    @Test
    fun beginCancelThenReconnectCanBeginAgain() {
        val active = AtomicBoolean(false)
        val ownership = SessionHandoffOwnership(active)
        val first = ReceiverObservation("receiver", epoch = 1, session = 7)
        val afterReconnect = ReceiverObservation("receiver", epoch = 1, session = 8)

        assertTrue(ownership.begin(first))
        ownership.release()
        assertFalse(active.get())
        assertFalse("旧链路迟到的关闭不能取消新轮次", ownership.endIfOwnedBy(first))
        assertTrue(ownership.begin(afterReconnect))
        assertTrue(active.get())
    }

    @Test
    fun disconnectGateIsAtomicWithBeginUntilCleanupCompletes() {
        val active = AtomicBoolean(false)
        val ownership = SessionHandoffOwnership(active)
        val endpoint = ReceiverObservation("receiver", epoch = 1, session = 9)
        var clearedCancellation = false

        ownership.blockNewHandoffs()
        assertFalse(ownership.begin(endpoint) { clearedCancellation = true })
        assertFalse("断连后不能清掉取消标志或占交接", clearedCancellation)
        assertFalse(active.get())

        ownership.allowNewHandoffs()
        assertTrue(ownership.begin(endpoint) { clearedCancellation = true })
        assertTrue(clearedCancellation)
        assertTrue(ownership.owns(endpoint))
        assertTrue(active.get())
        ownership.blockNewHandoffs()
        assertFalse(ownership.owns(endpoint))
    }

    @Test
    fun onlyOwnerRunsCancellationCallback() {
        val active = AtomicBoolean(false)
        val ownership = SessionHandoffOwnership(active)
        val owner = ReceiverObservation("receiver", epoch = 1, session = 10)
        val other = ReceiverObservation("receiver", epoch = 1, session = 11)
        var cancelled = 0

        assertTrue(ownership.begin(owner))
        assertFalse(ownership.cancelIfOwnedBy(other) { cancelled++ })
        assertTrue(active.get())
        assertTrue(ownership.cancelIfOwnedBy(owner) { cancelled++ })
        assertEquals(1, cancelled)
        assertFalse(active.get())
    }

    @Test
    fun alreadyCancelledOwnerIsReleasedWithoutRunningNewCancellation() {
        val active = AtomicBoolean(false)
        val ownership = SessionHandoffOwnership(active)
        val owner = ReceiverObservation("receiver", epoch = 1, session = 12)
        var cancelled = 0

        assertTrue(ownership.begin(owner))
        assertFalse(ownership.cancelIfOwnedBy(owner, isAlreadyCancelled = { true }) { cancelled++ })
        assertEquals(0, cancelled)
        assertFalse(active.get())
    }
}
