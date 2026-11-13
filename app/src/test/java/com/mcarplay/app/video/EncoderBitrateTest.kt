package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderBitrateTest {
    @Test
    fun bitrateHasFloorForSmallCanvas() {
        assertEquals(2_000_000, EncoderBitrate.forSize(640, 360, fps = 30))
    }

    @Test
    fun bitrateScalesWithResolutionAndSaturatesAtTenMbps() {
        assertEquals(5_529_600, EncoderBitrate.forSize(1280, 720, fps = 30))
        assertEquals(8_294_400, EncoderBitrate.forSize(1280, 720, fps = 60))
        assertEquals(10_000_000, EncoderBitrate.forSize(1920, 1080, fps = 30))
        assertEquals(10_000_000, EncoderBitrate.forSize(2560, 1440, fps = 30))
    }

    @Test
    fun sixtyFpsCapIsFifteenMbps() {
        assertEquals(15_000_000, EncoderBitrate.forSize(1920, 1080, fps = 60))
        assertEquals(15_000_000, EncoderBitrate.forSize(3840, 2160, fps = 60))
    }
}
