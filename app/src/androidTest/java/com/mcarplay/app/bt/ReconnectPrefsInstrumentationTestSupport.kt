package com.mcarplay.app.bt

import android.content.Context
import android.os.Bundle
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal object ReconnectPrefsInstrumentationTestSupport {
    const val ARG_SCENARIO = "scenario"
    const val ARG_MARKER = "marker"
    const val HANDOFF_FILE = "rgp07-phase-handoff.json"

    const val SCENARIO_NORMAL = "normal"
    const val SCENARIO_LEGACY = "legacy"
    const val SCENARIO_CORRUPT = "corrupt"
    const val SCENARIO_PARTIAL = "partial"

    const val PEER = "aa:bb:cc:dd:ee:01"
    const val UNRELATED_KEY = "mcarplay.test.unrelated_key"
    const val UNRELATED_VALUE = "kept-after-phase1"
    const val KEY_STATES = "auto_reconnect_guard_states"

    val LEGACY_KEYS =
        listOf(
            "auto_reconnect_guard_address",
            "auto_reconnect_guard_failures",
            "auto_reconnect_guard_first_failure_at",
            "auto_reconnect_guard_last_failure_at",
            "auto_reconnect_guard_blocked_until",
            "auto_reconnect_guard_recorded_attempt",
        )

    fun requireMarker(arguments: Bundle): String {
        val marker =
            arguments.getString(ARG_MARKER)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing unique marker argument")
        return marker
    }

    fun requireScenario(arguments: Bundle): String {
        val scenario =
            arguments.getString(ARG_SCENARIO)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing scenario argument")
        val scenarios = listOf(SCENARIO_NORMAL, SCENARIO_LEGACY, SCENARIO_CORRUPT, SCENARIO_PARTIAL)
        check(scenario in scenarios) { "Unknown scenario: $scenario" }
        return scenario
    }

    fun settings(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun handoffFile(context: Context): File = File(context.filesDir, HANDOFF_FILE)

    fun writePhase1Handoff(
        context: Context,
        marker: String,
        scenario: String,
        expectedKey: String,
        expectedValue: String,
    ) {
        val phase1Pid = Process.myPid()
        val handoff =
            JSONObject()
                .put("marker", marker)
                .put("phase1Pid", phase1Pid)
                .put("scenario", scenario)
                .put("expectedKey", expectedKey)
                .put("expectedValue", expectedValue)
        val file = handoffFile(context)
        FileOutputStream(file).use { stream ->
            stream.write(handoff.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        val persisted = JSONObject(file.readText(Charsets.UTF_8))
        check(persisted.getString("marker") == marker) { "Handoff marker mismatch after flush" }
        check(persisted.getInt("phase1Pid") == phase1Pid) { "Handoff phase1Pid mismatch after flush" }
        check(persisted.getString("scenario") == scenario) { "Handoff scenario mismatch after flush" }
        check(persisted.getString("expectedKey") == expectedKey) { "Handoff expectedKey mismatch after flush" }
        check(persisted.getString("expectedValue") == expectedValue) { "Handoff expectedValue mismatch after flush" }
    }

    fun readPhase1Handoff(context: Context): JSONObject {
        val file = handoffFile(context)
        check(file.isFile) { "Phase 1 handoff is missing: ${file.path}" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    fun cleanup(
        context: Context,
        marker: String,
    ) {
        handoffFile(context).delete()
        AutoReconnectGuard.clear(context)
        settings(context)
            .edit()
            .remove("mcarplay.test.handoff_$marker")
            .apply()
    }
}
