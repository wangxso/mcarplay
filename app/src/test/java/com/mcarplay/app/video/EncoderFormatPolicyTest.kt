package com.mcarplay.app.video

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转码编码器 MediaFormat 取值的单测。
 *
 * 这里钉死的是**单位与键名**，不是编码器行为：`MediaFormat` 在 JVM 单测里是桩对象
 * （`isReturnDefaultValues = true`），只能验证我们写进去的数值；真机上编码器是否采纳
 * 这些键仍需台架与实车观察。
 */
class EncoderFormatPolicyTest {
    @Test
    fun repeatPreviousFrameAfterIsInMicroseconds() {
        // 官方文档：KEY_REPEAT_PREVIOUS_FRAME_AFTER 的单位是微秒。
        // 30 fps → 33333us；60 fps → 16666us。写成纳秒会得到 33_333_333，慢 1000 倍。
        assertEquals(33_333L, EncoderFormatPolicy.microsPerFrame(30))
        assertEquals(16_666L, EncoderFormatPolicy.microsPerFrame(60))
        assertEquals(50_000L, EncoderFormatPolicy.microsPerFrame(20))
    }

    @Test
    fun nanosecondValueWouldBeAThousandTimesTooSlow() {
        val micros = EncoderFormatPolicy.microsPerFrame(30)
        val nanoseconds = 1_000_000_000L / 30
        assertNotEquals("重复周期必须按微秒写，纳秒值会让周期变成 33 秒", nanoseconds, micros)
        assertEquals(1000L, nanoseconds / micros)
        // 30fps 的一个帧间隔约 33ms；按微秒取值应落在同一量级（允许整除截断）
        assertTrue("重复周期必须接近一个帧间隔", micros in 30_000L..34_000L)
    }

    @Test
    fun frameRateIsGuardedAgainstZeroAndNegativeValues() {
        assertEquals(EncoderFormatPolicy.MICROS_PER_SECOND, EncoderFormatPolicy.microsPerFrame(0))
        assertEquals(EncoderFormatPolicy.MICROS_PER_SECOND, EncoderFormatPolicy.microsPerFrame(-30))
    }

    @Test
    fun transcodeKeysDisableBFrames() {
        val keys = EncoderFormatPolicy.transcodeKeys(30)
        assertEquals("B 帧会给车机解码器增加重排序延迟", 0, keys.maxBFrames)
        assertEquals(33_333L, keys.repeatPreviousFrameAfterMicros)
    }

    @Test
    fun optionalKeysAreOnlyPriorityAndLatency() {
        val keys = EncoderFormatPolicy.optionalRealtimeKeys().map { it.first }
        assertEquals(listOf(MediaFormat.KEY_PRIORITY, MediaFormat.KEY_LATENCY), keys)
        // 非目标：不得把 bitrate-mode 作为默认值引入；这里同时防止将来把 CBR 混进默认键。
        assertFalse("bitrate-mode(CBR) 不是本期默认值", keys.contains(MediaFormat.KEY_BITRATE_MODE))
        assertEquals(0, EncoderFormatPolicy.PRIORITY_REALTIME)
        assertEquals(1, EncoderFormatPolicy.LATENCY_FRAMES)
    }

    @Test
    fun describeStatesTheUnitSoReportsAreUnambiguous() {
        val text = EncoderFormatPolicy.describe(30)
        assertTrue(text, text.contains("max-bframes=0"))
        assertTrue(text, text.contains("repeat-previous-frame-after=33333us"))
        assertTrue(text, text.contains("微秒"))
    }

    @Test
    fun transcodeFallbackKeepsTranscodeKeysWhenOnlyLevelIsRejected() {
        // 审查 P2：level/Profile 被拒时的最后一级不得连带丢掉 max-bframes /
        // repeat-previous-frame-after。这里钉住级数、撤销顺序，以及「只撤 level」的那一级。
        val attempts = EncoderFormatPolicy.attempts(baselineProfile = true)
        assertEquals(
            listOf(
                EncoderFormatPolicy.Attempt.FULL,
                EncoderFormatPolicy.Attempt.WITHOUT_OPTIONAL_HINTS,
                EncoderFormatPolicy.Attempt.WITHOUT_LEVEL_AND_PROFILE,
            ),
            attempts,
        )
        val last = attempts.last()
        assertFalse("最后一级必须已经撤掉 level/Profile", last.withLevelAndProfile)
        assertFalse("可选提示在任何回退级都不需要", last.withOptionalRealtime)
        assertEquals("level 4.0 / Baseline", last.dropped)
    }

    @Test
    fun localScreenPathKeepsItsTwoStepFallback() {
        // 本地屏幕路径不开 Baseline：两级，且从不写 priority/latency（与旧行为一致）。
        val attempts = EncoderFormatPolicy.attempts(baselineProfile = false)
        assertEquals(2, attempts.size)
        assertFalse(attempts.any { it.withOptionalRealtime })
        assertTrue(attempts[0].withLevelAndProfile)
        assertFalse(attempts[1].withLevelAndProfile)
        assertNull(attempts[0].dropped)
    }
}
