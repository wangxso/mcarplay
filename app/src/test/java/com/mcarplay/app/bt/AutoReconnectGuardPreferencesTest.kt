package com.mcarplay.app.bt

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AutoReconnectGuardPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearSettings()
    }

    @After
    fun tearDown() {
        clearSettings()
    }

    @Test
    fun recordFastFailureRoundsTripMultiplePeers() {
        val peerA = "aa:bb:cc:dd:ee:01"
        val peerB = "bb:cc:dd:ee:ff:02"
        val expectedA =
            ReconnectCircuitBreaker.State(
                address = peerA,
                failures = 3,
                firstFailureAtMs = 1_000L,
                lastFailureAtMs = 3_000L,
                blockedUntilMs = 183_000L,
                recordedAttempt = "a-3",
            )
        val expectedB =
            ReconnectCircuitBreaker.State(
                address = peerB,
                failures = 3,
                firstFailureAtMs = 4_000L,
                lastFailureAtMs = 6_000L,
                blockedUntilMs = 186_000L,
                recordedAttempt = "b-3",
            )

        assertNull(AutoReconnectGuard.recordFastFailure(context, peerA, "a-1", 1_000L))
        assertNull(AutoReconnectGuard.recordFastFailure(context, peerA, "a-2", 2_000L))
        assertEquals(
            AutoReconnectGuard.Block(failures = 3, untilMs = 183_000L, remainingMs = 180_000L),
            AutoReconnectGuard.recordFastFailure(context, peerA, "a-3", 3_000L),
        )
        assertNull(AutoReconnectGuard.recordFastFailure(context, peerB, "b-1", 4_000L))
        assertNull(AutoReconnectGuard.recordFastFailure(context, peerB, "b-2", 5_000L))
        assertEquals(
            AutoReconnectGuard.Block(failures = 3, untilMs = 186_000L, remainingMs = 180_000L),
            AutoReconnectGuard.recordFastFailure(context, peerB, "b-3", 6_000L),
        )

        val raw = settings().getString(STATES_KEY, null)
        assertNotNull(raw)
        val persisted = decodeAutoReconnectStates(raw)
        assertEquals(2, persisted.size)
        assertState(expectedA, requireNotNull(persisted["AA:BB:CC:DD:EE:01"]))
        assertState(expectedB, requireNotNull(persisted["BB:CC:DD:EE:FF:02"]))
        val repersisted = decodeAutoReconnectStates(encodeAutoReconnectStates(persisted))
        assertState(
            expectedA,
            requireNotNull(repersisted["AA:BB:CC:DD:EE:01"]),
        )
        assertState(
            expectedB,
            requireNotNull(repersisted["BB:CC:DD:EE:FF:02"]),
        )
        assertEquals(
            AutoReconnectGuard.Block(failures = 3, untilMs = 183_000L, remainingMs = 179_000L),
            AutoReconnectGuard.block(context, peerA, 4_000L),
        )
        assertEquals(
            AutoReconnectGuard.Block(failures = 3, untilMs = 186_000L, remainingMs = 180_000L),
            AutoReconnectGuard.block(context, peerB, 6_000L),
        )
        assertNotNull(AutoReconnectGuard.activeBlock(context, 6_000L))
    }

    @Test
    fun clearRemovesStatesAndLegacyKeys() {
        writeReconnectKeys()

        AutoReconnectGuard.clear(context)

        assertFalse(settings().contains(STATES_KEY))
        LEGACY_KEYS.forEach { key -> assertFalse(settings().contains(key)) }
        assertTrue(settings().contains(UNRELATED_KEY))
        assertEquals(UNRELATED_VALUE, settings().getString(UNRELATED_KEY, null))
    }

    @Test
    fun corruptReadsFallBackToEmptyStates() {
        writeState(" \n\t ")
        assertNull(AutoReconnectGuard.block(context, PEER, 1_000L))
        assertNull(AutoReconnectGuard.activeBlock(context, 1_000L))
        assertEquals(emptyMap<String, ReconnectCircuitBreaker.State>(), decodeAutoReconnectStates(settings().getString(STATES_KEY, null)))

        clearSettings()
        writeState("not-json")
        assertNull(AutoReconnectGuard.block(context, PEER, 1_000L))
        assertNull(AutoReconnectGuard.activeBlock(context, 1_000L))
        assertEquals(emptyMap<String, ReconnectCircuitBreaker.State>(), decodeAutoReconnectStates(settings().getString(STATES_KEY, null)))

        clearSettings()
        assertNull(AutoReconnectGuard.block(context, PEER, 1_000L))
        assertNull(AutoReconnectGuard.activeBlock(context, 1_000L))
        assertNull(settings().getString(STATES_KEY, null))
    }

    @Test
    fun malformedPartialStatesUseOptDefaultsWithoutThrowing() {
        writeState(
            """
            {
              "MISSING_FIELDS": {},
              "WRONG_TYPES": {
                "address": "aa:bb:cc:dd:ee:01",
                "failures": "wrong",
                "firstFailureAtMs": "wrong",
                "lastFailureAtMs": "wrong",
                "blockedUntilMs": "wrong",
                "recordedAttempt": 42
              },
              "NOT_STATE": 1
            }
            """.trimIndent(),
        )

        val persisted = decodeAutoReconnectStates(settings().getString(STATES_KEY, null))
        assertEquals(2, persisted.size)
        val missingFields = requireNotNull(persisted["MISSING_FIELDS"])
        val wrongTypes = persisted["WRONG_TYPES"]
        assertNull(missingFields.address)
        assertEquals(0, missingFields.failures)
        assertEquals(0L, missingFields.firstFailureAtMs)
        assertEquals(0L, missingFields.lastFailureAtMs)
        assertEquals(0L, missingFields.blockedUntilMs)
        assertNull(missingFields.recordedAttempt)
        assertEquals("aa:bb:cc:dd:ee:01", wrongTypes?.address)
        assertEquals(0, wrongTypes?.failures)
        assertEquals(0L, wrongTypes?.firstFailureAtMs)
        assertEquals(0L, wrongTypes?.lastFailureAtMs)
        assertEquals(0L, wrongTypes?.blockedUntilMs)
        assertEquals("42", wrongTypes?.recordedAttempt)
        assertNull(AutoReconnectGuard.block(context, "aa:bb:cc:dd:ee:01", 1_000L))
        assertNull(AutoReconnectGuard.activeBlock(context, 1_000L))
    }

    @Test
    fun clearOnlyRemovesReconnectKeysAndIsIdempotent() {
        writeReconnectKeys()

        AutoReconnectGuard.clear(context)
        val afterFirst = settings()
        assertTrue(afterFirst.contains(UNRELATED_KEY))
        assertFalse(afterFirst.contains(STATES_KEY))
        LEGACY_KEYS.forEach { key -> assertFalse(afterFirst.contains(key)) }

        AutoReconnectGuard.clear(context)
        val afterSecond = settings()
        assertTrue(afterSecond.contains(UNRELATED_KEY))
        assertFalse(afterSecond.contains(STATES_KEY))
        LEGACY_KEYS.forEach { key -> assertFalse(afterSecond.contains(key)) }
    }

    @Test
    fun appliedWriteIsReadableInSameContext() {
        val peer = "aa:bb:cc:dd:ee:01"

        assertNull(AutoReconnectGuard.recordFastFailure(context, peer, null, 1_000L))

        val raw = settings().getString(STATES_KEY, null)
        assertEquals(
            encodeAutoReconnectStates(
                mapOf(
                    "AA:BB:CC:DD:EE:01" to
                        ReconnectCircuitBreaker.State(
                            address = peer,
                            failures = 1,
                            firstFailureAtMs = 1_000L,
                            lastFailureAtMs = 1_000L,
                        ),
                ),
            ),
            raw,
        )
        assertNull(AutoReconnectGuard.block(context, peer, 1_001L))
    }

    @Test
    fun storageExceptionsPropagateWithoutRunCatching() {
        val sentinel = IllegalStateException("RGP09 sentinel")
        val wrapper =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): SharedPreferences = throw sentinel
            }

        val fromBlock =
            try {
                AutoReconnectGuard.block(wrapper, PEER, 1_000L)
                fail("Expected IllegalStateException from block")
                null
            } catch (expected: IllegalStateException) {
                expected
            }
        assertSame(sentinel, fromBlock)
        val fromClear =
            try {
                AutoReconnectGuard.clear(wrapper)
                fail("Expected IllegalStateException from clear")
                null
            } catch (expected: IllegalStateException) {
                expected
            }
        assertSame(sentinel, fromClear)
    }

    @Test
    fun manualClearIsIdempotent() {
        writeReconnectKeys()

        AutoReconnectGuard.clear(context)
        assertFalse(settings().contains(STATES_KEY))
        LEGACY_KEYS.forEach { key -> assertFalse(settings().contains(key)) }

        AutoReconnectGuard.clear(context)
        assertFalse(settings().contains(STATES_KEY))
        LEGACY_KEYS.forEach { key -> assertFalse(settings().contains(key)) }
    }

    @Test
    fun unrelatedSettingsSurviveRecordBlockAndClear() {
        settings().edit().putString(UNRELATED_KEY, UNRELATED_VALUE).commit()

        AutoReconnectGuard.recordFastFailure(context, PEER, null, 1_000L)
        AutoReconnectGuard.recordFastFailure(context, PEER, null, 2_000L)
        AutoReconnectGuard.recordFastFailure(context, PEER, null, 3_000L)
        assertNotNull(AutoReconnectGuard.block(context, PEER, 3_001L))
        AutoReconnectGuard.clear(context)

        assertEquals(UNRELATED_VALUE, settings().getString(UNRELATED_KEY, null))
        assertFalse(settings().contains(STATES_KEY))
    }

    private fun assertState(
        expected: ReconnectCircuitBreaker.State,
        actual: ReconnectCircuitBreaker.State,
    ) {
        assertEquals(expected.address, actual.address)
        assertEquals(expected.failures, actual.failures)
        assertEquals(expected.firstFailureAtMs, actual.firstFailureAtMs)
        assertEquals(expected.lastFailureAtMs, actual.lastFailureAtMs)
        assertEquals(expected.blockedUntilMs, actual.blockedUntilMs)
        assertEquals(expected.recordedAttempt, actual.recordedAttempt)
    }

    private fun settings(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun clearSettings() {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun writeState(raw: String) {
        settings().edit().putString(STATES_KEY, raw).commit()
    }

    private fun writeReconnectKeys() {
        settings()
            .edit()
            .putString(STATES_KEY, "{}")
            .putString("auto_reconnect_guard_address", "legacy-address")
            .putString("auto_reconnect_guard_failures", "2")
            .putString("auto_reconnect_guard_first_failure_at", "1000")
            .putString("auto_reconnect_guard_last_failure_at", "2000")
            .putString("auto_reconnect_guard_blocked_until", "3000")
            .putString("auto_reconnect_guard_recorded_attempt", "attempt")
            .putString(UNRELATED_KEY, UNRELATED_VALUE)
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val STATES_KEY = "auto_reconnect_guard_states"
        const val PEER = "aa:bb:cc:dd:ee:01"
        const val UNRELATED_KEY = "unrelated_key"
        const val UNRELATED_VALUE = "kept"
        val LEGACY_KEYS =
            listOf(
                "auto_reconnect_guard_address",
                "auto_reconnect_guard_failures",
                "auto_reconnect_guard_first_failure_at",
                "auto_reconnect_guard_last_failure_at",
                "auto_reconnect_guard_blocked_until",
                "auto_reconnect_guard_recorded_attempt",
            )
    }
}
