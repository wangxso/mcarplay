package com.mcarplay.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStagePolicyTest {
    @Test
    fun airplayReconnectCanAdvanceDiscoveryButNotRegressSetup() {
        assertTrue(ConnectionStagePolicy.shouldEnterPairing(ConnectionStage.DISCOVERY))
        assertTrue(ConnectionStagePolicy.shouldEnterPairing(null))
        assertFalse(ConnectionStagePolicy.shouldEnterPairing(ConnectionStage.PAIRING))
        assertFalse(ConnectionStagePolicy.shouldEnterPairing(ConnectionStage.SETUP))
        assertFalse(ConnectionStagePolicy.shouldEnterPairing(ConnectionStage.CARWITH))
    }
}
