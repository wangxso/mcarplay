package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // 没有熔断时换一台车机重新计数：A 的失败不累计到 B，B 也不会触发 A 的熔断。
        assertEquals(1, state.failures)
        assertEquals(other, state.address)
        assertFalse(breaker.isBlocked(state, address, t0 + 2_000L))
        assertFalse(breaker.isBlocked(state, other, t0 + 2_000L))
    }

    @Test
    fun anotherReceiversFailuresAreCountedWhileTheFirstIsCoolingDown() {
        val other = "AA:BB:CC:DD:EE:02"
        val store = ReconnectCircuitBreakerStore(breaker)
        var states = emptyMap<String, ReconnectCircuitBreaker.State>()
        repeat(3) { index ->
            states = store.recordFailure(states, address, "a$index", t0 + index * 1_000L).first
        }
        assertNotNull(store.block(states, address, t0 + 5_000L))

        // A 冷却期间 B 的失败照常累计，但不会解除 A 的熔断。
        repeat(2) { index ->
            states = store.recordFailure(states, other, "b$index", t0 + 6_000L + index * 1_000L).first
        }
        assertNull(store.block(states, other, t0 + 8_000L))
        assertEquals(2, states[store.keyOf(other)]!!.failures)
        assertNotNull(store.block(states, address, t0 + 8_000L))

        // B 的第 3 次也独立熔断；A、B 各自持有自己的冷却期。
        val (afterThird, thirdBlock) = store.recordFailure(states, other, "b2", t0 + 9_000L)
        assertNotNull(thirdBlock)
        assertNotNull(store.block(afterThird, other, t0 + 9_000L))
        assertNotNull(store.block(afterThird, address, t0 + 9_000L))

        // A 的冷却结束后只解除 A；B 仍在自己的冷却期内。
        assertNull(store.block(afterThird, address, t0 + 3_000L + ReconnectCircuitBreaker.COOLDOWN_MILLIS))
        assertNotNull(store.block(afterThird, other, t0 + 3_000L + ReconnectCircuitBreaker.COOLDOWN_MILLIS))
    }

    @Test
    fun duplicateAttemptForTheSameReceiverIsRecordedOnce() {
        val store = ReconnectCircuitBreakerStore(breaker)
        val states = store.recordFailure(emptyMap(), address, "attempt-a", t0).first
        val (deduped, block) = store.recordFailure(states, address, "attempt-a", t0 + 1_000L)
        assertNull(block)
        assertEquals(1, deduped[store.keyOf(address)]!!.failures)
    }

    @Test
    fun successClearsOnlyThatReceiversFailures() {
        val other = "AA:BB:CC:DD:EE:02"
        val store = ReconnectCircuitBreakerStore(breaker)
        var states = store.recordFailure(emptyMap(), address, "a", t0).first
        states = store.recordFailure(states, other, "b", t0 + 1_000L).first

        states = store.recordHealthy(states, address)
        assertNull(states[store.keyOf(address)])
        assertEquals(1, states[store.keyOf(other)]!!.failures)
    }

    @Test
    fun persistedStateCodecRoundTripsEachReceiver() {
        val other = "AA:BB:CC:DD:EE:02"
        val states =
            mapOf(
                address.uppercase() to
                    ReconnectCircuitBreaker.State(
                        address = address,
                        failures = 2,
                        firstFailureAtMs = 10L,
                        lastFailureAtMs = 20L,
                        blockedUntilMs = 0L,
                        recordedAttempt = "attempt-a",
                    ),
                other.uppercase() to
                    ReconnectCircuitBreaker.State(
                        address = other,
                        failures = 3,
                        firstFailureAtMs = 15L,
                        lastFailureAtMs = 25L,
                        blockedUntilMs = 30L,
                        recordedAttempt = "attempt-b",
                    ),
            )
        assertEquals(states, decodeAutoReconnectStates(encodeAutoReconnectStates(states)))
        assertEquals(emptyMap<String, ReconnectCircuitBreaker.State>(), decodeAutoReconnectStates("not-json"))
        assertEquals(emptyMap<String, ReconnectCircuitBreaker.State>(), decodeAutoReconnectStates(null))
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
