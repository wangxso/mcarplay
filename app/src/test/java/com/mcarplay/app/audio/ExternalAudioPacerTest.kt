package com.mcarplay.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [ExternalAudioPacer] 的断流补静音：用模拟时钟驱动，检查断流之后每个真实帧的 RTP 时间戳
 * （帧槽 × 每帧样本数）与墙钟经过的样本数相差不超过一帧。
 */
class ExternalAudioPacerTest {
    private val second = 1_000_000_000L
    private val millis = 1_000_000L

    /** 一次真实帧发送：帧槽号与发送时刻。 */
    private data class Sent(
        val slot: Long,
        val at: Long,
    )

    private class Result(
        val pacer: ExternalAudioPacer,
        val sent: List<Sent>,
        val startAt: Long,
    )

    /**
     * 模拟发送循环：上游从 0 时刻起按实时速率产出整帧（每 [deliverEvery] 帧一次性到达），
     * [gaps] 区间内不产出（CarWith 断流，断流期间的样本不会补来）。没有整帧时循环在
     * 新数据到达或 Wait 到期时醒来，与 `AudioSender.runExternalLoop` 一致。
     */
    private fun simulate(
        samplesPerFrame: Int,
        sampleRate: Int,
        durationNanos: Long,
        gaps: List<LongRange> = emptyList(),
        deliverEvery: Int = 1,
        deliveryDelay: (Long) -> Long = { 0L },
    ): Result {
        val pacer = ExternalAudioPacer(samplesPerFrame, sampleRate)
        val produceAt =
            generateSequence(0L) { it + 1 }
                .map { it * samplesPerFrame * second / sampleRate }
                .takeWhile { it < durationNanos }
                .filter { at -> gaps.none { at in it } }
                .toList()
                .chunked(deliverEvery) { chunk -> chunk.last() + deliveryDelay(chunk.last()) to chunk.size }
        var produced = 0
        var available = 0
        var now = 0L
        val sent = mutableListOf<Sent>()
        var startAt = -1L
        while (now < durationNanos) {
            while (produced < produceAt.size && produceAt[produced].first <= now) {
                available += produceAt[produced].second
                produced++
            }
            when (val step = pacer.next(now, available > 0)) {
                is ExternalAudioPacer.Step.Wait -> {
                    val wake = now + step.nanos
                    now =
                        if (available > 0 || produced >= produceAt.size) {
                            wake
                        } else {
                            minOf(wake, produceAt[produced].first.coerceAtLeast(now + 1))
                        }
                }
                ExternalAudioPacer.Step.Send -> {
                    if (startAt < 0) startAt = now
                    available--
                    sent += Sent(pacer.slots - 1, now)
                }
                ExternalAudioPacer.Step.Fill -> Unit
                is ExternalAudioPacer.Step.Skip -> Unit
            }
        }
        return Result(pacer, sent, startAt)
    }

    /** 帧槽对应的样本数与墙钟经过样本数之差（单位：样本）。 */
    private fun driftSamples(
        sent: Sent,
        startAt: Long,
        samplesPerFrame: Int,
        sampleRate: Int,
    ): Double = sent.slot.toDouble() * samplesPerFrame - (sent.at - startAt).toDouble() * sampleRate / second

    private fun assertOnWallClock(
        result: Result,
        samplesPerFrame: Int,
        sampleRate: Int,
        after: Long = 0L,
    ) {
        val checked = result.sent.filter { it.at >= after }
        assertTrue("断流后应继续发真实帧", checked.isNotEmpty())
        for (sent in checked) {
            val drift = driftSamples(sent, result.startAt, samplesPerFrame, sampleRate)
            assertTrue("slot=${sent.slot} at=${sent.at} 偏离墙钟 $drift 样本", abs(drift) <= samplesPerFrame)
        }
    }

    @Test
    fun continuousSourceSendsOnScheduleWithoutFill() {
        val result = simulate(1024, 48_000, 10 * second)
        assertEquals(0L, result.pacer.filledFrames)
        assertEquals(0L, result.pacer.skippedFrames)
        assertEquals(result.sent.size.toLong(), result.pacer.slots)
        // 10 秒 ≈ 468.75 帧
        assertEquals(469, result.sent.size)
        assertOnWallClock(result, 1024, 48_000)
    }

    @Test
    fun halfSecondGapIsFilledAndTimelineStaysOnWallClock() {
        val gap = 2 * second until 2 * second + 500 * millis
        val result = simulate(1024, 48_000, 6 * second, listOf(gap))
        // 0.5 秒 ≈ 23.4 帧，全部由静音补上
        assertTrue("filled=${result.pacer.filledFrames}", result.pacer.filledFrames in 23L..24L)
        assertEquals(result.sent.size + result.pacer.filledFrames, result.pacer.slots)
        assertOnWallClock(result, 1024, 48_000, after = gap.last)
    }

