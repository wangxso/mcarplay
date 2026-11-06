package com.mcarplay.app.diagnostics

import com.mcarplay.app.diagnostics.ConnectionMilestone.AIRPLAY_CONNECTED
import com.mcarplay.app.diagnostics.ConnectionMilestone.AUTH_SETUP
import com.mcarplay.app.diagnostics.ConnectionMilestone.BT_LINK
import com.mcarplay.app.diagnostics.ConnectionMilestone.CTRL_INVITED
import com.mcarplay.app.diagnostics.ConnectionMilestone.FIRST_AUDIO
import com.mcarplay.app.diagnostics.ConnectionMilestone.FIRST_VIDEO
import com.mcarplay.app.diagnostics.ConnectionMilestone.IAP2_AUTHENTICATED
import com.mcarplay.app.diagnostics.ConnectionMilestone.PAIR_SETUP
import com.mcarplay.app.diagnostics.ConnectionMilestone.PAIR_VERIFY
import com.mcarplay.app.diagnostics.ConnectionMilestone.RECEIVER_RESOLVED
import com.mcarplay.app.diagnostics.ConnectionMilestone.RECORD
import com.mcarplay.app.diagnostics.ConnectionMilestone.SESSION_SETUP
import com.mcarplay.app.diagnostics.ConnectionMilestone.WIFI_CREDENTIALS
import com.mcarplay.app.diagnostics.ConnectionMilestone.WIFI_JOINED
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 由艾瑞泽 8 报告推导的发送端诊断场景，不是车机日志/报文回放。
 * 使用可控时钟验证阶段独立、30 s 对时提示和新旧尝试隔离，不创建测试专用协议状态机。
 */
class Arrizo8ConnectionRegressionTest {
    private var now = 0L
    private var ids = 0
    private val records = mutableListOf<String>()
    private val timeline = ConnectionTimeline({ 1_000_000 + now }, { now }, { "arrizo-attempt-${++ids}" }, { records += it })

    @Test
    fun authenticationNetworkAndControlSuccessDoNotCompleteAirPlayPairing() {
        val id = timeline.begin("synthetic_receiver")
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        timeline.milestone(id, BT_LINK)
        timeline.milestone(id, IAP2_AUTHENTICATED)
        timeline.milestone(id, WIFI_CREDENTIALS)
        assertWaitingAt(ConnectionProgress.Blocker.WIFI_JOIN, id, ConnectionStage.WIFI)

        timeline.milestone(id, WIFI_JOINED)
        timeline.stage(id, ConnectionStage.DISCOVERY)
        timeline.milestone(id, CTRL_INVITED)
        assertEquals(ConnectionProgress.Blocker.RECEIVER_ADDRESS, progress().blocker)
        timeline.milestone(id, RECEIVER_RESOLVED)
        assertWaitingAt(ConnectionProgress.Blocker.AIRPLAY_PORT, id, ConnectionStage.PAIRING)

        timeline.milestone(id, AIRPLAY_CONNECTED)
        timeline.milestone(id, PAIR_SETUP)
        assertEquals(ConnectionProgress.Blocker.HOMEKIT_PAIRING, progress().blocker)
        assertFalse(timeline.snapshot()!!.milestones.containsKey(PAIR_VERIFY))
        assertTrue(ConnectionProgress.reportLines(timeline.snapshot()!!).contains("BLOCKED_AT PAIRING:HOMEKIT_PAIRING"))

        timeline.finish(id, DiagnosticCode.PAIRING_FAILED, failed = true)
        assertEquals("failed", timeline.snapshot()!!.status)
        assertEquals(DiagnosticCode.PAIRING_FAILED, timeline.snapshot()!!.code)
        assertEquals(0L, timeline.snapshot()!!.videoFrames)
    }

    @Test
    fun sessionActivationVideoAndAudioHaveSeparateFirstArrivalTimes() {
        val id = timeline.begin("synthetic_receiver")
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        timeline.milestone(id, BT_LINK)
        timeline.stage(id, ConnectionStage.PAIRING)
        timeline.milestone(id, PAIR_VERIFY)
        timeline.stage(id, ConnectionStage.SETUP)
        timeline.milestone(id, AUTH_SETUP)
        assertEquals(ConnectionProgress.Blocker.SESSION_SETUP, progress().blocker)
        timeline.milestone(id, SESSION_SETUP)
        assertEquals(ConnectionProgress.Blocker.RECORD, progress().blocker)

        // 仅借用报告的三个相对时刻驱动模型；不把手机写出事件等同于车机成功/解码事件。
        now = 9_690
        timeline.milestone(id, RECORD)
        timeline.stage(id, ConnectionStage.CARWITH)
        assertEquals("waiting", timeline.snapshot()!!.status)
        assertEquals(ConnectionProgress.Blocker.CARWITH_FRAME, progress().blocker)
        assertEquals(0L, timeline.snapshot()!!.videoFrames)
        assertEquals(0L, timeline.snapshot()!!.audioPackets)
        assertNull(progress().sinceBluetoothMs)

        now = 11_347
        timeline.sourceFrame(id)
        assertEquals("waiting", timeline.snapshot()!!.status)
        timeline.media(id, video = true, bytes = 1024)
        assertEquals("streaming", timeline.snapshot()!!.status)
        assertEquals(0L, timeline.snapshot()!!.audioPackets)
        assertFalse(timeline.snapshot()!!.milestones.containsKey(FIRST_AUDIO))

        now = 13_864
        timeline.audioPacket(id)
        val state = timeline.snapshot()!!
        assertEquals(9_690L, state.milestones[RECORD])
        assertEquals(11_347L, state.milestones[FIRST_VIDEO])
        assertEquals(13_864L, state.milestones[FIRST_AUDIO])
        assertEquals(1L, state.videoFrames)
        assertEquals(1L, state.audioPackets)
        assertTrue(ConnectionProgress.reportLines(state).contains("RECORD=+9690ms"))
        assertTrue(ConnectionProgress.reportLines(state).contains("FIRST_VIDEO=+11347ms"))
        assertTrue(ConnectionProgress.reportLines(state).contains("FIRST_AUDIO=+13864ms"))
    }

