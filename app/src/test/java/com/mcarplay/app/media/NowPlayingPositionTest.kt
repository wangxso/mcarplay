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

    @Test
    fun rejectsWallClockAndMissingAnchors() {
        assertEquals(90_000L, playbackPositionAnchor(90_000L, 100_000L, null))
        assertEquals(100_000L, playbackPositionAnchor(1_700_000_000_000L, 100_000L, null))
        assertEquals(100_000L, playbackPositionAnchor(null, 101_000L, 100_000L))
        assertEquals(100_000L, playbackPositionAnchor(0L, 100_000L, null))
    }

    @Test
    fun notifiesListenersForSeekWithUnchangedTrack() {
        var notifications = 0
        val listener: () -> Unit = { notifications++ }
        MediaSessionsState.publish(playing)
        MediaSessionsState.addListener(listener)
        try {
            val sought = playing.copy(positionMs = 23_000L, anchoredAtMs = 102_000L)
            MediaSessionsState.publish(playing)
            MediaSessionsState.publish(sought)
            MediaSessionsState.publish(sought)
            assertEquals(1, notifications)
        } finally {
            MediaSessionsState.removeListener(listener)
            MediaSessionsState.publish(null)
        }
    }
}
