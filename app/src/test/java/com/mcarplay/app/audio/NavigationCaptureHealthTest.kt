package com.mcarplay.app.audio

import com.mcarplay.app.audio.NavigationPlaybackState.IDLE
import com.mcarplay.app.audio.NavigationPlaybackState.PLAYING
import com.mcarplay.app.audio.NavigationPlaybackState.UNKNOWN
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCaptureHealthTest {
    @Test
    fun silentOrMissingPcmFallsBackAtTheDeadline() {
        val health = NavigationCaptureHealth(1_000L)
        assertFalse(health.shouldRestoreGenericCapture(null, 1_000L, UNKNOWN))
        assertFalse(health.shouldRestoreGenericCapture(ByteArray(16), 30_999L, UNKNOWN))
        assertTrue(health.shouldRestoreGenericCapture(null, 31_000L, UNKNOWN))
    }

    @Test
    fun idleDoesNotCountButMultipleSilentPlaybackBurstsDo() {
        val health = NavigationCaptureHealth(0L)
        val silence = ByteArray(16)
        assertFalse(health.shouldRestoreGenericCapture(silence, 500L, IDLE))
        assertFalse(health.shouldRestoreGenericCapture(silence, 70_000L, PLAYING))
        assertFalse(health.shouldRestoreGenericCapture(silence, 73_000L, IDLE))
        assertFalse(health.shouldRestoreGenericCapture(silence, 90_000L, PLAYING))
        assertTrue(health.shouldRestoreGenericCapture(silence, 93_000L, PLAYING))
    }

    @Test
    fun aValidSamplePreventsFallbackDuringNormalQuietIntervals() {
        val health = NavigationCaptureHealth(1_000L)
        assertFalse(health.shouldRestoreGenericCapture(byteArrayOf(0, 0, 0, -1), 31_000L, PLAYING))
        assertTrue(health.verified)
        assertFalse(health.shouldRestoreGenericCapture(ByteArray(16), 90_000L, PLAYING))
        assertFalse(health.shouldRestoreGenericCapture(null, 120_000L, UNKNOWN))
    }
}
