package com.mcarplay.app.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class BtSessionGateTest {
    @Test
    fun outgoingSocketThatCompletesDuringIndependentWifiHandoffIsClosed() {
        var closed = false
        val outbound = Closeable { closed = true }
        assertTrue(!BtSessionGate.runOutbound(outbound, wifiHandoffDetached = true) { error("unexpected admission") })
        assertTrue(closed)
        assertFalse(BtSessionGate.outboundPaused)
    }

    @Test
    fun inboundAcceptedWhileDialingDiscardsOnlyTheLateOutboundSocket() {
        var closed = false
        val outbound = Closeable { closed = true }
        assertFalse(BtSessionGate.outboundPaused)
        BtSessionGate.inboundAccepted()
        try {
            assertFalse(BtSessionGate.runOutbound(outbound) { error("unexpected admission") })
            assertTrue(closed)
            assertTrue(BtSessionGate.outboundPaused)
        } finally {
            BtSessionGate.inboundFinished()
        }

        closed = false
        assertTrue(BtSessionGate.runOutbound(outbound) {})
        assertFalse(closed)
    }

    @Test
    fun inboundPreemptsAdmittedOutboundAndWaitsForItToExit() {
        var closed = false
        var finished: java.util.concurrent.CountDownLatch? = null
        try {
            assertTrue(
                BtSessionGate.runOutbound(Closeable { closed = true }) {
                    finished = checkNotNull(BtSessionGate.inboundAccepted())
                    assertTrue(closed)
                    assertTrue(finished?.count == 1L)
                },
            )
            assertTrue(finished?.count == 0L)
        } finally {
            BtSessionGate.inboundFinished()
        }
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