    @Test
    fun halfSecondGapOnPcmProfileStaysOnWallClock() {
        val gap = 1 * second until 1 * second + 500 * millis
        val result = simulate(AudioSender.SAMPLES_PER_PACKET, 44_100, 3 * second, listOf(gap))
        // 0.5 秒 @44.1kHz / 352 ≈ 62.6 帧
        assertTrue("filled=${result.pacer.filledFrames}", result.pacer.filledFrames in 62L..63L)
        assertOnWallClock(result, AudioSender.SAMPLES_PER_PACKET, 44_100, after = gap.last)
    }

    @Test
    fun multiSecondGapIsFilledWithoutSkipping() {
        val gap = 3 * second until 11 * second + 300 * millis
        val result = simulate(1024, 48_000, 15 * second, listOf(gap))
        // 8.3 秒 ≈ 389 帧
        assertTrue("filled=${result.pacer.filledFrames}", result.pacer.filledFrames in 388L..390L)
        assertEquals(0L, result.pacer.skippedFrames)
        assertOnWallClock(result, 1024, 48_000, after = gap.last)
    }

    @Test
    fun repeatedGapsDoNotAccumulateDrift() {
        val gaps = (1..10).map { index -> index * second until index * second + 300 * millis }
        val result = simulate(1024, 48_000, 12 * second, gaps)
        // 每次 300ms ≈ 14 帧，共 10 次
        assertTrue("filled=${result.pacer.filledFrames}", result.pacer.filledFrames in 135L..150L)
        assertOnWallClock(result, 1024, 48_000)
        val last = result.sent.last()
        assertTrue(abs(driftSamples(last, result.startAt, 1024, 48_000)) <= 1024)
    }

    @Test
    fun producerJitterBelowGraceIsNotFilled() {
        // CarWith 每 ~40ms 送一块（两帧 AAC），个别块晚到 80ms
        val result =
            simulate(1024, 48_000, 5 * second, deliverEvery = 2, deliveryDelay = { at ->
                if (at % (second / 2) <
                    50 * millis
                ) {
                    80 * millis
                } else {
                    0L
                }
            })
        assertEquals(0L, result.pacer.filledFrames)
    }

    @Test
    fun nothingIsFilledBeforeTheFirstRealFrame() {
        val pacer = ExternalAudioPacer(1024, 48_000)
        repeat(100) { index ->
            val step = pacer.next(index * 100 * millis, frameReady = false)
            assertEquals(ExternalAudioPacer.Step.Wait(ExternalAudioPacer.IDLE_WAIT_NANOS), step)
        }
        assertEquals(0L, pacer.slots)
        // 首帧立即发出，从这一刻起计时
        assertEquals(ExternalAudioPacer.Step.Send, pacer.next(20 * second, frameReady = true))
        assertTrue(pacer.next(20 * second, frameReady = true) is ExternalAudioPacer.Step.Wait)
    }

    @Test
    fun stalledSenderThreadSkipsSlotsInsteadOfBursting() {
        val pacer = ExternalAudioPacer(1024, 48_000)
        assertEquals(ExternalAudioPacer.Step.Send, pacer.next(0L, frameReady = true))
        val now = 3 * second + 7 * millis
        val step = pacer.next(now, frameReady = true)
        assertTrue("step=$step", step is ExternalAudioPacer.Step.Skip)
        assertTrue(pacer.deadline(pacer.slots) <= now)
        assertTrue(pacer.deadline(pacer.slots + 1) > now)
        assertEquals((step as ExternalAudioPacer.Step.Skip).frames, pacer.skippedFrames)
        // 跳过之后时间轴贴着墙钟，下一帧照常发出
        assertEquals(ExternalAudioPacer.Step.Send, pacer.next(now, frameReady = true))
        val drift = pacer.slots.toDouble() * 1024 - now.toDouble() * 48_000 / second
        assertTrue("drift=$drift", abs(drift) <= 1024)
    }

    @Test
    fun deadlineHasNoRoundingDriftOverLongSessions() {
        val pacer = ExternalAudioPacer(1024, 48_000)
        pacer.next(0L, frameReady = true)
        // 10 小时 = 1_687_500 帧 × 1024 / 48000
        assertEquals(36_000 * second, pacer.deadline(1_687_500L))
        val pcm = ExternalAudioPacer(AudioSender.SAMPLES_PER_PACKET, 44_100)
        pcm.next(0L, frameReady = true)
        // 352 × 44100 帧 = 352 秒
        assertEquals(352 * second, pcm.deadline(44_100L))
    }
}
