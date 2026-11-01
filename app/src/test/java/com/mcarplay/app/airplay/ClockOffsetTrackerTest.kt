package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockOffsetTrackerTest {
    @Test
    fun requestTransmitTimeIsReadFromNtpField() {
        val request = ByteArray(32)
        // t3 = 5 秒 + 0.5 秒（分数 0x80000000）
        request[27] = 5
        request[28] = 0x80.toByte()
        assertEquals(5_500_000_000L, TimingServer.requestTransmitNanos(request))
    }

    @Test
    fun hugeFirstStepRebasesInsteadOfReportingDrift() {
        // 帕萨特 22:45：#1→#2 时钟差 2208930692091ms，旧实现此后一直报几万亿毫秒的漂移
        val tracker = ClockOffsetTracker()
        val base = 1_000_000_000_000L
        tracker.record(receiverNanos = 0L, localNanos = base)
        val rebased = tracker.record(receiverNanos = -2_208_930_692_091_000_000L, localNanos = base + 7_000_000L)
        assertTrue(rebased.text, rebased.text.contains("换基准"))
        val next = tracker.record(receiverNanos = -2_208_930_692_091_000_000L + 1_000_000_000L, localNanos = base + 1_007_000_000L)
        assertFalse(next.jumped)
        assertTrue(next.text, next.text.contains("漂移 0.0ms"))
    }

    @Test
    fun reportsDriftAndJumpsRelativeToFirstSample() {
        val tracker = ClockOffsetTracker(jumpMillis = 50L)
        val base = 1_000_000_000_000L
        assertFalse(tracker.record(receiverNanos = 0L, localNanos = base).jumped)
        // 3 秒后车机时钟慢了 2ms：漂移不算跳变
        val steady = tracker.record(receiverNanos = 3_000_000_000L, localNanos = base + 3_002_000_000L)
        assertFalse(steady.jumped)
        assertTrue(steady.text, steady.text.contains("漂移 2.0ms"))
        assertTrue(steady.text, steady.text.contains("本次间隔 3002ms"))
        // 车机时钟突然前跳 200ms
        val jump = tracker.record(receiverNanos = 6_200_000_000L, localNanos = base + 6_002_000_000L)
        assertTrue(jump.jumped)
        assertTrue(jump.text, jump.text.contains("时钟差跳变 -200ms"))
    }
}
