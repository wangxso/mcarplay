package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 编码参数取值与失败释放（issue #72 任务 D 的接线部分）。
 *
 * 键的归属与逐级回退由 [EncoderFormatPolicy]（见 EncoderFormatPolicyTest）负责；
 * 这里钉码率算术与「codec 创建后任一步失败都必须释放（含已创建的 Surface）」。
 */
class EncoderFormatPlanTest {
    @Test
    fun bitrateKeepsTheExistingFormulaAndOrder() {
        // 与重构前的内联表达式逐值一致：像素×6 钳在 2–10 Mbps，60fps×1.5，上限 15 Mbps。
        assertEquals(2_000_000, EncoderFormatRules.bitrateFor(320, 180, 30))
        assertEquals(5_529_600, EncoderFormatRules.bitrateFor(1280, 720, 30))
        assertEquals(8_640_000, EncoderFormatRules.bitrateFor(1600, 900, 30))
        assertEquals("1920×1080 已触上限", 10_000_000, EncoderFormatRules.bitrateFor(1920, 1080, 30))
        assertEquals(10_000_000, EncoderFormatRules.bitrateFor(4096, 2160, 30))
        assertEquals(15_000_000, EncoderFormatRules.bitrateFor(4096, 2160, 60))
        assertEquals(12_960_000, EncoderFormatRules.bitrateFor(1600, 900, 60))
    }

    @Test
    fun releaseOnFailureReleasesOnceAndRethrows() {
        var releases = 0
        try {
            releaseOnFailure({ releases++ }) { throw IllegalStateException("configure failed") }
            fail("必须原样抛出")
        } catch (expected: IllegalStateException) {
            assertEquals("configure failed", expected.message)
        }
        assertEquals(1, releases)
    }

    @Test
    fun releaseOnFailureDoesNotReleaseOnSuccess() {
        var releases = 0
        val value = releaseOnFailure({ releases++ }) { "ok" }
        assertEquals("ok", value)
        assertEquals(0, releases)
    }

    @Test
    fun releaseOnFailureIgnoresThrowingReleaseAndKeepsOriginalError() {
        try {
            releaseOnFailure({ throw IllegalStateException("release also failed") }) {
                throw IllegalArgumentException("configure failed")
            }
            fail("原始异常必须优先")
        } catch (expected: IllegalArgumentException) {
            assertEquals("configure failed", expected.message)
        }
    }

    @Test
    fun avcMimeTypeAndEncodeFlagAreUnchanged() {
        assertEquals("video/avc", android.media.MediaFormat.MIMETYPE_VIDEO_AVC)
        assertEquals(1, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
    }
}
