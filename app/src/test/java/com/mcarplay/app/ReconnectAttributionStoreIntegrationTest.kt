package com.mcarplay.app

import com.mcarplay.app.bt.AutoReconnectGuard
import com.mcarplay.app.bt.ReconnectCircuitBreaker
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `ReconnectAttribution` 与真实内存熔断 store 的 JVM 组合回归。 */
class ReconnectAttributionStoreIntegrationTest {
    private class TestContext

    /** 只记录真实 [MemoryReconnectGuardStore] 的调用与参数，全部执行后返回原 Block。 */
    private class CountingStore(
        private val delegate: MemoryReconnectGuardStore,
    ) : ReconnectGuardStore {
        var contextCount = 0
        var healthyCount = 0
        var failureCount = 0
        var lastContext: Any? = null
        var lastAddress: String? = null
        var lastAttempt: String? = null

        override fun recordHealthy(
            context: Any?,
            address: String?,
            attempt: String?,
        ) {
            contextCount++
            healthyCount++
            lastContext = context
            lastAddress = address
            lastAttempt = attempt
            delegate.recordHealthy(context, address, attempt)
        }

        override fun recordFastFailure(
            context: Any?,
            address: String?,
            attempt: String?,
        ): AutoReconnectGuard.Block? {
            contextCount++
            failureCount++
            lastContext = context
            lastAddress = address
            lastAttempt = attempt
            return delegate.recordFastFailure(context, address, attempt)
        }
    }

    /** 固定 failure 路径并可显式抛出，用于异常透传。 */
    private class ThrowingStore(
        private val throwable: Throwable,
    ) : ReconnectGuardStore {
        var contextCount = 0

        override fun recordHealthy(
            context: Any?,
            address: String?,
            attempt: String?,
        ) {
            contextCount++
            throw throwable
        }

        override fun recordFastFailure(
            context: Any?,
            address: String?,
            attempt: String?,
        ): AutoReconnectGuard.Block? {
            contextCount++
            throw throwable
        }
    }

    /** 记录查询叶子调用参数，并在指定方法抛出原异常。 */
    private class CountingQuery(
        private val throwable: Throwable? = null,
    ) : ReconnectGuardStore,
        ReconnectGuardQuery {
        var activeBlockCount = 0
        var clearCount = 0
        var lastActiveContext: Any? = null
        var lastActiveNowMs: Long? = null
        var lastClearContext: Any? = null

        override fun recordHealthy(
            context: Any?,
            address: String?,
            attempt: String?,
        ): Unit = throw UnsupportedOperationException("store recordHealthy is not used")

        override fun recordFastFailure(
            context: Any?,
            address: String?,
            attempt: String?,
        ): AutoReconnectGuard.Block? = throw UnsupportedOperationException("store recordFastFailure is not used")

        override fun block(
            context: Any?,
            address: String?,
            nowMs: Long,
        ): AutoReconnectGuard.Block? = throw UnsupportedOperationException("query block is not used")

        override fun activeBlock(
            context: Any,
            nowMs: Long,
        ): AutoReconnectGuard.Block? {
            activeBlockCount++
            lastActiveContext = context
            lastActiveNowMs = nowMs
            throwable?.let { throw it }
            return null
        }

        override fun clear(context: Any) {
            clearCount++
            lastClearContext = context
            throwable?.let { throw it }
        }
    }

    private val testContext = TestContext()

    private class MemoryAttributionFixture(
        var nowMs: Long,
    ) {
        val context = TestContext()
        var attempt: String? = null
        val logs = mutableListOf<String>()
        val store = MemoryReconnectGuardStore(nowMs = { nowMs })
        val attribution =
            ReconnectAttribution(
                store = store,
                query = store,
                context = { context },
                snapshot = { attempt?.let { ReconnectAttemptSnapshot(it, 0L, 1_000L) } },
                appendLog = logs::add,
            )

        fun record(
            id: String,
            code: DiagnosticCode,
            peer: String?,
        ): Boolean {
            attempt = id
            return attribution.record(id, code, peer)
        }

        fun block(
            peer: String?,
            at: Long = nowMs,
        ): AutoReconnectGuard.Block? = attribution.block(peer, at)

        fun activeBlock(at: Long = nowMs): AutoReconnectGuard.Block? = attribution.activeBlock(context, at)

        fun clear() {
            attribution.clear(context)
        }
    }

