package com.mcarplay.app

import com.mcarplay.app.bt.AUTO_RECONNECT_FAST_FAILURE_MAX_MS
import com.mcarplay.app.bt.AutoReconnectGuard
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestContext

/** 自动重连接线的真实阈值、快照身份、成功清槽与记账叶子顺序。 */
class ReconnectAttributionTest {
    private class RecordingStore : ReconnectGuardStore {
        var healthyCount = 0
        var failureCount = 0
        var contextCount = 0
        var lastHealthyAddress: String? = null
        var lastFailureAddress: String? = null
        var lastAttempt: String? = null

        override fun recordHealthy(
            context: Any?,
            address: String?,
            attempt: String?,
        ) {
            healthyCount++
            contextCount++
            lastHealthyAddress = address
            lastAttempt = attempt
        }

        override fun recordFastFailure(
            context: Any?,
            address: String?,
            attempt: String?,
        ): AutoReconnectGuard.Block? {
            failureCount++
            contextCount++
            lastFailureAddress = address
            lastAttempt = attempt
            return AutoReconnectGuard.Block(failures = 1, untilMs = 3_000L, remainingMs = 3_000L)
        }
    }

    private val testContext = TestContext()

    @Test
    fun failureCodeSnapshotIdentityAndRealStoreTripReconnect() {
        val store = RecordingStore()
        var logs = 0
        var snapshots = 0
        val attribution =
            ReconnectAttribution(
                store = store,
                context = { testContext },
                snapshot = {
                    snapshots++
                    ReconnectAttemptSnapshot("attempt", 0L, AUTO_RECONNECT_FAST_FAILURE_MAX_MS - 1L)
                },
                appendLog = { logs++ },
            )
        val tripped = attribution.record("attempt", DiagnosticCode.BT_LINK_CLOSED, "AA:BB")
        assertTrue(tripped)
        assertEquals(1, snapshots)
        assertEquals(1, store.failureCount)
        assertEquals(0, store.healthyCount)
        assertEquals("AA:BB", store.lastFailureAddress)
        assertEquals("attempt", store.lastAttempt)
        assertEquals(1, logs)
    }

    @Test
    fun framesClearOnlyMatchingPeerAndNeverTrip() {
        val store = RecordingStore()
        val attribution =
            ReconnectAttribution(
                store = store,
                context = { testContext },
                snapshot = { ReconnectAttemptSnapshot("attempt", 1L, 90_000L) },
                appendLog = { },
            )
        assertFalse(attribution.record("attempt", DiagnosticCode.VIDEO_CLOSED, "AA:BB"))
        assertEquals(1, store.healthyCount)
        assertEquals("AA:BB", store.lastHealthyAddress)
        assertEquals(0, store.failureCount)
    }

    @Test
    fun codeGuardAndAttemptIdentityShortCircuitBeforeSnapshot() {
        val store = RecordingStore()
        var snapshots = 0
        val attribution =
            ReconnectAttribution(
                store = store,
                context = { null },
                snapshot = {
                    snapshots++
                    ReconnectAttemptSnapshot("other", 0L, 1_000L)
                },
                appendLog = { },
            )
        assertFalse(attribution.record("attempt", DiagnosticCode.USER_STOP, "AA:BB"))
        assertFalse(attribution.record("attempt", null, "AA:BB"))
        assertFalse(attribution.record(null, DiagnosticCode.BT_LINK_CLOSED, "AA:BB"))
        assertEquals(0, snapshots)
        assertEquals(0, store.failureCount)
        assertEquals(0, store.healthyCount)
    }

    @Test
    fun mismatchedSnapshotIdIsIgnored() {
        val store = RecordingStore()
        val attribution =
            ReconnectAttribution(
                store = store,
                context = { testContext },
                snapshot = { ReconnectAttemptSnapshot("other", 0L, 1_000L) },
                appendLog = { },
            )
        assertFalse(attribution.record("attempt", DiagnosticCode.SETUP_FAILED, "AA:BB"))
        assertEquals(0, store.failureCount)
        assertEquals(0, store.healthyCount)
    }

    @Test
    fun longRoundsKeepFastFailureWindow() {
        val store = RecordingStore()
        val attribution =
            ReconnectAttribution(
                store = store,
                context = { testContext },
                snapshot = { ReconnectAttemptSnapshot("attempt", 0L, AUTO_RECONNECT_FAST_FAILURE_MAX_MS) },
                appendLog = { },
            )
        assertFalse(attribution.record("attempt", DiagnosticCode.SETUP_FAILED, "AA:BB"))
        assertEquals(0, store.failureCount)
    }
}
