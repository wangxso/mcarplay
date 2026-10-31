package com.mcarplay.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpSendGateTest {
    private var now = 0L
    private val gate = UdpSendGate(graceNanos = 3_000_000_000L, clock = { now })

    @Test
    fun toleratesBriefOutageAndReportsDroppedPackets() {
        assertNull(gate.onSuccess())
        assertTrue(gate.onFailure())
        now += 20_000_000L
        assertTrue(gate.onFailure())
        now += 20_000_000L
        assertEquals(2, gate.onSuccess())
        assertNull(gate.onSuccess())
    }

    @Test
    fun failsOnceOutageOutlastsGrace() {
        assertTrue(gate.onFailure())
        now += 2_999_000_000L
        assertTrue(gate.onFailure())
        now += 1_000_000L
        assertFalse(gate.onFailure())
        assertEquals(3_000L, gate.failingMillis())
    }

    @Test
    fun recoveryRestartsGraceWindow() {
        assertTrue(gate.onFailure())
        now += 2_500_000_000L
        gate.onSuccess()
        now += 1_000_000_000L
        assertTrue(gate.onFailure())
        now += 2_500_000_000L
        assertTrue(gate.onFailure())
        assertEquals(2, gate.dropped)
    }
}
