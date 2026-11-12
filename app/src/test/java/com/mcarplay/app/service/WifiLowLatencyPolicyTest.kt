package com.mcarplay.app.service

import com.mcarplay.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLowLatencyPolicyTest {
    @Test
    fun onlyStandardVariantMayControlDeviceWideMode() {
        assertTrue(WifiLowLatencyPolicy.supportsForce("standard"))
        assertFalse(WifiLowLatencyPolicy.supportsForce("remote"))
        assertFalse(WifiLowLatencyPolicy.supportsForce("other"))
        assertTrue(WifiLowLatencyPolicy.supportsForce(BuildConfig.FLAVOR))
    }

    @Test
    fun zeroExitCodeDoesNotMaskAospFailureOutput() {
        assertTrue(WifiLowLatencyPolicy.commandSucceeded(0, ""))
        assertFalse(WifiLowLatencyPolicy.commandSucceeded(1, ""))
        assertFalse(WifiLowLatencyPolicy.commandSucceeded(0, "Command execution failed"))
        assertFalse(WifiLowLatencyPolicy.commandSucceeded(0, "command execution failed\n"))
    }
}
