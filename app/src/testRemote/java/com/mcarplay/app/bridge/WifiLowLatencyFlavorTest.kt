package com.mcarplay.app.bridge

import com.mcarplay.app.BuildConfig
import com.mcarplay.app.service.WifiLowLatencyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WifiLowLatencyFlavorTest {
    @Test
    fun remoteVariantNeverControlsDeviceWideWifiMode() {
        assertEquals("remote", BuildConfig.FLAVOR)
        assertFalse(WifiLowLatencyPolicy.supportsForce(BuildConfig.FLAVOR))
    }
}
