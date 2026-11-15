package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun singleWifiWithMatchingShellSsidProvesTheHandleWithoutLocation() {
        // Android 13+ 没有定位权限时 verifiedWifiNetwork 恒为 null；
        // 若 Shizuku 读到的站端 SSID 就是目标，系统又只有这一张 Wi-Fi，唯一性成立。
        assertEquals(
            car,
            VerifiedWifiSelection.choose(
                ssid = "CarAP",
                locationVerified = null,
                singleWifi = car,
                liveSsid = "\"CarAP\"",
            ),
        )
    }

    @Test
    fun singleWifiWithAnotherSsidDoesNotProveTheHandle() {
        assertNull(
            VerifiedWifiSelection.choose(
                ssid = "CarAP",
                locationVerified = null,
                singleWifi = home,
                liveSsid = "HomeAP",
            ),
        )
        assertNull(
            VerifiedWifiSelection.choose(
                ssid = "CarAP",
                locationVerified = null,
                singleWifi = car,
                liveSsid = null,
            ),
        )
    }

    @Test
    fun ambiguousWifiListNeverPicksAHandleWithoutLocation() {
        // 两张 Wi-Fi 并存（手机热点/P2P）：没有定位核验就不能猜，交给用户确认。
        assertNull(
            VerifiedWifiSelection.choose(
                ssid = "CarAP",
                locationVerified = null,
                singleWifi = null,
                liveSsid = "CarAP",
            ),
        )
    }

    @Test
    fun locationVerifiedHandleWinsWithoutShellSsid() {
        assertEquals(
            car,
            VerifiedWifiSelection.choose(
                ssid = "CarAP",
                locationVerified = car,
                singleWifi = null,
                liveSsid = null,
            ),
        )
    }

    @Test
    fun theHandleIsOnlyTrustedWhenTwoSamplesAgree() {
        // P1：句柄与 SSID 是两次系统查询，必须连续两次采样都成立且句柄相同。
        assertEquals(
            car,
            VerifiedWifiSelection.confirmed(
                ssid = "CarAP",
                first = VerifiedWifiSelection.Sample(car, "\"CarAP\""),
                second = VerifiedWifiSelection.Sample(car, "CarAP"),
            ),
        )
    }

    @Test
    fun aNetworkSwitchBetweenTheTwoSamplesIsRejected() {
        // 读 SSID 之前是 A、之后已切到 B：不能把 A 的句柄贴成 B。
        assertNull(
            VerifiedWifiSelection.confirmed(
                ssid = "CarAP",
                first = VerifiedWifiSelection.Sample(car, "CarAP"),
                second = VerifiedWifiSelection.Sample(home, "HomeAP"),
            ),
        )
        // 句柄变了但 SSID 仍可读成目标（两张同名/占位）：同样不采信。
        assertNull(
            VerifiedWifiSelection.confirmed(
                ssid = "CarAP",
                first = VerifiedWifiSelection.Sample(car, "CarAP"),
                second = VerifiedWifiSelection.Sample(home, "CarAP"),
            ),
        )
    }

    @Test
    fun aSecondWifiAppearingBetweenSamplesIsRejected() {
        // 第二次采样时已不是唯一 Wi-Fi（singleWifiNetwork 返回 null）。
        assertNull(
            VerifiedWifiSelection.confirmed(
                ssid = "CarAP",
                first = VerifiedWifiSelection.Sample(car, "CarAP"),
                second = VerifiedWifiSelection.Sample(null, "CarAP"),
            ),
        )
    }

    @Test
    fun unreadableOrForeignSsidFailsTheProof() {
        assertNull(
            VerifiedWifiSelection.confirmed(
                ssid = "CarAP",
                first = VerifiedWifiSelection.Sample(car, null),
                second = VerifiedWifiSelection.Sample(car, null),
            ),
        )
        assertNull(
            VerifiedWifiSelection.confirmed(
                ssid = "CarAP",
                first = VerifiedWifiSelection.Sample(car, "HomeAP"),
                second = VerifiedWifiSelection.Sample(car, "HomeAP"),
            ),
        )
    }
}
