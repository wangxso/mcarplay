package com.mcarplay.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmResamplerTest {
    @Test
    fun equalRatePreservesSignedStereoSamples() {
        val input = samples(-32768, 32767, -1, 1, 123, -456)
        assertArrayEquals(input, PcmResampler(48_000, 2, 48_000).convert(input))
    }

    @Test
    fun duplicatesMonoAndInterpolates() {
        assertArrayEquals(samples(-1000, -1000, 0, 0, 1000, 1000), PcmResampler(24_000, 1, 48_000).convert(samples(-1000, 1000)))
    }

    @Test
    fun chunkBoundariesDoNotChangeOutputOrAccumulateClockError() {
        val input = ByteArray(44_101 * 4) { (it * 13).toByte() }
        val whole = PcmResampler(44_100, 2, 48_000).convert(input)
        val streaming = PcmResampler(44_100, 2, 48_000)
        val chunks =
            input
                .toList()
                .chunked(92)
                .flatMap { streaming.convert(it.toByteArray()).toList() }
                .toByteArray()
        assertEquals(48_001 * 4, whole.size)
        assertArrayEquals(whole, chunks)
    }

    @Test
    fun downsamplingKeepsStereoChannelsSeparate() {
        assertArrayEquals(samples(1, -1, 3, -3), PcmResampler(48_000, 2, 24_000).convert(samples(1, -1, 2, -2, 3, -3)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteSampleFrames() {
        PcmResampler(44_100, 2, 48_000).convert(byteArrayOf(1, 2))
    }

    private fun samples(vararg values: Int): ByteArray =
        ByteBuffer
            .allocate(values.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }
            .array()
}