    private fun attribution(
        store: ReconnectGuardStore,
        snapshot: () -> ReconnectAttemptSnapshot?,
        logs: MutableList<String> = mutableListOf(),
        context: () -> Any? = { testContext },
    ): ReconnectAttribution =
        ReconnectAttribution(
            store = store,
            context = context,
            snapshot = snapshot,
            appendLog = logs::add,
        )

    @Test
    fun repeatedSameAttemptDoesNotTripTwice() {
        var now = 0L
        val logs = mutableListOf<String>()
        val store = MemoryReconnectGuardStore(nowMs = { now })
        val attribution = attribution(store, snapshot = { ReconnectAttemptSnapshot("attempt", 0L, 1_000L) }, logs = logs)

        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun thirdDistinctAttemptTripsOnce() {
        var now = 0L
        val logs = mutableListOf<String>()
        val store = MemoryReconnectGuardStore(nowMs = { now })
        var currentAttempt = ""
        val attribution = attribution(store, snapshot = { ReconnectAttemptSnapshot(currentAttempt, 0L, 1_000L) }, logs = logs)

        currentAttempt = "attempt-1"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        currentAttempt = "attempt-2"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.VIDEO_CLOSED, "AA:BB"))
        currentAttempt = "attempt-3"
        assertTrue(attribution.record("attempt-3", DiagnosticCode.SETUP_FAILED, "AA:BB"))
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(listOf(tripLog(failures = 3)), logs)
    }

    @Test
    fun sameAttemptWithinEqualWindowTripsOnThird() {
        verifyWindowBoundary(delta = ReconnectCircuitBreaker.WINDOW_MILLIS, thirdAttemptTripped = true)
    }

    @Test
    fun sameAttemptOutsideWindowResetsFailureSequence() {
        verifyWindowBoundary(delta = ReconnectCircuitBreaker.WINDOW_MILLIS + 1L, thirdAttemptTripped = false)
    }

    @Test
    fun sameAttemptOneMillisecondBeforeWindowTripsOnThird() {
        verifyWindowBoundary(delta = ReconnectCircuitBreaker.WINDOW_MILLIS - 1L, thirdAttemptTripped = true)
    }

    private fun verifyWindowBoundary(
        delta: Long,
        thirdAttemptTripped: Boolean,
    ) {
        val base = 1_000L
        var now = base
        var currentAttempt = ""
        val logs = mutableListOf<String>()
        val store = MemoryReconnectGuardStore(nowMs = { now })
        val attribution =
            attribution(store, snapshot = { ReconnectAttemptSnapshot(currentAttempt, 0L, 1_000L) }, logs = logs)

        currentAttempt = "attempt-a"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.WIFI_LOST, "AA:BB"))
        currentAttempt = "attempt-b"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.WIFI_JOIN_FAILED, "AA:BB"))
        now = base + delta
        val thirdAttempt = "attempt-c"
        currentAttempt = thirdAttempt
        assertEquals(
            thirdAttemptTripped,
            attribution.record(thirdAttempt, DiagnosticCode.PAIRING_FAILED, "AA:BB"),
        )
        if (thirdAttemptTripped) {
            assertEquals(listOf(tripLog(3)), logs)
        } else {
            now += 1L
            currentAttempt = "attempt-d"
            assertFalse(attribution.record(currentAttempt, DiagnosticCode.SETUP_FAILED, "AA:BB"))
            now += 1L
            currentAttempt = "attempt-e"
            assertTrue(attribution.record(currentAttempt, DiagnosticCode.CONNECTION_INCOMPLETE, "AA:BB"))
            assertEquals(listOf(tripLog(3)), logs)
        }
    }

    @Test
    fun differentPeersRemainIsolated() {
        var now = 0L
        var currentAttempt = ""
        val logs = mutableListOf<String>()
        val store = MemoryReconnectGuardStore(nowMs = { now })
        val attribution = attribution(store, snapshot = { ReconnectAttemptSnapshot(currentAttempt, 0L, 1_000L) }, logs = logs)

        currentAttempt = "attempt-a-1"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        currentAttempt = "attempt-b-1"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.VIDEO_CLOSED, "CC:DD"))
        currentAttempt = "attempt-a-2"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.SETUP_FAILED, "AA:BB"))
        currentAttempt = "attempt-b-2"
        assertFalse(attribution.record(currentAttempt, DiagnosticCode.CONNECTION_INCOMPLETE, "CC:DD"))
        now += 1L
        currentAttempt = "attempt-a-3"
        assertTrue(attribution.record(currentAttempt, DiagnosticCode.WIFI_LOST, "AA:BB"))
        currentAttempt = "attempt-b-3"
        assertTrue(attribution.record(currentAttempt, DiagnosticCode.WIFI_JOIN_FAILED, "CC:DD"))
        assertEquals(2, logs.size)
        assertEquals(tripLog(3), logs[0])
        assertEquals(tripLog(3), logs[1])
    }

    @Test
    fun framesOnlyClearMatchingPeerThroughRealStore() {
        var now = 0L
        var currentAttempt = ""
        val logs = mutableListOf<String>()
        val delegate = MemoryReconnectGuardStore(nowMs = { now })
        val store = CountingStore(delegate)
        val failing = attribution(store, snapshot = { ReconnectAttemptSnapshot(currentAttempt, 0L, 1_000L) }, logs = logs)
        val healthy = attribution(store, snapshot = { ReconnectAttemptSnapshot(currentAttempt, 1L, 90_000L) }, logs = logs)

        currentAttempt = "attempt-a-1"
        assertFalse(failing.record(currentAttempt, DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        currentAttempt = "attempt-a-2"
        assertFalse(failing.record(currentAttempt, DiagnosticCode.VIDEO_CLOSED, "AA:BB"))
        currentAttempt = "attempt-b-1"
        assertFalse(failing.record(currentAttempt, DiagnosticCode.SETUP_FAILED, "CC:DD"))
        currentAttempt = "attempt-b-2"
        assertFalse(failing.record(currentAttempt, DiagnosticCode.CONNECTION_INCOMPLETE, "CC:DD"))
        currentAttempt = "attempt-a-healthy"
        assertFalse(healthy.record(currentAttempt, DiagnosticCode.VIDEO_CLOSED, "AA:BB"))
        currentAttempt = "attempt-b-3"
        assertTrue(failing.record(currentAttempt, DiagnosticCode.WIFI_LOST, "CC:DD"))
        currentAttempt = "attempt-a-new-1"
        assertFalse(failing.record(currentAttempt, DiagnosticCode.WIFI_JOIN_FAILED, "AA:BB"))
        currentAttempt = "attempt-a-new-2"
        assertFalse(failing.record(currentAttempt, DiagnosticCode.PAIRING_FAILED, "AA:BB"))
        currentAttempt = "attempt-a-new-3"
        assertTrue(failing.record(currentAttempt, DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(2, logs.size)
        assertEquals(tripLog(3), logs[0])
        assertEquals(tripLog(3), logs[1])
        assertEquals(1, store.healthyCount)
        assertEquals(8, store.failureCount)
        assertEquals(9, store.contextCount)
    }

    @Test
    fun invalidInputsShortCircuitWithoutStoreCalls() {
        val logs = mutableListOf<String>()
        val delegate = MemoryReconnectGuardStore(nowMs = { 0L })
        val store = CountingStore(delegate)
        var snapshotCount = 0
        val attribution =
            attribution(store, snapshot = {
                snapshotCount++
                null
            }, logs = logs, context = { null })

        assertFalse(attribution.record(null, null, "AA:BB"))
        assertFalse(attribution.record("attempt", DiagnosticCode.USER_STOP, "AA:BB"))
        assertEquals(0, snapshotCount)
        assertEquals(0, store.contextCount)
        assertEquals(0, store.healthyCount)
        assertEquals(0, store.failureCount)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun blankAttemptShortCircuitsAfterSnapshotWithoutStoreCalls() {
        val logs = mutableListOf<String>()
        val delegate = MemoryReconnectGuardStore(nowMs = { 0L })
        val store = CountingStore(delegate)
        var snapshotCount = 0
        val attribution =
            attribution(store, snapshot = {
                snapshotCount++
                ReconnectAttemptSnapshot("attempt", 0L, 1_000L)
            }, logs = logs)

        assertFalse(attribution.record(null, DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertFalse(attribution.record("   ", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(2, snapshotCount)
        assertEquals(0, store.contextCount)
        assertEquals(0, store.healthyCount)
        assertEquals(0, store.failureCount)
        assertEquals(emptyList<String>(), logs)
    }

    private fun attributionWithQuery(
        query: CountingQuery,
        snapshot: () -> ReconnectAttemptSnapshot?,
        logs: MutableList<String> = mutableListOf(),
        context: () -> Any? = { testContext },
    ): ReconnectAttribution =
        ReconnectAttribution(
            store = query,
            query = query,
            context = context,
            snapshot = snapshot,
            appendLog = logs::add,
        )

    @Test
    fun nullAddressWithValidSnapshotReachesMemoryStoreWithoutClockRead() {
        var clockCount = 0
        val logs = mutableListOf<String>()
        val store =
            MemoryReconnectGuardStore(nowMs = {
                clockCount++
                0L
            })
        val attribution = attribution(store, snapshot = { ReconnectAttemptSnapshot("attempt", 0L, 1_000L) }, logs = logs)

        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, null))
        assertEquals(0, clockCount)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun nullContextShortCircuitsWithoutStoreCalls() {
        val logs = mutableListOf<String>()
        val delegate = MemoryReconnectGuardStore(nowMs = { 0L })
        val store = CountingStore(delegate)
        var snapshotCount = 0
        val attribution =
            attribution(store, context = { null }, snapshot = {
                snapshotCount++
                null
            }, logs = logs)

        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(0, snapshotCount)
        assertEquals(0, store.contextCount)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun snapshotMismatchShortCircuitDoesNotReadMemoryClock() {
        var now = 0L
        val logs = mutableListOf<String>()
        val store = MemoryReconnectGuardStore(nowMs = { ++now })
        val attribution = attribution(store, snapshot = { ReconnectAttemptSnapshot("other", 0L, 1_000L) }, logs = logs)

        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(0L, now)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun nullSnapshotShortCircuitsWithoutStoreCalls() {
        val logs = mutableListOf<String>()
        val delegate = MemoryReconnectGuardStore(nowMs = { 0L })
        val store = CountingStore(delegate)
        var snapshotCount = 0
        val attribution =
            attribution(store, snapshot = {
                snapshotCount++
                null
            }, logs = logs)

        assertFalse(attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(1, snapshotCount)
        assertEquals(0, store.contextCount)
        assertEquals(0, store.healthyCount)
        assertEquals(0, store.failureCount)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun memoryClockSentinelExceptionKeepsIdentity() {
        val sentinel = IllegalStateException("clock sentinel")
        val logs = mutableListOf<String>()
        val store = MemoryReconnectGuardStore(nowMs = { throw sentinel })
        val attribution = attribution(store, snapshot = { ReconnectAttemptSnapshot("attempt", 0L, 1_000L) }, logs = logs)

        try {
            attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB")
            fail("clock exception must escape")
        } catch (thrown: IllegalStateException) {
            assertSame(sentinel, thrown)
        }
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun throwingStoreExceptionKeepsIdentity() {
        val sentinel = UnsupportedOperationException("store sentinel")
        val logs = mutableListOf<String>()
        val store = ThrowingStore(sentinel)
        val attribution = attribution(store, snapshot = { ReconnectAttemptSnapshot("attempt", 0L, 1_000L) }, logs = logs)
        try {
            attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB")
            fail("store exception must escape")
        } catch (thrown: UnsupportedOperationException) {
            assertSame(sentinel, thrown)
        }
        assertEquals(1, store.contextCount)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun thirdRealMemoryFailureBlockMapsThresholdState() {
        var now = 0L
        val store = MemoryReconnectGuardStore(nowMs = { now })

        assertNull(store.recordFastFailure(testContext, "AA:BB", "attempt-1"))
        assertNull(store.recordFastFailure(testContext, "AA:BB", "attempt-2"))
        now = 456L
        val block = store.recordFastFailure(testContext, "AA:BB", "attempt-3")

        assertEquals(3, block?.failures)
        assertEquals(180_456L, block?.untilMs)
        assertEquals(180_000L, block?.remainingMs)
    }

    @Test
    fun thirdNewTripAndActiveBlockQueryRemainSeparateEvidence() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)

        assertNull(fixture.block("AA:BB"))
        assertFalse(fixture.record("attempt-1", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertFalse(fixture.record("attempt-2", DiagnosticCode.VIDEO_CLOSED, "AA:BB"))
        val newTrip = fixture.record("attempt-3", DiagnosticCode.SETUP_FAILED, "AA:BB")
        val activeBlock = fixture.block("aa:bb")

        assertTrue(newTrip)
        assertEquals(3, activeBlock?.failures)
        assertEquals(181_000L, activeBlock?.untilMs)
        assertEquals(ReconnectCircuitBreaker.COOLDOWN_MILLIS, activeBlock?.remainingMs)
    }

    @Test
    fun repeatedSameAttemptDoesNotNewTripButTrippedQueryStillBlocks() {
        val fixture = MemoryAttributionFixture(nowMs = 2_000L)
        tripPeer(fixture, "AA:BB")

        val repeatRecorded = fixture.record("attempt-3", DiagnosticCode.BT_LINK_CLOSED, "AA:BB")
        val activeBlock = fixture.block("aa:bb")

        assertFalse(repeatRecorded)
        assertEquals(3, activeBlock?.failures)
        assertEquals(ReconnectCircuitBreaker.COOLDOWN_MILLIS, activeBlock?.remainingMs)
    }

    @Test
    fun activeBlockQueryStillBlocksAtOneMillisecondRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        val activeBlock = fixture.block("AA:BB", at = 180_999L)

        assertEquals(3, activeBlock?.failures)
        assertEquals(181_000L, activeBlock?.untilMs)
        assertEquals(1L, activeBlock?.remainingMs)
    }

    @Test
    fun activeBlockQueryStillBlocksAtFullCooldownRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        val activeBlock = fixture.block("AA:BB")

        assertEquals(3, activeBlock?.failures)
        assertEquals(181_000L, activeBlock?.untilMs)
        assertEquals(ReconnectCircuitBreaker.COOLDOWN_MILLIS, activeBlock?.remainingMs)
    }

    @Test
    fun activeBlockQueryExpiresAtZeroRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.block("AA:BB", at = 181_000L))
    }

    @Test
    fun activeBlockQueryExpiresForNegativeRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.block("AA:BB", at = 181_001L))
    }

    @Test
    fun activeBlockQueryExpiresOnRollbackOneBeyondCooldown() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.block("AA:BB", at = 999L))
    }

    @Test
    fun activeBlockQueryMatchesSamePeerCaseInsensitively() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "aa:bb")

        assertEquals(3, fixture.block("AA:BB")?.failures)
    }

    @Test
    fun differentPeerIsNotBlockedAndTripsIndependently() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.block("cc:dd"))
        assertFalse(fixture.record("attempt-b-1", DiagnosticCode.BT_LINK_CLOSED, "CC:DD"))
        assertFalse(fixture.record("attempt-b-2", DiagnosticCode.VIDEO_CLOSED, "CC:DD"))
        assertTrue(fixture.record("attempt-b-3", DiagnosticCode.SETUP_FAILED, "CC:DD"))
        assertEquals(3, fixture.block("cc:dd")?.failures)
        assertEquals(3, fixture.block("aa:bb")?.failures)
    }

    @Test
    fun nullPeerIsNotCountedOrBlockedByKnownPeer() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.block(null))
        assertFalse(fixture.record("attempt-null", DiagnosticCode.BT_LINK_CLOSED, null))
        assertEquals(3, fixture.block("aa:bb")?.failures)
    }

    @Test
    fun activeBlockReturnsAnyPeerAtOneMillisecondRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        val block = fixture.activeBlock(at = 180_999L)

        assertEquals(3, block?.failures)
        assertEquals(181_000L, block?.untilMs)
        assertEquals(1L, block?.remainingMs)
    }

    @Test
    fun activeBlockReturnsFullCooldownRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        val block = fixture.activeBlock()

        assertEquals(3, block?.failures)
        assertEquals(181_000L, block?.untilMs)
        assertEquals(ReconnectCircuitBreaker.COOLDOWN_MILLIS, block?.remainingMs)
    }

    @Test
    fun activeBlockExpiresAtZeroRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.activeBlock(at = 181_000L))
    }

    @Test
    fun activeBlockExpiresForNegativeRemaining() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.activeBlock(at = 181_001L))
    }

    @Test
    fun activeBlockExpiresOnRollbackOneBeyondCooldown() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        assertNull(fixture.activeBlock(at = 999L))
    }

    @Test
    fun activeBlockIsEmptyWithoutStates() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)

        assertNull(fixture.activeBlock())
    }

    @Test
    fun activeBlockFindsFirstIndependentPeerAndNewPeerCanTripLater() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")

        val block = fixture.activeBlock()
        assertEquals(3, block?.failures)
        assertFalse(fixture.record("attempt-b-1", DiagnosticCode.BT_LINK_CLOSED, "CC:DD"))
        assertFalse(fixture.record("attempt-b-2", DiagnosticCode.VIDEO_CLOSED, "CC:DD"))
        assertTrue(fixture.record("attempt-b-3", DiagnosticCode.SETUP_FAILED, "CC:DD"))
        assertEquals(3, fixture.activeBlock()?.failures)
        assertEquals(3, fixture.block("cc:dd")?.failures)
    }

    @Test
    fun activeBlockIgnoresContextAndClearsAllPeers() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")
        tripPeer(fixture, "CC:DD")

        assertEquals(3, fixture.activeBlock()?.failures)
        fixture.clear()
        assertNull(fixture.activeBlock())
        assertNull(fixture.block("AA:BB"))
        assertNull(fixture.block("CC:DD"))
    }

    @Test
    fun clearAllowsDistinctAttemptRefillThroughMemoryClock() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)
        tripPeer(fixture, "AA:BB")
        fixture.clear()

        assertNull(fixture.activeBlock())
        fixture.nowMs = 2_000L
        assertFalse(fixture.record("attempt-4", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertFalse(fixture.record("attempt-5", DiagnosticCode.VIDEO_CLOSED, "AA:BB"))
        assertTrue(fixture.record("attempt-6", DiagnosticCode.SETUP_FAILED, "AA:BB"))
        assertEquals(3, fixture.activeBlock()?.failures)
        assertEquals(182_000L, fixture.block("AA:BB")?.untilMs)
    }

    @Test
    fun newTripAndActiveBlockQueryRemainSeparateEvidence() {
        val fixture = MemoryAttributionFixture(nowMs = 1_000L)

        assertNull(fixture.activeBlock())
        assertFalse(fixture.record("attempt-1", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertFalse(fixture.record("attempt-2", DiagnosticCode.VIDEO_CLOSED, "AA:BB"))
        assertTrue(fixture.record("attempt-3", DiagnosticCode.SETUP_FAILED, "AA:BB"))
        val block = fixture.activeBlock()

        assertEquals(3, block?.failures)
        assertEquals(181_000L, block?.untilMs)
        assertEquals(ReconnectCircuitBreaker.COOLDOWN_MILLIS, block?.remainingMs)
    }

    @Test
    fun repeatedSameAttemptDoesNotNewTripButRemainsActive() {
        val fixture = MemoryAttributionFixture(nowMs = 2_000L)
        tripPeer(fixture, "AA:BB")

        assertFalse(fixture.record("attempt-3", DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(3, fixture.activeBlock()?.failures)
    }

    @Test
    fun activeBlockQueryExceptionKeepsIdentity() {
        val sentinel = IllegalStateException("query active sentinel")
        val query = CountingQuery(sentinel)
        val attribution = attributionWithQuery(query, snapshot = { ReconnectAttemptSnapshot("attempt", 0L, 1_000L) })
        try {
            attribution.activeBlock(testContext)
            fail("query active exception must escape")
        } catch (thrown: IllegalStateException) {
            assertSame(sentinel, thrown)
        }
        assertEquals(1, query.activeBlockCount)
        assertSame(testContext, query.lastActiveContext)
    }

    @Test
    fun clearQueryExceptionKeepsIdentity() {
        val sentinel = UnsupportedOperationException("query clear sentinel")
        val query = CountingQuery(sentinel)
        val attribution = attributionWithQuery(query, snapshot = { null })
        try {
            attribution.clear(testContext)
            fail("query clear exception must escape")
        } catch (thrown: UnsupportedOperationException) {
            assertSame(sentinel, thrown)
        }
        assertEquals(1, query.clearCount)
        assertSame(testContext, query.lastClearContext)
    }

    @Test
    fun activeBlockEntryDefaultClockReachesQuery() {
        val query = CountingQuery()
        val attribution = attributionWithQuery(query, snapshot = { null })
        val before = System.currentTimeMillis()
        attribution.activeBlock(testContext)
        val after = System.currentTimeMillis()

        assertEquals(1, query.activeBlockCount)
        assertEquals(0, query.clearCount)
        assertSame(testContext, query.lastActiveContext)
        val readAt = checkNotNull(query.lastActiveNowMs)
        assertTrue(readAt in (before - 1L)..after)
    }

    private fun tripLog(failures: Int): String = "AUTO: 连续 $failures 次会话在出画前被车机断开——暂停自动重连 180 秒（可在 App 内手动连接）"

    private fun tripPeer(
        fixture: MemoryAttributionFixture,
        peer: String,
    ) {
        val attempts =
            listOf(
                "attempt-1" to DiagnosticCode.BT_LINK_CLOSED,
                "attempt-2" to DiagnosticCode.VIDEO_CLOSED,
                "attempt-3" to DiagnosticCode.SETUP_FAILED,
            )
        attempts.forEachIndexed { index, (attempt, code) ->
            assertEquals(index == attempts.lastIndex, fixture.record(attempt, code, peer))
        }
        assertEquals(
            fixture.nowMs + ReconnectCircuitBreaker.COOLDOWN_MILLIS,
            fixture.block(peer)?.untilMs,
        )
    }
}
