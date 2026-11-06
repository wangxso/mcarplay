package com.mcarplay.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlaySuppressorTest {
    @Test
    fun dropsCarPlayKeysOnlyInTheStartupWindow() {
        val suppressor = AutoPlaySuppressor()
        suppressor.arm(alreadyPlaying = false, nowMs = 1_000)
        assertTrue(suppressor.shouldDropPlayKey(1_000))
        assertTrue(suppressor.shouldDropPlayKey(6_000))
        assertFalse("A later manual press must reach the phone", suppressor.shouldDropPlayKey(6_001))
    }

    @Test
    fun alreadyPlayingMusicIsLeftAlone() {
        val suppressor = AutoPlaySuppressor()
        suppressor.arm(alreadyPlaying = true, nowMs = 0)
        assertFalse(suppressor.shouldDropPlayKey(1))
        assertFalse(suppressor.shouldPauseStartedPlayback(2))
        assertFalse(suppressor.isArmed(3))
    }

    @Test
    fun pausesStartedPlaybackOnceWithinTheWatchWindow() {
        val suppressor = AutoPlaySuppressor()
        suppressor.arm(alreadyPlaying = false, nowMs = 0)
        assertTrue(suppressor.shouldPauseStartedPlayback(1_000))
        assertFalse("Only pause once per connection", suppressor.shouldPauseStartedPlayback(2_000))
        assertTrue(suppressor.isArmed(10_000))
        assertFalse(suppressor.isArmed(15_001))

        suppressor.arm(alreadyPlaying = false, nowMs = 0)
        assertFalse(suppressor.shouldPauseStartedPlayback(15_001))
    }

    @Test
    fun disarmStopsKeyBlockingAndPausing() {
        val suppressor = AutoPlaySuppressor()
        suppressor.arm(alreadyPlaying = false, nowMs = 0)
        suppressor.disarm()
        assertFalse(suppressor.shouldDropPlayKey(10))
        assertFalse(suppressor.shouldPauseStartedPlayback(20))
        assertFalse(suppressor.isArmed(30))
    }
}
