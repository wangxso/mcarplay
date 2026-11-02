package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BluetoothDiagnosticsTest {
    private val lines = mutableListOf<String>()
    private var now = 100L
    private val diagnostics = BluetoothDiagnostics(lines::add) { now }
    private val target = BluetoothDiagnostics.PeerMatch.TARGET
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test
    fun reportsPendingConnectBeforeBlockingCallReturns() {
        diagnostics.begin("1234abcd")
        diagnostics.connectStarted(diagnostics.epoch(), address, 12, target)
        now += 20_000
        val snapshot = diagnostics.summary()
        assertTrue(snapshot, snapshot.contains("outboundAttempts=1 outboundSuccess=0 outboundFailures=0"))
        assertTrue(snapshot, snapshot.contains("inFlight=1 inFlightMs=20000"))
        assertFalse(lines.joinToString(), lines.any { address in it })
    }

    @Test
    fun lateCompletionAndStateEventsCannotAlterNextRun() {
        diagnostics.begin("1234abcd")
        val oldEpoch = diagnostics.epoch()
        val oldAttempt = diagnostics.connectStarted(oldEpoch, address, 12, target)
        diagnostics.begin("abcd1234")
        val before = diagnostics.summary()
        diagnostics.connectFinished(oldAttempt, error = IOException("read failed, read ret: -1"))
        diagnostics.registration(oldEpoch, BluetoothDiagnostics.Service.IAP2, 5)
        diagnostics.incoming(oldEpoch, BluetoothDiagnostics.Service.IAP2, address, target)
        diagnostics.bondChanged(oldEpoch, address, target, 12, 10)
        assertEquals(before, diagnostics.summary())
        assertEquals(3, lines.size)
    }

    @Test
    fun stopCountsPendingAttemptAsCancelledAndFreezesElapsedTime() {
        diagnostics.begin("1234abcd")
        val attempt = diagnostics.connectStarted(diagnostics.epoch(), address, 12, target)
        now += 250
        diagnostics.finish()
        val stopped = diagnostics.summary()
        now += 10_000
        diagnostics.connectFinished(attempt, error = IOException("socket closed"))
        assertEquals(stopped, diagnostics.summary())
        assertTrue(stopped, stopped.contains("active=false elapsedMs=250"))
        assertTrue(stopped, stopped.contains("outboundFailures=0 outboundCancelled=1 inFlight=0"))
        assertTrue(lines.any { "outcome=CANCELLED" in it })
    }

    @Test
    fun registrationDoesNotCountAsReceiverConnectionAndRollbackClearsRecords() {
        diagnostics.begin("1234abcd")
        val epoch = diagnostics.epoch()
        diagnostics.registration(epoch, BluetoothDiagnostics.Service.IAP2, 5)
        diagnostics.registration(epoch, BluetoothDiagnostics.Service.CARPLAY, 10, IOException("no SDP slots"))
        diagnostics.serverStopped(epoch)
        val snapshot = diagnostics.summary()
        assertTrue(snapshot, snapshot.contains("iap2Registered=false carPlayRegistered=false"))
        assertTrue(snapshot, snapshot.contains("incomingAccepted=0 incomingRejected=0 capabilityConnections=0"))
        assertTrue(lines.any { "evidence=LOCAL_REGISTRATION_ONLY" in it })
    }

    @Test
    fun rejectedPeerAndCapabilityProbeAreSeparateFromAcceptedIap2() {
        diagnostics.begin("1234abcd")
        val epoch = diagnostics.epoch()
        diagnostics.incoming(epoch, BluetoothDiagnostics.Service.IAP2, address, BluetoothDiagnostics.PeerMatch.OTHER)
        diagnostics.incoming(epoch, BluetoothDiagnostics.Service.CARPLAY, address, target)
        assertTrue(diagnostics.summary().contains("incomingAccepted=0 incomingRejected=1 capabilityConnections=1"))
        diagnostics.incoming(epoch, BluetoothDiagnostics.Service.IAP2, address, target)
        assertTrue(diagnostics.summary().contains("incomingAccepted=1 incomingRejected=1 capabilityConnections=1"))
    }

    @Test
    fun repeatedFailuresAreCountedButThrottledAndChangedReasonIsImmediate() {
        diagnostics.begin("1234abcd")
        val epoch = diagnostics.epoch()
        repeat(3) {
            val attempt = diagnostics.connectStarted(epoch, address, 12, target)
            diagnostics.connectFinished(attempt, error = IOException("read failed, read ret: -1"))
            now += 3_000
        }
        assertEquals(1, lines.count { "event=CONNECT_END" in it })
        val changed = diagnostics.connectStarted(epoch, address, 12, target)
        diagnostics.connectFinished(changed, creatingSocket = true, error = SecurityException("private details"))
        assertEquals(2, lines.count { "event=CONNECT_END" in it })
        assertTrue(diagnostics.summary().contains("outboundAttempts=4 outboundSuccess=0 outboundFailures=4"))
        assertTrue(lines.last().contains("phase=CREATE_SOCKET outcome=FAILED"))
        assertTrue(lines.last().contains("reason=PERMISSION"))
    }

    @Test
    fun sdpTimeoutNotStartedAndCompletedHaveDistinctEvidence() {
        diagnostics.begin("1234abcd")
        val epoch = diagnostics.epoch()
        diagnostics.sdp(epoch, address, BluetoothDiagnostics.SdpOutcome.TIMEOUT, 2500, 8, false)
        diagnostics.sdp(epoch, address, BluetoothDiagnostics.SdpOutcome.NOT_STARTED, 1, 8, false)
        diagnostics.sdp(epoch, address, BluetoothDiagnostics.SdpOutcome.COMPLETE, 5, 9, true)
        assertTrue(diagnostics.summary().contains("sdpQueries=3 sdpCompleted=1 sdpTimeouts=1 sdpErrors=1"))
        assertTrue(lines.any { "outcome=TIMEOUT" in it && "cachedServices=8 cachedIap2=false" in it })
        assertFalse(lines.any { "unsupported" in it })
    }

    @Test
    fun bondAndAclOnlyContainRunLocalPeerNumbers() {
        diagnostics.begin("1234abcd")
        val epoch = diagnostics.epoch()
        diagnostics.bondChanged(epoch, address, target, 10, 11)
        diagnostics.bondChanged(epoch, address, target, 11, 12)
        diagnostics.acl(epoch, address, target, connected = true)
        diagnostics.acl(epoch, address, target, connected = false)
        val text = lines.joinToString("\n")
        assertFalse(text.contains(address))
        assertTrue(text.contains("peer=1 match=TARGET before=BONDING after=BONDED"))
        assertTrue(diagnostics.summary().contains("bondEvents=2 aclConnected=1 aclDisconnected=1"))
    }
}
