package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiBindingTest {
    private data class NetId(
        val name: String,
    )

    private val car = NetId("wifi:car")
    private val home = NetId("wifi:home")

    @Test
    fun verifiedHandleWinsWhenAnotherWifiIsAlreadyTheActiveNetwork() {
        // 两张 Wi-Fi 并存：核验到的是车机热点，但系统 activeNetwork/绑定槽位可能是家里的。
        val decision = WifiBindPlan.decide("CarAP", boundSsid = null, verifiedNetwork = car, currentNetwork = home)
        assertEquals(WifiBindPlan.Decision.BindVerified(car), decision)
        assertNotEquals(WifiBindPlan.Decision.BindVerified(home), decision)
    }

    @Test
    fun recordedCarSsidDoesNotLetAnotherCurrentNetworkWin() {
        // 已把车机 SSID 记为 bound，但进程当前绑在另一张 Wi-Fi：必须回到核验句柄。
        val decision = WifiBindPlan.decide("CarAP", boundSsid = "CarAP", verifiedNetwork = car, currentNetwork = home)
        assertEquals(WifiBindPlan.Decision.BindVerified(car), decision)
    }

    @Test
    fun missingVerifiedHandleNeverFallsBackToAWildcardBind() {
        // 没有核验句柄时只能是用户确认：不能让并发回调把任意 Wi-Fi 交给进程。
        val decision = WifiBindPlan.decide("CarAP", boundSsid = null, verifiedNetwork = null, currentNetwork = home)
        assertEquals(WifiBindPlan.Decision.NeedsConfirmation, decision)
    }

    @Test
    fun alreadyBoundNetworkIsReusedOnlyWhenNoConflictingHandleWasVerified() {
        assertEquals(
            WifiBindPlan.Decision.AlreadyBound,
            WifiBindPlan.decide("CarAP", boundSsid = "CarAP", verifiedNetwork = null, currentNetwork = car),
        )
    }

    @Test
    fun requiredCurrentNetworkMismatchRejectsTheBind() {
        assertEquals(
            WifiBindPlan.Decision.Rejected,
            WifiBindPlan.decide(
                "CarAP",
                boundSsid = null,
                verifiedNetwork = car,
                currentNetwork = home,
                requiredCurrent = car,
            ),
        )
    }

    @Test
    fun confirmationTokenRejectsWifiSwitchedWhileDialogWasOpen() {
        // 弹窗展示的是 A；用户切到 B 后点“使用当前网络”，绝不能把 A 的 SSID 绑到 B。
        val shown = WifiNetworkToken(car, "CarAP")
        assertTrue(shown.matches(car, "CarAP"))
        assertFalse(shown.matches(home, "HomeAP"))
        assertFalse(shown.matches(home, "CarAP"))
    }

    @Test
    fun confirmationTokenFallsBackToReadableSsidWhenHandleIsUnavailable() {
        val shown = WifiNetworkToken<NetId>(network = null, ssid = "CarAP")
        assertTrue(shown.matches(NetId("wifi:any"), "\"CarAP\""))
        assertFalse(shown.matches(NetId("wifi:any"), "HomeAP"))
        assertFalse(shown.matches(NetId("wifi:any"), null))
    }

    @Test
    fun confirmationTokenWithoutIdentityNeverMatches() {
        val empty = WifiNetworkToken<NetId>(network = null, ssid = null)
        assertFalse(empty.hasIdentity)
        assertFalse(empty.matches(null, null))
        assertFalse(empty.matches(car, "CarAP"))
    }
}
