package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiNetworkSelectionTest {
    @Test
    fun boundCarNetworkWinsOverAnyOtherActiveNetwork() {
        assertEquals(
            "wifi:car",
            WifiCarPlayConnector.chooseWifiNetworkTarget("wifi:car", "wifi:home") { it.startsWith("wifi:") },
        )
    }

    @Test
    fun activeWifiIsUsedOnlyWhenNothingIsBound() {
        assertEquals(
            "wifi:home",
            WifiCarPlayConnector.chooseWifiNetworkTarget(null, "wifi:home") { it.startsWith("wifi:") },
        )
        assertNull(WifiCarPlayConnector.chooseWifiNetworkTarget(null, "cell:5g") { it.startsWith("wifi:") })
    }

    @Test
    fun lostBoundNetworkFallsBackToActiveWifiInsteadOfWaitingForACallback() {
        // 绑定的车机网络已丢失（能力查询不到 Wi-Fi）：不能再选它白等回调。
        assertEquals(
            "wifi:home",
            WifiCarPlayConnector.chooseWifiNetworkTarget("gone:car", "wifi:home") { it.startsWith("wifi:") },
        )
        assertNull(WifiCarPlayConnector.chooseWifiNetworkTarget("gone:car", null) { it.startsWith("wifi:") })
    }
}