    @Test
    fun thirtySecondWindowIsAnObservationAndDoesNotEndThePhoneAttempt() {
        val id = timeline.begin("synthetic_receiver")
        now = 5_000
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        timeline.milestone(id, BT_LINK)
        now = 6_197
        timeline.milestone(id, IAP2_AUTHENTICATED)
        timeline.stage(id, ConnectionStage.PAIRING)
        timeline.milestone(id, AIRPLAY_CONNECTED)

        now = 34_999
        assertFalse(progress().pastHeadUnitWindow)
        now = 35_002
        assertEquals(30_002L, progress().sinceBluetoothMs)
        assertTrue(progress().pastHeadUnitWindow)
        assertEquals("waiting", timeline.snapshot()!!.status)
        assertNull(timeline.snapshot()!!.code)
        assertEquals(ConnectionProgress.Blocker.HOMEKIT_PAIRING, progress().blocker)
        assertTrue(records.none { JSONObject(it).getString("event") == "END" })

        timeline.finish(id, DiagnosticCode.PAIRING_FAILED, failed = true)
        now = 70_000
        timeline.finish(id, DiagnosticCode.USER_STOP, failed = false)
        assertEquals(35_002L, timeline.snapshot()!!.elapsedMs)
        assertEquals(DiagnosticCode.PAIRING_FAILED, timeline.snapshot()!!.code)
        assertNull(progress().sinceBluetoothMs)
    }

    @Test
    fun retryAfterFailedPairingIsANewAttemptAndLateCleanupCannotReplaceIt() {
        val first = timeline.begin("synthetic_receiver")
        timeline.stage(first, ConnectionStage.BLUETOOTH)
        timeline.milestone(first, BT_LINK)
        timeline.milestone(first, IAP2_AUTHENTICATED)
        timeline.stage(first, ConnectionStage.PAIRING)
        timeline.milestone(first, AIRPLAY_CONNECTED)
        now = 30_002
        timeline.finish(first, DiagnosticCode.PAIRING_FAILED, failed = true)

        now = 70_078
        val retry = timeline.begin("synthetic_retry")
        assertFalse(first == retry)
        assertTrue(timeline.snapshot()!!.milestones.isEmpty())
        assertEquals(0L, timeline.snapshot()!!.elapsedMs)
        assertNull(timeline.snapshot()!!.code)
        timeline.stage(retry, ConnectionStage.BLUETOOTH)
        timeline.milestone(retry, BT_LINK)
        assertEquals(0L, progress().sinceBluetoothMs)
        assertFalse(progress().pastHeadUnitWindow)

        val before = timeline.snapshot()
        timeline.milestone(first, PAIR_VERIFY)
        timeline.stage(first, ConnectionStage.STREAMING)
        timeline.sourceFrame(first)
        timeline.media(first, video = true, bytes = 4096)
        timeline.audioPacket(first)
        timeline.issue(first, DiagnosticCode.BT_LINK_CLOSED)
        timeline.videoEnded(first)
        timeline.abandon(first, cancelled = true)
        assertEquals(before, timeline.snapshot())

        val events = records.map { JSONObject(it) }
        val ends = events.filter { it.getString("event") == "END" }
        assertEquals(1, ends.size)
        assertEquals(first, ends.single().getString("attempt"))
        assertEquals("PAIRING_FAILED", ends.single().getString("code"))
        assertEquals(listOf(first, retry), events.filter { it.getString("event") == "BEGIN" }.map { it.getString("attempt") })
    }

    private fun progress() = ConnectionProgress.of(timeline.snapshot()!!)!!

    private fun assertWaitingAt(
        blocker: ConnectionProgress.Blocker,
        id: String,
        stage: ConnectionStage,
    ) {
        timeline.stage(id, stage)
        assertEquals(blocker, progress().blocker)
        assertEquals("waiting", timeline.snapshot()!!.status)
        assertEquals(0L, timeline.snapshot()!!.videoFrames)
    }
}
