package com.mcarplay.app.bt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase1WriterTest {
    @Test
    fun writesScenarioStateAndHandoff() {
        val arguments = InstrumentationRegistry.getArguments()
        val marker = ReconnectPrefsInstrumentationTestSupport.requireMarker(arguments)
        val scenario = ReconnectPrefsInstrumentationTestSupport.requireScenario(arguments)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ReconnectPrefsInstrumentationTestSupport.settings(context)
        val expectedKey = "mcarplay.test.handoff_$marker"
        val expectedValue = "expected:$scenario"

        ReconnectPrefsInstrumentationTestSupport.handoffFile(context).delete()
        prefs.edit().clear().commit()
        prefs
            .edit()
            .putString(ReconnectPrefsInstrumentationTestSupport.UNRELATED_KEY, ReconnectPrefsInstrumentationTestSupport.UNRELATED_VALUE)
            .commit()
        prefs.edit().putString(expectedKey, expectedValue).commit()
        prefs.edit().putString(expectedKey, expectedValue).commit()

        when (scenario) {
            ReconnectPrefsInstrumentationTestSupport.SCENARIO_NORMAL -> {
                assertNull(
                    AutoReconnectGuard.recordFastFailure(context, ReconnectPrefsInstrumentationTestSupport.PEER, "$marker-0", 1_000L),
                )
                assertNull(
                    AutoReconnectGuard.recordFastFailure(context, ReconnectPrefsInstrumentationTestSupport.PEER, "$marker-1", 2_000L),
                )
                assertEquals(
                    AutoReconnectGuard.Block(failures = 3, untilMs = 183_000L, remainingMs = 180_000L),
                    AutoReconnectGuard.recordFastFailure(context, ReconnectPrefsInstrumentationTestSupport.PEER, "$marker-2", 3_000L),
                )
            }

            ReconnectPrefsInstrumentationTestSupport.SCENARIO_LEGACY -> {
                prefs
                    .edit()
                    .putString(ReconnectPrefsInstrumentationTestSupport.KEY_STATES, "{}")
                    .commit()
                ReconnectPrefsInstrumentationTestSupport.LEGACY_KEYS.forEachIndexed { index, key ->
                    prefs.edit().putString(key, "legacy-$index").commit()
                }
            }

            ReconnectPrefsInstrumentationTestSupport.SCENARIO_CORRUPT -> {
                prefs
                    .edit()
                    .putString(ReconnectPrefsInstrumentationTestSupport.KEY_STATES, "not-json")
                    .commit()
            }

            ReconnectPrefsInstrumentationTestSupport.SCENARIO_PARTIAL -> {
                prefs
                    .edit()
                    .putString(ReconnectPrefsInstrumentationTestSupport.KEY_STATES, PARTIAL_JSON)
                    .commit()
            }
        }

        ReconnectPrefsInstrumentationTestSupport.writePhase1Handoff(context, marker, scenario, expectedKey, expectedValue)
    }

    private companion object {
        const val PARTIAL_JSON =
            """{"MISSING_FIELDS":{},"WRONG_TYPES":{"address":"aa:bb:cc:dd:ee:01","failures":"wrong",
            "firstFailureAtMs":"wrong","lastFailureAtMs":"wrong","blockedUntilMs":"wrong","recordedAttempt":42},
            "NOT_STATE":1}"""
    }
}
