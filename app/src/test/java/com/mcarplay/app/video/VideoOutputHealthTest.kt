package com.mcarplay.app.video

import com.mcarplay.app.video.VideoOutputHealth.Action
import com.mcarplay.app.video.VideoOutputHealth.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputHealthTest {
    private fun health() = VideoOutputHealth()

    /** 正常写出（间隔远小于阈值）。 */
    private fun VideoOutputHealth.normal(now: Long) =
        sample(now, 0, sourceInputSinceLastWrite = true, encoderOutputSinceLastWrite = true, paused = false, stopped = false)

    /** 转码路径：有输入、编码器无输出。 */
    private fun VideoOutputHealth.encoderSilent(now: Long) =
        sample(now, 0, sourceInputSinceLastWrite = true, encoderOutputSinceLastWrite = false, paused = false, stopped = false)

    /** 透传路径：编码器维度为 null，源有输入却没写出。 */
    private fun VideoOutputHealth.passthroughStalled(now: Long) =
        sample(now, 0, sourceInputSinceLastWrite = true, encoderOutputSinceLastWrite = null, paused = false, stopped = false)

    @Test
    fun freshSenderIsNormal() {
        val decision = health().normal(16)
        assertEquals(Action.NONE, decision.action)
        assertEquals(Kind.NORMAL, decision.kind)
    }

    @Test
    fun encoderWithoutOutputRequestsKeyFramesOnARetryIntervalThenGivesUp() {
        val health = health()
        // 5s：跨过阈值就是第 1 轮补帧（同时留 WARN 标记）
        val first = health.encoderSilent(VideoOutputHealth.WARN_MILLIS)
        assertEquals(Action.REQUEST_KEY_FRAME, first.action)
        assertEquals(Kind.NO_ENCODER_OUTPUT, first.kind)
        assertTrue(first.warning)
        assertEquals(1, first.keyFrameRequests)

        // 同一时刻反复采样：只报 WARN，不成为要帧风暴
        assertEquals(Action.WARN, health.encoderSilent(VideoOutputHealth.WARN_MILLIS + 100).action)
        assertEquals(
            Action.WARN,
            health.encoderSilent(VideoOutputHealth.WARN_MILLIS + VideoOutputHealth.KEY_FRAME_RETRY_MILLIS - 1).action,
        )

        // 满一个重试间隔后再请求
        val second =
            health.encoderSilent(VideoOutputHealth.WARN_MILLIS + VideoOutputHealth.KEY_FRAME_RETRY_MILLIS)
        assertEquals(Action.REQUEST_KEY_FRAME, second.action)
        assertEquals(2, second.keyFrameRequests)

        val third =
            health.encoderSilent(VideoOutputHealth.WARN_MILLIS + 2 * VideoOutputHealth.KEY_FRAME_RETRY_MILLIS)
        assertEquals(Action.REQUEST_KEY_FRAME, third.action)
        assertEquals(3, third.keyFrameRequests)

        // 已请求满 3 轮：不再请求，只剩诊断
        val exhausted = health.encoderSilent(VideoOutputHealth.GIVE_UP_MILLIS - 1)
        assertEquals(Action.WARN, exhausted.action)
        assertEquals(3, exhausted.keyFrameRequests)
        assertFalse(exhausted.giveUp)

        // 到达 20s：判定自动补救用尽，只报一次
        val giveUp = health.encoderSilent(VideoOutputHealth.GIVE_UP_MILLIS)
        assertEquals(Action.GIVE_UP, giveUp.action)
        assertTrue(giveUp.giveUp)
        assertTrue(health.hasGivenUp)
        val secondGiveUp = health.encoderSilent(VideoOutputHealth.GIVE_UP_MILLIS + 1_000)
        assertEquals(Action.GIVE_UP, secondGiveUp.action)
        assertFalse("GIVE_UP must be logged exactly once per stall", secondGiveUp.giveUp)
    }

    @Test
    fun keyFrameRequestsAreCappedAtThreeRounds() {
        val health = health()
        val requests = mutableListOf<Long>()
        var at = VideoOutputHealth.WARN_MILLIS
        while (at < 60_000) {
            if (health.encoderSilent(at).action == Action.REQUEST_KEY_FRAME) requests += at
            at += 100
        }
        assertEquals(
            "One stall gets at most MAX_KEY_FRAME_REQUESTS bounded requests",
            VideoOutputHealth.MAX_KEY_FRAME_REQUESTS,
            requests.size,
        )
        assertEquals(3, health.requestedKeyFrames)
    }

    @Test
    fun blockedWriteNeverRequestsAKeyFrame() {
        val health = health()
        // 写卡在 write/flush 里，或编码器在输出但写不出去：都不许补帧
        val inFlight =
            health.sample(
                VideoOutputHealth.WARN_MILLIS,
                writeInFlightMillis = VideoOutputHealth.WRITE_BLOCK_MILLIS,
                sourceInputSinceLastWrite = true,
                encoderOutputSinceLastWrite = false,
                paused = false,
                stopped = false,
            )
        assertEquals(Kind.WRITE_BLOCKED, inFlight.kind)
        assertEquals(Action.WARN, inFlight.action)
        assertEquals(0, inFlight.keyFrameRequests)

        val producedButStalled =
            health.sample(
                VideoOutputHealth.GIVE_UP_MILLIS,
                writeInFlightMillis = 0,
                sourceInputSinceLastWrite = true,
                encoderOutputSinceLastWrite = true,
                paused = false,
                stopped = false,
            )
        assertEquals(Kind.WRITE_BLOCKED, producedButStalled.kind)
        assertEquals("A blocked write must never pile a key frame behind it", 0, producedButStalled.keyFrameRequests)
        assertEquals(Action.GIVE_UP, producedButStalled.action)
    }

    @Test
    fun noSourceInputIsNotAnEncoderFaultAndNeverRequests() {
        val health = health()
        val decision =
            health.sample(
                VideoOutputHealth.WARN_MILLIS,
                0,
                sourceInputSinceLastWrite = false,
                encoderOutputSinceLastWrite = false,
                paused = false,
                stopped = false,
            )
        assertEquals(Kind.NO_SOURCE_INPUT, decision.kind)
        assertEquals(Action.WARN, decision.action)
        assertEquals(0, decision.keyFrameRequests)
    }

    @Test
    fun passthroughWithSourceInputButNoWriteIsWriteBound() {
        val health = health()
        val decision = health.passthroughStalled(VideoOutputHealth.WARN_MILLIS)
        assertEquals(Kind.WRITE_BLOCKED, decision.kind)
        assertEquals(Action.WARN, decision.action)
        assertEquals(0, decision.keyFrameRequests)
    }

    @Test
    fun newOutputResetsTheStallAndCanTriggerAgain() {
        val health = health()
        health.encoderSilent(VideoOutputHealth.WARN_MILLIS)
        health.encoderSilent(VideoOutputHealth.WARN_MILLIS + VideoOutputHealth.KEY_FRAME_RETRY_MILLIS)
        assertEquals(2, health.requestedKeyFrames)

        val recovered = health.normal(16)
        assertEquals(Action.NONE, recovered.action)
        assertTrue("Recovery after a stall is worth one log line", recovered.recovered)
        assertTrue(recovered.maxStalledMillis >= VideoOutputHealth.WARN_MILLIS)
        assertEquals(0, health.requestedKeyFrames)

        // 下一次停滞重新从头计，不受上一轮已用尽次数影响
        val next = health.encoderSilent(VideoOutputHealth.WARN_MILLIS)
        assertEquals(Action.REQUEST_KEY_FRAME, next.action)
        assertEquals(1, next.keyFrameRequests)
    }

    @Test
    fun aChangedClassificationIsReportedAgainWithoutASecondRequest() {
        val health = health()
        val noInput =
            health.sample(
                VideoOutputHealth.WARN_MILLIS,
                0,
                sourceInputSinceLastWrite = false,
                encoderOutputSinceLastWrite = false,
                paused = false,
                stopped = false,
            )
        assertEquals(Kind.NO_SOURCE_INPUT, noInput.kind)
        assertTrue(noInput.warning)

        // 源恢复输入但写不出去：分类变成写阻塞，应再记一行，但仍不补帧
        val blocked =
            health.sample(
                VideoOutputHealth.WARN_MILLIS + 100,
                writeInFlightMillis = VideoOutputHealth.WRITE_BLOCK_MILLIS,
                sourceInputSinceLastWrite = true,
                encoderOutputSinceLastWrite = false,
                paused = false,
                stopped = false,
            )
        assertEquals(Kind.WRITE_BLOCKED, blocked.kind)
        assertTrue("A changed classification deserves one new log line", blocked.warning)
        assertEquals(0, blocked.keyFrameRequests)

        val stillBlocked =
            health.sample(
                VideoOutputHealth.WARN_MILLIS + 200,
                writeInFlightMillis = VideoOutputHealth.WRITE_BLOCK_MILLIS,
                sourceInputSinceLastWrite = true,
                encoderOutputSinceLastWrite = false,
                paused = false,
                stopped = false,
            )
        assertFalse("The same classification must not repeat the log", stillBlocked.warning)
    }

    @Test
    fun onlyAnInProgressEncoderStallKeepsAPendingRequest() {
        val health = health()
        // 编码器无输出：5s 请求（发布），随后未到重试间隔的 WARN 必须保留这条待处理请求
        val request = health.encoderSilent(VideoOutputHealth.WARN_MILLIS)
        assertEquals(Action.REQUEST_KEY_FRAME, request.action)
        assertFalse(request.keepsPendingKeyFrameRequest)
        val waiting =
            health.encoderSilent(VideoOutputHealth.WARN_MILLIS + VideoOutputHealth.KEY_FRAME_RETRY_MILLIS - 1)
        assertEquals(Action.WARN, waiting.action)
        assertTrue("An unconsumed request of the same stall must survive", waiting.keepsPendingKeyFrameRequest)

        // 恢复、写阻塞、无输入、用尽都必须让待处理请求失效
        assertFalse(health.normal(16).keepsPendingKeyFrameRequest)
        assertFalse(health.passthroughStalled(VideoOutputHealth.WARN_MILLIS).keepsPendingKeyFrameRequest)

        val blocked = health()
        assertFalse(
            blocked
                .sample(
                    VideoOutputHealth.WARN_MILLIS,
                    0,
                    sourceInputSinceLastWrite = false,
                    encoderOutputSinceLastWrite = false,
                    paused = false,
                    stopped = false,
                ).keepsPendingKeyFrameRequest,
        )

        val exhausted = health()
        exhausted.encoderSilent(VideoOutputHealth.WARN_MILLIS)
        exhausted.encoderSilent(VideoOutputHealth.WARN_MILLIS + VideoOutputHealth.KEY_FRAME_RETRY_MILLIS)
        exhausted.encoderSilent(VideoOutputHealth.WARN_MILLIS + 2 * VideoOutputHealth.KEY_FRAME_RETRY_MILLIS)
        val afterCap = exhausted.encoderSilent(VideoOutputHealth.GIVE_UP_MILLIS - 1)
        assertEquals(Action.WARN, afterCap.action)
        // 仍处同一轮「编码器无输出」停滞、只是不再发新请求：已发出的请求可以继续被消费
        assertTrue(afterCap.keepsPendingKeyFrameRequest)
        // 判定用尽后必须让任何未消费的请求失效
        assertFalse(exhausted.encoderSilent(VideoOutputHealth.GIVE_UP_MILLIS).keepsPendingKeyFrameRequest)
    }

    @Test
    fun pauseAndStopResetWithoutWarningOrRequests() {
        val health = health()
        val paused =
            health.sample(
                VideoOutputHealth.GIVE_UP_MILLIS * 2,
                writeInFlightMillis = 0,
                sourceInputSinceLastWrite = false,
                encoderOutputSinceLastWrite = false,
                paused = true,
                stopped = false,
            )
        assertEquals(Action.NONE, paused.action)
        assertEquals(0, paused.keyFrameRequests)
        assertFalse(health.hasGivenUp)

        val stopped =
            health.sample(
                VideoOutputHealth.GIVE_UP_MILLIS * 2,
                0,
                sourceInputSinceLastWrite = true,
                encoderOutputSinceLastWrite = false,
                paused = false,
                stopped = true,
            )
        assertEquals(Action.NONE, stopped.action)
        assertEquals(0, stopped.keyFrameRequests)
    }

    @Test
    fun recoveryWithoutPriorStallIsNotReported() {
        val health = health()
        health.normal(16)
        val recovered = health.normal(32)
        assertFalse(recovered.recovered)
    }

    @Test
    fun summaryReflectsUsefulState() {
        val health = health()
        assertEquals("无停滞", health.summary())
        health.encoderSilent(VideoOutputHealth.WARN_MILLIS)
        assertTrue(health.summary().contains("1 次"))
        health.encoderSilent(VideoOutputHealth.GIVE_UP_MILLIS)
        assertTrue(health.summary().contains("已用尽"))
    }

    @Test
    fun thresholdsAreOrdered() {
        assertEquals(5_000L, VideoOutputHealth.WARN_MILLIS)
        assertEquals(20_000L, VideoOutputHealth.GIVE_UP_MILLIS)
        assertEquals(3, VideoOutputHealth.MAX_KEY_FRAME_REQUESTS)
    }

    @Test
    fun invalidConfigurationIsRejected() {
        listOf<() -> Unit>(
            { VideoOutputHealth(warnMillis = 0) },
            { VideoOutputHealth(giveUpMillis = VideoOutputHealth.WARN_MILLIS) },
            { VideoOutputHealth(keyFrameRetryMillis = 0) },
            { VideoOutputHealth(maxKeyFrameRequests = 0) },
            { VideoOutputHealth(writeBlockMillis = 0) },
        ).forEach { construct ->
            val failed = runCatching(construct).exceptionOrNull()
            assertTrue("$construct must be rejected", failed is IllegalArgumentException)
        }
    }
}
