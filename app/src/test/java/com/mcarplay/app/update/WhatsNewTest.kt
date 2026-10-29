package com.mcarplay.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test
    fun freshInstallDoesNotShow() {
        assertFalse(WhatsNew.shouldShow(firstInstallTime = 100, lastUpdateTime = 100, shownUpdateTime = 0))
    }

    @Test
    fun updateShowsOnceThenStaysQuiet() {
        assertTrue(WhatsNew.shouldShow(firstInstallTime = 100, lastUpdateTime = 200, shownUpdateTime = 0))
        assertFalse(WhatsNew.shouldShow(firstInstallTime = 100, lastUpdateTime = 200, shownUpdateTime = 200))
    }

    @Test
    fun everyLaterUpdateShowsAgain() {
        assertTrue(WhatsNew.shouldShow(firstInstallTime = 100, lastUpdateTime = 300, shownUpdateTime = 200))
    }

    @Test
    fun unknownPackageTimesDoNotShow() {
        assertFalse(WhatsNew.shouldShow(firstInstallTime = 0, lastUpdateTime = 0, shownUpdateTime = 0))
    }

    @Test
    fun localBuildsUseUnreleasedEntry() {
        assertEquals(ReleaseNotes.UNRELEASED, WhatsNew.changelogTag(""))
        assertEquals("v0.1.3-test.3", WhatsNew.changelogTag("v0.1.3-test.3"))
    }
}
