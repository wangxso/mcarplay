package com.mcarplay.app.net

import com.mcarplay.app.net.StreamingInviteReconnect.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingInviteReconnectTest {
    private fun decide(
        streaming: Boolean = true,
        enabled: Boolean = true,
        now: Long = 100_000L,
        lastReconnect: Long = 0L,
    ) = StreamingInviteReconnect.decide(streaming, enabled, now, lastReconnect)

    @Test
    fun invitesOutsideStreamingBelongToTheConnectFlow() {
        assertEquals(Decision.NOT_STREAMING, decide(streaming = false))
        assertEquals(Decision.NOT_STREAMING, decide(streaming = false, enabled = false))
    }

    @Test
    fun unregisteredReceiversKeepTheirSession() {
        assertEquals(Decision.NOT_ENABLED, decide(enabled = false))
        assertEquals(Decision.NOT_ENABLED, decide(enabled = false, lastReconnect = 99_000L))
    }

    @Test
    fun firstStreamingInviteReconnects() {
        assertEquals(Decision.RECONNECT, decide())
    }

    @Test
    fun invitesWithinFifteenSecondsOfAReconnectAreIgnored() {
        assertEquals(Decision.DEBOUNCED, decide(now = 100_000L, lastReconnect = 100_000L))
        assertEquals(Decision.DEBOUNCED, decide(now = 114_999L, lastReconnect = 100_000L))
        assertEquals(Decision.RECONNECT, decide(now = 115_000L, lastReconnect = 100_000L))
        assertEquals(Decision.RECONNECT, decide(now = 160_000L, lastReconnect = 100_000L))
    }
}
