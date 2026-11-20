package com.mcarplay.app.bt

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase2ReaderTest {
    @Test
    fun verifiesPersistenceWithProductionGuard() {
        val arguments = InstrumentationRegistry.getArguments()
        val marker = ReconnectPrefsInstrumentationTestSupport.requireMarker(arguments)
        val scenario = ReconnectPrefsInstrumentationTestSupport.requireScenario(arguments)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val handoff = ReconnectPrefsInstrumentationTestSupport.readPhase1Handoff(context)

        assertEquals(marker, handoff.getString("marker"))
        assertEquals(scenario, handoff.getString("scenario"))
        val phase1Pid = handoff.getInt("phase1Pid")
        val phase2Pid = Process.myPid()
        assertTrue("Phase 2 PID $phase2Pid must differ from Phase 1 PID $phase1Pid", phase1Pid != phase2Pid)
        val expectedKey = "mcarplay.test.handoff_$marker"
        val expectedValue = "expected:$scenario"
        assertEquals(expectedKey, handoff.getString("expectedKey"))
        assertEquals(expectedValue, handoff.getString("expectedValue"))
        val prefs = ReconnectPrefsInstrumentationTestSupport.settings(context)
        assertEquals(expectedValue, prefs.getString(expectedKey, null))
        assertEquals(
            ReconnectPrefsInstrumentationTestSupport.UNRELATED_VALUE,
            prefs.getString(ReconnectPrefsInstrumentationTestSupport.UNRELATED_KEY, null),
        )

        try {
            when (scenario) {
                ReconnectPrefsInstrumentationTestSupport.SCENARIO_NORMAL -> verifyNormal(context)
                ReconnectPrefsInstrumentationTestSupport.SCENARIO_LEGACY -> verifyLegacy(context)
                ReconnectPrefsInstrumentationTestSupport.SCENARIO_CORRUPT -> verifyCorrupt(context)
                ReconnectPrefsInstrumentationTestSupport.SCENARIO_PARTIAL -> verifyPartial(context)
            }
        } finally {
            ReconnectPrefsInstrumentationTestSupport.cleanup(context, marker)
        }
    }

    private fun verifyNormal(context: Context) {
        val peer = ReconnectPrefsInstrumentationTestSupport.PEER
        assertEquals(
            AutoReconnectGuard.Block(failures = 3, untilMs = 183_000L, remainingMs = 179_000L),
            AutoReconnectGuard.block(context, peer, 4_000L),
        )
        assertEquals(
            AutoReconnectGuard.Block(failures = 3, untilMs = 183_000L, remainingMs = 179_000L),
            AutoReconnectGuard.activeBlock(context, 4_000L),
        )
    }

    private fun verifyLegacy(context: Context) {
        val prefs = ReconnectPrefsInstrumentationTestSupport.settings(context)
        val unrelatedKey = ReconnectPrefsInstrumentationTestSupport.UNRELATED_KEY
        val unrelatedValue = ReconnectPrefsInstrumentationTestSupport.UNRELATED_VALUE
        val legacyKeys = ReconnectPrefsInstrumentationTestSupport.LEGACY_KEYS

        assertEquals("{}", prefs.getString(ReconnectPrefsInstrumentationTestSupport.KEY_STATES, null))
        legacyKeys.forEachIndexed { index, key ->
            assertEquals("legacy-$index", prefs.getString(key, null))
        }
        assertEquals(unrelatedValue, prefs.getString(unrelatedKey, null))

        AutoReconnectGuard.clear(context)

        assertFalse(prefs.contains(ReconnectPrefsInstrumentationTestSupport.KEY_STATES))
        legacyKeys.forEach { key ->
            assertFalse(prefs.contains(key))
        }
        assertEquals(unrelatedValue, prefs.getString(unrelatedKey, null))
    }

    private fun verifyCorrupt(context: Context) {
        val peer = ReconnectPrefsInstrumentationTestSupport.PEER
        assertNull(AutoReconnectGuard.block(context, peer, 1_000L))
        assertNull(AutoReconnectGuard.activeBlock(context, 1_000L))
        assertEquals(emptyMap<String, ReconnectCircuitBreaker.State>(), decodeAutoReconnectStates("not-json"))
    }

    private fun verifyPartial(context: Context) {
        val prefs = ReconnectPrefsInstrumentationTestSupport.settings(context)
        val decoded = decodeAutoReconnectStates(prefs.getString(ReconnectPrefsInstrumentationTestSupport.KEY_STATES, null))
        assertEquals(2, decoded.size)
        val missing = requireNotNull(decoded["MISSING_FIELDS"])
        assertNull(missing.address)
        assertEquals(0, missing.failures)
        assertEquals(0L, missing.firstFailureAtMs)
        assertEquals(0L, missing.lastFailureAtMs)
        assertEquals(0L, missing.blockedUntilMs)
        assertNull(missing.recordedAttempt)
        val wrongTypes = requireNotNull(decoded["WRONG_TYPES"])
        assertEquals("aa:bb:cc:dd:ee:01", wrongTypes.address)
        assertEquals(0, wrongTypes.failures)
        assertEquals(0L, wrongTypes.firstFailureAtMs)
        assertEquals(0L, wrongTypes.lastFailureAtMs)
        assertEquals(0L, wrongTypes.blockedUntilMs)
        assertEquals("42", wrongTypes.recordedAttempt)
        assertNull(AutoReconnectGuard.block(context, ReconnectPrefsInstrumentationTestSupport.PEER, 1_000L))
        assertNull(AutoReconnectGuard.activeBlock(context, 1_000L))
    }
}
