package com.mcarplay.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAudioMixTest {
    private var now = 0L
    private val config = AudioDuckingConfig(duckDb = -20f, holdMs = 50, releaseMs = 20)
    private val mixer = NavigationAudioMixer(1_000, config) { now }

    @Test
    fun silentGuidanceKeepsMediaUnchanged() {
        val media = stereo(12_000, -6_000)

        mixer.mix(media, stereo(0, 0), config.duckDb)

        assertFalse(mixer.ducking)
        assertArrayEquals(stereo(12_000, -6_000), media)
    }

    @Test
    fun voiceIsBoostedWhileMusicFadesDownAndReturnsAfterHold() {
        mixer.mix(stereo(10_000, -10_000), null, config.duckDb)
        val during = stereo(10_000, -10_000)
        mixer.mix(during, stereo(2_000, -2_000), config.duckDb)

        assertTrue(mixer.ducking)
        assertTrue(left(during, 0) in 1_001..9_999)
        assertEquals(5_000, left(during, 47))

        now = 48_000_000L
        val tail = stereo(10_000, -10_000)
        mixer.mix(tail, stereo(0, 0), config.duckDb)
        assertEquals(1_000, left(tail, 47))

        now = 100_000_000L
        val restored = stereo(10_000, -10_000)
        mixer.mix(restored, null, config.duckDb)
        assertFalse(mixer.ducking)
        assertTrue(left(restored, 0) in 1_001..9_999)
        assertEquals(10_000, left(restored, 47))
    }

    @Test
    fun zeroDuckingLeavesMusicLevelAndStillBoostsGuidance() {
        val media = stereo(10_000, -10_000)
        mixer.mix(media, stereo(2_000, -2_000), 0f)

        assertArrayEquals(stereo(14_000, -14_000), media)
    }

    @Test
    fun baseAttenuationReducesMusicWithoutChangingBoostedGuidance() {
        val media = stereo(10_000, -10_000)
        mixer.mix(media, stereo(2_000, -2_000), config.duckDb, musicAttenuationDb = -20f)

        assertArrayEquals(stereo(4_100, -4_100), media)
    }

    @Test
    fun loudGuidanceKeepsHeadroomAndSignedPcmDoesNotWrap() {
        val media = stereo(10_000, -10_000)
        mixer.mix(media, stereo(25_000, -25_000), config.duckDb)
        assertArrayEquals(stereo(27_000, -27_000), media)

        val withPeaks = stereo(10_000, -10_000)
        mixer.mix(withPeaks, stereo(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()), config.duckDb)
        assertArrayEquals(stereo(Short.MAX_VALUE.toInt(), Short.MIN_VALUE.toInt()), withPeaks)
    }

    private fun stereo(
        left: Int,
        right: Int,
    ): ByteArray {
        val frame = byteArrayOf(left.toByte(), (left shr 8).toByte(), right.toByte(), (right shr 8).toByte())
        return ByteArray(48 * frame.size) { frame[it % frame.size] }
    }

    private fun left(
        pcm: ByteArray,
        frame: Int,
    ): Int = (pcm[frame * 4 + 1].toInt() shl 8) or (pcm[frame * 4].toInt() and 0xff)
}
