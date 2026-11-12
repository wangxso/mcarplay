package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectCircuitBreakerTest {
    private val breaker = ReconnectCircuitBreaker()
    private val address = "AA:BB:CC:DD:EE:01"
    private val t0 = 1_000_000L

    private fun fail(
        state: ReconnectCircuitBreaker.State,
        at: Long,
        peer: String? = address,
    ): ReconnectCircuitBreaker.Failure = breaker.onFastFailure(state, peer, at)

    @Test
    fun tripsOnTheThirdFastFailureWithinTheWindow() {
        var state = fail(ReconnectCircuitBreaker.State(), t0).state
        assertFalse(breaker.isBlocked(state, address, t0))

        state = fail(state, t0 + 10_000L).state
        assertFalse(breaker.isBlocked(state, address, t0 + 10_000L))

        val third = fail(state, t0 + 20_000L)
        assertTrue(third.tripped)
        assertEquals(3, third.state.failures)
        assertTrue(breaker.isBlocked(third.state, address, t0 + 20_000L))
        assertTrue(breaker.isBlocked(third.state, address.lowercase(), t0 + 20_000L))
    }

    @Test
    fun failuresOutsideTheWindowRestartTheCount() {
        var state = fail(ReconnectCircuitBreaker.State(), t0).state
        state = fail(state, t0 + 10_000L).state
        // 第 3 次已超过 5 分钟窗口：从 1 重新计数，不应熔断
        val late = fail(state, t0 + ReconnectCircuitBreaker.WINDOW_MILLIS + 1L)
        assertFalse(late.tripped)
        assertEquals(1, late.state.failures)
        assertFalse(breaker.isBlocked(late.state, address, t0 + ReconnectCircuitBreaker.WINDOW_MILLIS + 1L))
    }

    @Test
    fun blockOnlyAppliesToTheSameReceiver() {
        var state = ReconnectCircuitBreaker.State()
        repeat(3) { index ->
            state = fail(state, t0 + index * 1_000L).state
        }
        assertTrue(breaker.isBlocked(state, address, t0 + 5_000L))
        assertFalse(breaker.isBlocked(state, "AA:BB:CC:DD:EE:02", t0 + 5_000L))
    }

    @Test
    fun blockExpiresAndTheNextFailureStartsANewCount() {
        var state = ReconnectCircuitBreaker.State()
        repeat(3) { index ->
            state = fail(state, t0 + index * 1_000L).state
        }
        val blockedUntil = state.blockedUntilMs
        assertFalse(breaker.isBlocked(state, address, blockedUntil))
        assertEquals(0L, breaker.remainingMillis(state, blockedUntil))

        val after = fail(state, blockedUntil + 1_000L)
        assertFalse(after.tripped)
        assertEquals(1, after.state.failures)
        assertEquals(0L, after.state.blockedUntilMs)
    }

    @Test
    fun successClearsTheBlock() {
        var state = ReconnectCircuitBreaker.State()
        repeat(3) { index ->
            state = fail(state, t0 + index * 1_000L).state
        }
        state = breaker.onSuccess(state)
        assertEquals(0, state.failures)
        assertEquals(0L, state.blockedUntilMs)
        assertFalse(breaker.isBlocked(state, address, t0 + 1_000L))
    }

    @Test
    fun failuresWithoutAKnownPeerAreNeitherCountedNorMatched() {
        var state = ReconnectCircuitBreaker.State()
        repeat(3) { index ->
            val failure = breaker.onFastFailure(state, null, t0 + index * 1_000L)
            assertFalse(failure.tripped)
            assertEquals(0, failure.state.failures)
            assertEquals(0L, failure.state.blockedUntilMs)
            state = failure.state
        }
        assertFalse(breaker.isBlocked(state, address, t0 + 5_000L))
        assertFalse(breaker.isBlocked(state, null, t0 + 5_000L))
    }

    @Test
    fun aBlockForOneReceiverDoesNotMatchAnUnknownAutomaticTarget() {
        var state = ReconnectCircuitBreaker.State()
        repeat(3) { index ->
            state = fail(state, t0 + index * 1_000L).state
        }
        assertTrue(breaker.isBlocked(state, address, t0 + 5_000L))
        // 默认自动模式没有 TargetDevicePrefs：A 车的熔断不能拦住“所有接收端”。
        assertFalse(breaker.isBlocked(state, null, t0 + 5_000L))
    }

    @Test
    fun legacyStoredStateWithoutAnAddressNeverBlocksAConcreteReceiver() {
        val legacy =
            ReconnectCircuitBreaker.State(
                address = null,
                failures = 3,
                firstFailureAtMs = t0,
                lastFailureAtMs = t0 + 2_000L,
                blockedUntilMs = t0 + ReconnectCircuitBreaker.COOLDOWN_MILLIS,
            )
        assertTrue(breaker.isBlockActive(legacy, t0 + 1_000L))
        assertFalse(breaker.isBlocked(legacy, address, t0 + 1_000L))
        assertFalse(breaker.isBlocked(legacy, null, t0 + 1_000L))
    }

    @Test
    fun failuresAreKeyedPerReceiverSoOneCarCannotTripAnother() {
        val other = "AA:BB:CC:DD:EE:02"
        var state = fail(ReconnectCircuitBreaker.State(), t0).state
        state = fail(state, t0 + 1_000L, other).state
        // 换一台车机后重新计数：A 的失败不累计到 B，B 也不会触发 A 的熔断。
        assertEquals(1, state.failures)
        assertEquals(other, state.address)
        assertFalse(breaker.isBlocked(state, address, t0 + 2_000L))
        assertFalse(breaker.isBlocked(state, other, t0 + 2_000L))
    }

    @Test
    fun clockRollbackCannotExtendTheBlockForever() {
        var state = ReconnectCircuitBreaker.State()
        repeat(3) { index ->
            state = fail(state, t0 + index * 1_000L).state
        }
        // 系统时钟回拨到熔断设置前很久：剩余时间超过冷却时长，视为失效而不是永久熔断
        assertFalse(breaker.isBlocked(state, address, state.blockedUntilMs - ReconnectCircuitBreaker.COOLDOWN_MILLIS - 1L))
        assertEquals(
            ReconnectCircuitBreaker.COOLDOWN_MILLIS,
            breaker.remainingMillis(state, state.blockedUntilMs - ReconnectCircuitBreaker.COOLDOWN_MILLIS - 1L),
        )
    }
}
