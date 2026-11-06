package com.mcarplay.app.diagnostics

import com.mcarplay.app.diagnostics.ConnectionProgress.Blocker
import com.mcarplay.app.diagnostics.ConnectionProgress.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias M = ConnectionMilestone

class ConnectionProgressTest {
    private var now = 0L
    private var ids = 0
    private val timeline = ConnectionTimeline({ 1_000_000 + now }, { now }, { "attempt-${++ids}" }, {})

    private fun progress() = ConnectionProgress.of(timeline.snapshot()!!)

    @Test
    fun precheckAndSelfTestHaveNoStepper() {
        val id = timeline.begin("user")
        assertNull(progress())
        timeline.finish(id, DiagnosticCode.BT_PERMISSION, true)
        assertNull(progress())
        val check = timeline.begin("carwith_self_test")
        timeline.stage(check, ConnectionStage.SELF_TEST)
        assertNull(progress())
    }

    @Test
    fun earlierLayersAreDoneAndTheCurrentOneNamesItsMissingStep() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        now = 300
        timeline.milestone(id, ConnectionMilestone.BT_LINK)
        timeline.milestone(id, ConnectionMilestone.IAP2_AUTHENTICATED)
        now = 1_200
        timeline.milestone(id, ConnectionMilestone.WIFI_CREDENTIALS)
        timeline.stage(id, ConnectionStage.WIFI)
        now = 3_200
        timeline.milestone(id, ConnectionMilestone.WIFI_JOINED)
        timeline.stage(id, ConnectionStage.DISCOVERY)
        now = 3_700
        timeline.milestone(id, ConnectionMilestone.RECEIVER_RESOLVED)
        timeline.stage(id, ConnectionStage.PAIRING)
        now = 3_800
        timeline.milestone(id, ConnectionMilestone.AIRPLAY_CONNECTED)
        now = 5_000
        val result = progress()!!
        assertEquals(
            listOf(
                StepState.DONE,
                StepState.DONE,
                StepState.DONE,
                StepState.ACTIVE,
                StepState.PENDING,
                StepState.PENDING,
                StepState.PENDING,
            ),
            result.steps.map { it.state },
        )
        assertEquals(ConnectionProgress.LAYERS, result.steps.map { it.stage })
        assertEquals(listOf(1_200L, 2_000L, 500L, 1_300L, null, null, null), result.steps.map { it.durationMs })
        assertEquals(ConnectionMilestone.AIRPLAY_CONNECTED to 3_800L, result.latest)
        assertEquals(Blocker.HOMEKIT_PAIRING, result.blocker)
        assertEquals(4_700L, result.sinceBluetoothMs)
        assertFalse(result.pastHeadUnitWindow)
    }

    @Test
    fun failureKeepsTheBlockerButDropsTheLiveCounter() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.PAIRING)
        timeline.milestone(id, ConnectionMilestone.BT_LINK)
        timeline.milestone(id, ConnectionMilestone.AIRPLAY_CONNECTED)
        now = 31_000
        timeline.finish(id, DiagnosticCode.PAIRING_FAILED, true)
        val result = progress()!!
        assertEquals(StepState.FAILED, result.steps[3].state)
        assertEquals(Blocker.HOMEKIT_PAIRING, result.blocker)
        assertNull(result.sinceBluetoothMs)
        assertFalse(result.pastHeadUnitWindow)
    }

    @Test
    fun headUnitWindowHintStartsAtThirtySecondsAndEndsWithRecord() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        now = 1_000
        timeline.milestone(id, ConnectionMilestone.BT_LINK)
        timeline.stage(id, ConnectionStage.SETUP)
        now = 30_999
        assertFalse(progress()!!.pastHeadUnitWindow)
        now = 31_000
        assertEquals(30_000L, progress()!!.sinceBluetoothMs)
        assertTrue(progress()!!.pastHeadUnitWindow)
        timeline.milestone(id, ConnectionMilestone.RECORD)
        assertNull(progress()!!.sinceBluetoothMs)
        assertFalse(progress()!!.pastHeadUnitWindow)
    }

    @Test
    fun noBluetoothLinkMeansNoWindowHint() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.WIFI)
        now = 45_000
        assertNull(progress()!!.sinceBluetoothMs)
        assertFalse(progress()!!.pastHeadUnitWindow)
    }

    @Test
    fun stoppedSessionAfterStreamingCountsAsDone() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.CARWITH)
        timeline.media(id, true, 100)
        assertEquals(StepState.ACTIVE, progress()!!.steps.last().state)
        assertNull(progress()!!.blocker)
        timeline.videoEnded(id)
        val result = progress()!!
        assertTrue(result.steps.all { it.state == StepState.DONE })
        assertNull(result.blocker)
    }

    @Test
    fun userStopBeforeStreamingIsStoppedWithoutBlocker() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.DISCOVERY)
        timeline.finish(id, DiagnosticCode.USER_STOP, false)
        val result = progress()!!
        assertEquals(StepState.STOPPED, result.steps[2].state)
        assertEquals(StepState.PENDING, result.steps[3].state)
        assertNull(result.blocker)
    }

    @Test
    fun blockerFollowsTheMissingStepOfEachLayer() {
        fun blocker(
            stage: ConnectionStage,
            vararg reached: ConnectionMilestone,
        ): Blocker? {
            val id = timeline.begin("receiver")
            timeline.stage(id, stage)
            reached.forEach { timeline.milestone(id, it) }
            return progress()!!.blocker
        }
        assertEquals(Blocker.BT_LINK, blocker(ConnectionStage.BLUETOOTH))
        assertEquals(Blocker.IAP2_AUTH, blocker(ConnectionStage.BLUETOOTH, M.BT_LINK, M.IAP2_IDENTIFIED))
        assertEquals(Blocker.WIFI_INFO, blocker(ConnectionStage.BLUETOOTH, M.BT_LINK, M.IAP2_AUTHENTICATED))
        assertNull(blocker(ConnectionStage.BLUETOOTH, M.BT_LINK, M.IAP2_AUTHENTICATED, M.WIFI_CREDENTIALS))
        assertEquals(Blocker.WIFI_JOIN, blocker(ConnectionStage.WIFI, M.WIFI_CREDENTIALS))
        assertNull(blocker(ConnectionStage.WIFI, M.WIFI_JOINED))
        assertEquals(Blocker.RECEIVER_DISCOVERY, blocker(ConnectionStage.DISCOVERY, M.WIFI_JOINED))
        assertEquals(Blocker.RECEIVER_ADDRESS, blocker(ConnectionStage.DISCOVERY, M.CTRL_INVITED))
        assertNull(blocker(ConnectionStage.DISCOVERY, M.RECEIVER_RESOLVED))
        assertEquals(Blocker.AIRPLAY_PORT, blocker(ConnectionStage.PAIRING, M.RECEIVER_RESOLVED))
        assertEquals(Blocker.HOMEKIT_PAIRING, blocker(ConnectionStage.PAIRING, M.AIRPLAY_CONNECTED, M.PAIR_SETUP))
        assertNull(blocker(ConnectionStage.PAIRING, M.AIRPLAY_CONNECTED, M.PAIR_VERIFY))
        assertEquals(Blocker.SESSION_SETUP, blocker(ConnectionStage.SETUP, M.PAIR_VERIFY, M.AUTH_SETUP))
        assertEquals(Blocker.RECORD, blocker(ConnectionStage.SETUP, M.SESSION_SETUP))
        assertNull(blocker(ConnectionStage.SETUP, M.SESSION_SETUP, M.RECORD))
        assertEquals(Blocker.CARWITH_FRAME, blocker(ConnectionStage.CARWITH, M.RECORD))
        assertNull(blocker(ConnectionStage.STREAMING))
    }

    @Test
    fun durationsUseOneDecimalBelowTenSeconds() {
        assertEquals("0.0s", ConnectionProgress.formatDuration(0))
        assertEquals("0.0s", ConnectionProgress.formatDuration(-5))
        assertEquals("1.2s", ConnectionProgress.formatDuration(1_299))
        assertEquals("9.9s", ConnectionProgress.formatDuration(9_999))
        assertEquals("10s", ConnectionProgress.formatDuration(10_000))
        assertEquals("12s", ConnectionProgress.formatDuration(12_345))
    }

    @Test
    fun marksOnlyLabelReachedLayers() {
        assertEquals(listOf("✓", "●", "✗", "■", ""), StepState.entries.map { ConnectionProgress.mark(it) })
    }

    @Test
    fun reportLinesListDurationsMilestonesAndBlocker() {
        val id = timeline.begin("receiver")
        now = 50
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        now = 462
        timeline.milestone(id, ConnectionMilestone.BT_LINK)
        now = 2_050
        timeline.stage(id, ConnectionStage.PAIRING)
        now = 2_100
        timeline.milestone(id, ConnectionMilestone.AIRPLAY_CONNECTED)
        now = 4_050
        assertEquals(
            "STAGE_DURATIONS PRECHECK=50ms BLUETOOTH=2000ms PAIRING=2000ms*\n" +
                "MILESTONES BT_LINK=+462ms AIRPLAY_CONNECTED=+2100ms\n" +
                "BLOCKED_AT PAIRING:HOMEKIT_PAIRING",
            ConnectionProgress.reportLines(timeline.snapshot()!!),
        )
        timeline.finish(id, DiagnosticCode.USER_STOP, false)
        assertEquals(
            "STAGE_DURATIONS PRECHECK=50ms BLUETOOTH=2000ms PAIRING=2000ms*\n" +
                "MILESTONES BT_LINK=+462ms AIRPLAY_CONNECTED=+2100ms",
            ConnectionProgress.reportLines(timeline.snapshot()!!),
        )
    }

    @Test
    fun reportLinesSayNoneWithoutMilestones() {
        timeline.begin("receiver")
        assertEquals("STAGE_DURATIONS PRECHECK=0ms*\nMILESTONES none", ConnectionProgress.reportLines(timeline.snapshot()!!))
    }
}
