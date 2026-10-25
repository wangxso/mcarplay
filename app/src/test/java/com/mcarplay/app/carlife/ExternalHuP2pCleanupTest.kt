package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalHuP2pCleanupTest {
    @Test
    fun waitsForRemoveCallbackBeforeClosingChannel() {
        val fixture = Fixture()
        var finish: (() -> Unit)? = null
        fixture.cleanup.release { finish = it }
        assertEquals(listOf("schedule"), fixture.events)
        requireNotNull(finish).invoke()
        assertEquals(listOf("schedule", "cancel", "close"), fixture.events)
    }

    @Test
    fun missingRemoveCallbackClosesOnTimeoutOnlyOnce() {
        val fixture = Fixture()
        var finish: (() -> Unit)? = null
        fixture.cleanup.release { finish = it }
        requireNotNull(fixture.timeout).run()
        requireNotNull(finish).invoke()
        fixture.cleanup.release()
        assertEquals(listOf("schedule", "timeout", "cancel", "close"), fixture.events)
    }

    @Test
    fun removeExceptionStillClosesChannel() {
        val fixture = Fixture()
        assertThrows(IllegalStateException::class.java) {
            fixture.cleanup.release { error("remove failed") }
        }
        assertEquals(listOf("schedule", "cancel", "close"), fixture.events)
    }

    @Test
    fun synchronousRemoveCallbackCancelsTimeoutAndClosesOnce() {
        val fixture = Fixture()
        fixture.cleanup.release { finished -> finished() }
        requireNotNull(fixture.timeout).run()
        assertEquals(listOf("schedule", "cancel", "close"), fixture.events)
    }

    @Test
    fun borrowedGroupClosesChannelWithoutRemovingGroup() {
        val fixture = Fixture()
        fixture.cleanup.release()
        fixture.cleanup.release()
        assertEquals(listOf("cancel", "close"), fixture.events)
    }

    private class Fixture {
        val events = mutableListOf<String>()
        var timeout: Runnable? = null
        val cleanup =
            ExternalHuP2pCleanup(
                scheduleTimeout = {
                    timeout = it
                    events += "schedule"
                },
                cancelTimeout = { events += "cancel" },
                closeChannel = { events += "close" },
                onTimeout = { events += "timeout" },
            )
    }
}
