package com.mcarplay.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSourcePrefsTest {
    @Test
    fun existingAndUnrecognisedPreferencesDefaultToCarWith() {
        listOf(null, "", "hidden", "unsupported").forEach { stored ->
            assertEquals(VideoSource.CAR_WITH, VideoSource.fromId(stored))
        }
    }

    @Test
    fun eachSourceCanBeRestoredFromItsStoredId() {
        VideoSource.entries.forEach { source ->
            assertEquals(source, VideoSource.fromId(source.id))
        }
    }
}
