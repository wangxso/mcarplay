package com.mcarplay.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ConnectionTimelineTest {
    private var now = 0L
    private var ids = 0
    private val records = mutableListOf<String>()
    private val timeline = ConnectionTimeline({ 1_000_000 + now }, { now }, { "attempt-${++ids}" }, { records.add(it) })

    @Test
    fun usesMonotonicElapsedTimeAndWritesSchemaAndAttempt() {
        val id = timeline.begin("user")
        now = 2_500
        timeline.stage(id, ConnectionStage.PAIRING)
        val event = JSONObject(records.last())
        assertEquals(1, event.getInt("schema"))
        assertEquals(id, event.getString("attempt"))
        assertEquals(2_500L, event.getLong("elapsedMs"))
        assertEquals("PAIRING", event.getString("stage"))
    }

    @Test
    fun sourceInputIsNotProofOfCarPlayOutput() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.CARWITH)
        timeline.sourceFrame(id)
        assertEquals(0L, timeline.snapshot()!!.videoFrames)
        assertEquals("waiting", timeline.snapshot()!!.status)
        assertTrue(timeline.snapshot()!!.summary().contains("尚未向接收端"))
        timeline.media(id, true, 500)
        assertEquals("streaming", timeline.snapshot()!!.status)
        assertEquals(ConnectionStage.STREAMING, timeline.snapshot()!!.stage)
    }

    @Test
    fun countsNativeAudioAndSentAudioSeparately() {
        val id = timeline.begin("receiver")
        timeline.media(id, false, 4096)
        assertEquals(1L, timeline.snapshot()!!.audioChunks)
        assertEquals(0L, timeline.snapshot()!!.audioPackets)
        timeline.audioPacket(id)
        assertEquals(1L, timeline.snapshot()!!.audioPackets)
    }

    @Test
    fun ignoresOldCallbacksAfterReplacementAndMarksSupersession() {
        val old = timeline.begin("first")
        val next = timeline.begin("replacement")
        timeline.media(old, true, 100)
        timeline.finish(old, DiagnosticCode.VIDEO_CLOSED, true)
        timeline.stage(old, ConnectionStage.PAIRING)
        assertEquals(next, timeline.snapshot()!!.id)
        assertEquals("waiting", timeline.snapshot()!!.status)
        assertEquals(0L, timeline.snapshot()!!.videoFrames)
        assertTrue(records.any { JSONObject(it).optString("code") == "SUPERSEDED" })
    }

    @Test
    fun preservesOriginalFailureWhenCleanupAndLateMediaArrive() {
        val id = timeline.begin("receiver")
        now = 100
        timeline.finish(id, DiagnosticCode.CARWITH_FAILED, true, "connect failed")
        now = 2000
        timeline.finish(id, DiagnosticCode.USER_STOP, false)
        timeline.media(id, true, 100)
        assertEquals(DiagnosticCode.CARWITH_FAILED, timeline.snapshot()!!.code)
        assertEquals(100L, timeline.snapshot()!!.elapsedMs)
        assertEquals(0L, timeline.snapshot()!!.videoFrames)
    }

    @Test
    fun recordsWarningsWithoutStoppingVideo() {
        val id = timeline.begin("receiver")
        timeline.media(id, true, 100)
        timeline.issue(id, DiagnosticCode.AUDIO_UNAVAILABLE)
        timeline.media(id, true, 100)
        assertEquals("streaming", timeline.snapshot()!!.status)
        assertEquals(2L, timeline.snapshot()!!.videoFrames)
        assertEquals(DiagnosticCode.AUDIO_UNAVAILABLE, timeline.snapshot()!!.code)
    }

    @Test
    fun samplesRatherThanPersistingEveryFrameAndReportsLastVideoAge() {
        val id = timeline.begin("receiver")
        repeat(1000) { timeline.media(id, true, 100) }
        assertEquals(2, records.size)
        now = 10_000
        timeline.media(id, true, 100)
        now = 12_000
        assertEquals(3, records.size)
        assertEquals(2_000L, timeline.snapshot()!!.lastVideoMsAgo)
    }

    @Test
    fun stalledVideoIsDescribedAsMissingWritesNotReceiverFailure() {
        val id = timeline.begin("receiver")
        timeline.media(id, true, 100)
        now = 6000
        assertTrue(timeline.snapshot()!!.summary().contains("6s 未发出新视频帧"))
        assertNull(timeline.snapshot()!!.code)
    }

    @Test
    fun screenSuspensionSuppressesStallUntilANewIdrResumesOutput() {
        val id = timeline.begin("receiver")
        timeline.media(id, true, 100)
        now = 6_000
        assertTrue(timeline.snapshot()!!.summary().contains("6s 未发出新视频帧"))

        timeline.screenOutput(id, suspended = true)
        val paused = timeline.snapshot()!!
        assertTrue(paused.screenSuspended)
        assertTrue(paused.summary().contains("屏幕视频已暂停"))
        assertFalse(paused.summary().contains("未发出新视频帧"))

        now = 10_000
        timeline.media(id, true, 100)
        timeline.screenOutput(id, suspended = false)
        now = 12_000
        assertFalse(timeline.snapshot()!!.screenSuspended)
        assertFalse(timeline.snapshot()!!.summary().contains("未发出新视频帧"))

        val screenEvents = records.map(::JSONObject).filter { it.optString("event") == "SCREEN_OUTPUT" }
        assertEquals(2, screenEvents.size)
        assertTrue(screenEvents[0].getBoolean("screenSuspended"))
        assertFalse(screenEvents[1].getBoolean("screenSuspended"))
    }

    @Test
    fun telemetryFailureCannotBreakConnectionWorker() {
        val broken = ConnectionTimeline(sink = { throw IllegalStateException("disk unavailable") })
        val id = broken.begin("receiver")
        broken.media(id, true, 100)
        assertEquals(1L, broken.snapshot()!!.videoFrames)
    }

    @Test
    fun preEndpointWarningClearsWhenConnectionProgresses() {
        val id = timeline.begin("receiver")
        timeline.issue(id, DiagnosticCode.ENDPOINT_PENDING)
        timeline.stage(id, ConnectionStage.WIFI)
        assertNull(timeline.snapshot()!!.code)
    }

    @Test
    fun concurrentMediaCountersAreAccurate() {
        val id = timeline.begin("receiver")
        val executor = Executors.newFixedThreadPool(4)
        repeat(4) { executor.submit { repeat(250) { timeline.media(id, true, 10) } } }
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(1000L, timeline.snapshot()!!.videoFrames)
        assertEquals(10_000L, timeline.snapshot()!!.videoBytes)
    }

    @Test
    fun bluetoothLinkDropDuringWifiJoinIsNotRecordedAsUserStop() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.WIFI)
        // onSenderLinkClosed: issue BT_LINK_CLOSED, then set connectCancelled
        timeline.issue(id, DiagnosticCode.BT_LINK_CLOSED)
        timeline.abandon(id, cancelled = true, fallback = DiagnosticCode.WIFI_JOIN_FAILED)
        assertEquals(DiagnosticCode.BT_LINK_CLOSED, timeline.snapshot()!!.code)
        assertEquals("failed", timeline.snapshot()!!.status)
    }

    @Test
    fun realCancelIsUserStopAndUncancelledJoinFailureUsesFallback() {
        val cancelled = timeline.begin("receiver")
        timeline.abandon(cancelled, cancelled = true, fallback = DiagnosticCode.WIFI_JOIN_FAILED)
        assertEquals(DiagnosticCode.USER_STOP, timeline.snapshot()!!.code)
        assertEquals("stopped", timeline.snapshot()!!.status)

        val failed = timeline.begin("receiver")
        timeline.abandon(failed, cancelled = false, fallback = DiagnosticCode.WIFI_JOIN_FAILED)
        assertEquals(DiagnosticCode.WIFI_JOIN_FAILED, timeline.snapshot()!!.code)
        assertEquals("failed", timeline.snapshot()!!.status)
    }

    @Test
    fun exceptionKeepsStageSpecificCode() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.SETUP)
        // catch only notes the exception; finally classifies the attempt
        timeline.note(id, "EXCEPTION java.net.SocketException: reset")
        assertEquals("waiting", timeline.snapshot()!!.status)
        timeline.abandon(id, cancelled = false)
        assertEquals(DiagnosticCode.SETUP_FAILED, timeline.snapshot()!!.code)
        assertTrue(records.any { JSONObject(it).getString("detail").startsWith("EXCEPTION") })
    }

    @Test
    fun exceptionAfterBluetoothLinkClosedStaysBtLinkClosed() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.PAIRING)
        timeline.issue(id, DiagnosticCode.BT_LINK_CLOSED)
        timeline.note(id, "EXCEPTION java.io.IOException")
        timeline.abandon(id, cancelled = true)
        assertEquals(DiagnosticCode.BT_LINK_CLOSED, timeline.snapshot()!!.code)
    }

    @Test
    fun videoEndAfterStreamingIsNormalButEndBeforeAnyFrameIsFailure() {
        val streamed = timeline.begin("receiver")
        timeline.media(streamed, true, 100)
        timeline.videoEnded(streamed)
        assertEquals(DiagnosticCode.VIDEO_CLOSED, timeline.snapshot()!!.code)
        assertEquals("stopped", timeline.snapshot()!!.status)

        val never = timeline.begin("receiver")
        timeline.videoEnded(never)
        assertEquals("failed", timeline.snapshot()!!.status)
    }

    @Test
    fun slowSinkDoesNotBlockSnapshotOrFrameCounting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blocking = false
        val slow =
            ConnectionTimeline(sink = {
                if (blocking) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            })
        val id = slow.begin("receiver")
        slow.media(id, true, 100)
        blocking = true
        val writer = thread { slow.stage(id, ConnectionStage.PAIRING) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val counter = thread { repeat(100) { slow.media(id, true, 100) } }
        counter.join(1_000)
        val stillRunning = counter.isAlive
        val frames = slow.snapshot()!!.videoFrames
        release.countDown()
        writer.join(2_000)
        counter.join(2_000)
        assertTrue("frame counting waited for the sink", !stillRunning)
        assertEquals(101L, frames)
    }

    @Test
    fun recordsStayInOrderAndCarryTrack() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.WIFI)
        timeline.stage(id, ConnectionStage.DISCOVERY)
        timeline.finish(id, DiagnosticCode.USER_STOP, false)
        assertEquals(listOf("BEGIN", "STAGE", "STAGE", "END"), records.map { JSONObject(it).getString("event") })
        assertTrue(records.all { JSONObject(it).getString("track") == "carplay" })
    }

    @Test
    fun separateTracksDoNotSupersedeEachOther() {
        val selfTest = ConnectionTimeline(sink = { records.add(it) }, track = "self_test")
        val carPlay = timeline.begin("user_start")
        val check = selfTest.begin("carwith_self_test")
        selfTest.stage(check, ConnectionStage.SELF_TEST)
        timeline.finish(carPlay, DiagnosticCode.PAIRING_FAILED, true)
        assertEquals(DiagnosticCode.PAIRING_FAILED, timeline.snapshot()!!.code)
        assertEquals("waiting", selfTest.snapshot()!!.status)
        assertTrue(records.none { JSONObject(it).optString("code") == "SUPERSEDED" })
    }

    @Test
    fun milestoneKeepsFirstArrivalAndJournalsOnce() {
        val id = timeline.begin("receiver")
        now = 400
        timeline.milestone(id, ConnectionMilestone.BT_LINK, "rfcomm")
        now = 9_000
        timeline.milestone(id, ConnectionMilestone.BT_LINK, "rfcomm again")
        assertEquals(mapOf(ConnectionMilestone.BT_LINK to 400L), timeline.snapshot()!!.milestones)
        val marks = records.map { JSONObject(it) }.filter { it.getString("event") == "MILESTONE" }
        assertEquals(1, marks.size)
        assertEquals("BT_LINK", marks.single().getString("milestone"))
        assertEquals(400L, marks.single().getLong("elapsedMs"))
        assertEquals("rfcomm", marks.single().getString("detail"))
        assertTrue(records.map { JSONObject(it) }.filter { it.getString("event") != "MILESTONE" }.none { it.has("milestone") })
    }

    @Test
    fun milestoneIgnoresReplacedAndFinishedAttempts() {
        val old = timeline.begin("first")
        val next = timeline.begin("replacement")
        timeline.milestone(old, ConnectionMilestone.PAIR_VERIFY)
        assertTrue(timeline.snapshot()!!.milestones.isEmpty())
        timeline.finish(next, DiagnosticCode.PAIRING_FAILED, true)
        timeline.milestone(next, ConnectionMilestone.PAIR_VERIFY)
        assertTrue(timeline.snapshot()!!.milestones.isEmpty())
        assertTrue(records.none { JSONObject(it).getString("event") == "MILESTONE" })
    }

    @Test
    fun beginClearsMilestonesAndDurations() {
        val first = timeline.begin("first")
        timeline.stage(first, ConnectionStage.BLUETOOTH)
        timeline.milestone(first, ConnectionMilestone.BT_LINK)
        now = 1_000
        timeline.begin("second")
        val state = timeline.snapshot()!!
        assertTrue(state.milestones.isEmpty())
        assertEquals(mapOf(ConnectionStage.PRECHECK to 0L), state.stageDurations)
    }

    @Test
    fun stageDurationsAccumulateAcrossReentryAndFreezeAtEnd() {
        val id = timeline.begin("receiver")
        now = 100
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        now = 1_100
        timeline.stage(id, ConnectionStage.WIFI)
        now = 1_600
        timeline.stage(id, ConnectionStage.BLUETOOTH)
        now = 1_900
        assertEquals(1_300L, timeline.snapshot()!!.stageDurations[ConnectionStage.BLUETOOTH])
        timeline.stage(id, ConnectionStage.PAIRING)
        now = 4_900
        timeline.finish(id, DiagnosticCode.PAIRING_FAILED, true)
        now = 60_000
        val durations = timeline.snapshot()!!.stageDurations
        assertEquals(100L, durations[ConnectionStage.PRECHECK])
        assertEquals(1_300L, durations[ConnectionStage.BLUETOOTH])
        assertEquals(500L, durations[ConnectionStage.WIFI])
        assertEquals(3_000L, durations[ConnectionStage.PAIRING])
        assertNull(durations[ConnectionStage.SETUP])
    }

    @Test
    fun mediaCallbacksRecordTheirOwnMilestones() {
        val id = timeline.begin("receiver")
        now = 1_500
        timeline.stage(id, ConnectionStage.CARWITH)
        now = 2_000
        timeline.sourceFrame(id)
        now = 2_500
        timeline.media(id, true, 100)
        now = 2_700
        timeline.audioPacket(id)
        now = 5_000
        timeline.sourceFrame(id)
        timeline.media(id, true, 100)
        timeline.audioPacket(id)
        val state = timeline.snapshot()!!
        assertEquals(
            mapOf(
                ConnectionMilestone.CARWITH_FRAME to 2_000L,
                ConnectionMilestone.FIRST_VIDEO to 2_500L,
                ConnectionMilestone.FIRST_AUDIO to 2_700L,
            ),
            state.milestones,
        )
        assertEquals(1_000L, state.stageDurations[ConnectionStage.CARWITH])
        assertEquals(2_500L, state.stageDurations[ConnectionStage.STREAMING])
        val tagged = records.map { JSONObject(it) }.filter { it.has("milestone") }
        assertEquals(listOf("FIRST_CARWITH_VIDEO", "FIRST_VIDEO", "FIRST_AUDIO_PACKET"), tagged.map { it.getString("event") })
    }

    @Test
    fun firstVideoFromEarlierStageStillCountsThatStage() {
        val id = timeline.begin("receiver")
        timeline.stage(id, ConnectionStage.SETUP)
        now = 800
        timeline.media(id, true, 100)
        now = 1_000
        val durations = timeline.snapshot()!!.stageDurations
        assertEquals(800L, durations[ConnectionStage.SETUP])
        assertEquals(200L, durations[ConnectionStage.STREAMING])
    }
}
