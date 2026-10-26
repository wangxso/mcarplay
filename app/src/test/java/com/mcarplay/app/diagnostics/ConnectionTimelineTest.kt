package com.mcarplay.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
