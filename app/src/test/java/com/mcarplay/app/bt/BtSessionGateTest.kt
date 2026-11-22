package com.mcarplay.app.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class BtSessionGateTest {
    @Test
    fun inboundAcceptedWhileDialingDiscardsOnlyTheLateOutboundSocket() {
        var closed = false
        val outbound = Closeable { closed = true }
        assertFalse(BtSessionGate.outboundPaused)
        BtSessionGate.inboundAccepted()
        try {
            assertTrue(BtSessionGate.discardLateOutbound(outbound))
            assertTrue(closed)
            assertTrue(BtSessionGate.outboundPaused)
        } finally {
            BtSessionGate.inboundFinished()
        }

        closed = false
        assertFalse(BtSessionGate.discardLateOutbound(outbound))
        assertFalse(closed)
    }

    @Test
    fun olderInboundFinishingKeepsTheNewerInboundInControl() {
        BtSessionGate.inboundAccepted()
        BtSessionGate.inboundAccepted()
        BtSessionGate.inboundFinished()
        try {
            assertTrue(BtSessionGate.outboundPaused)
        } finally {
            BtSessionGate.inboundFinished()
        }
        assertFalse(BtSessionGate.outboundPaused)
    }
}
