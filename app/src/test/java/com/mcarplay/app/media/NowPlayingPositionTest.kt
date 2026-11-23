package com.mcarplay.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingPositionTest {
    private val playing =
        MediaSessionsState.NowPlaying(
            title = null,
            artist = null,
            playing = true,
            positionMs = 10_000L,
            durationMs = 30_000L,
            anchoredAtMs = 100_000L,
        )

    @Test
    fun extrapolatesFromPlayerReportedTimeAndClampsToDuration() {
        assertEquals(10_000L, playing.displayPositionAt(99_000L))
        assertEquals(13_000L, playing.displayPositionAt(103_000L))
        assertEquals(30_000L, playing.displayPositionAt(123_000L))
    }

    @Test
    fun keepsPausedPositionAndHandlesUnknownPosition() {
        assertEquals(10_000L, playing.copy(playing = false).displayPositionAt(103_000L))
        assertEquals(0L, playing.copy(positionMs = -1L).displayPositionAt(103_000L))
    }
}
