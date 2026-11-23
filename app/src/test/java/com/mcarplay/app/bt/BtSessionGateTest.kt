package com.mcarplay.app.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.SocketTimeoutException

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
    fun samePeerInboundIsRejectedDuringDialAndAcceptedAfterFailure() {
        assertNull(
            BtSessionGate.dial<Unit>("AA:BB:CC:DD:EE:FF") {
                assertNull(BtSessionGate.admitInbound("aa:bb:cc:dd:ee:ff"))
                null
            },
        )
        checkNotNull(BtSessionGate.admitInbound("AA:BB:CC:DD:EE:FF"))
        BtSessionGate.inboundFinished()
    }

    @Test
    fun timedOutDialReleasesSamePeer() {
        assertThrows(SocketTimeoutException::class.java) {
            BtSessionGate.dial<Unit>("AA") { throw SocketTimeoutException() }
        }
        checkNotNull(BtSessionGate.admitInbound("AA"))
        BtSessionGate.inboundFinished()
    }

    @Test
    fun differentPeerCanStillClaimInboundDuringDial() {
        var closed = false
        val outbound = Closeable { closed = true }
        assertFalse(BtSessionGate.outboundPaused)
        BtSessionGate.dial<Unit>("AA") {
            checkNotNull(BtSessionGate.admitInbound("BB"))
            Unit
        }
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
                    finished = checkNotNull(BtSessionGate.admitInbound("AA")).outboundFinished
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
        checkNotNull(BtSessionGate.admitInbound("AA"))
        checkNotNull(BtSessionGate.admitInbound("AA"))
        BtSessionGate.inboundFinished()
        try {
            assertTrue(BtSessionGate.outboundPaused)
        } finally {
            BtSessionGate.inboundFinished()
        }
        assertFalse(BtSessionGate.outboundPaused)
    }
}
