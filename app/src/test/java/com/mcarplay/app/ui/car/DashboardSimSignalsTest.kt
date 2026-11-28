package com.mcarplay.app.ui.car

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardSimSignalsTest {
    @Test
    fun distinguishesLteAndNonStandalone5G() {
        assertEquals("4G", networkGeneration(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("5G", networkGeneration(TelephonyManager.NETWORK_TYPE_LTE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
        assertEquals("5G", networkGeneration(TelephonyManager.NETWORK_TYPE_NR))
    }

    @Test
    fun doesNotInventGenerationWhenUnknown() {
        assertNull(networkGeneration(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        assertNull(networkGeneration(TelephonyManager.NETWORK_TYPE_IWLAN))
    }
}
