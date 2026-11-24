package com.mcarplay.app.net

import com.mcarplay.app.architecture.KotlinSource
import com.mcarplay.app.architecture.SourceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWifiLinkContractTest {
    private data class WifiId(
        val id: String,
    )

    private class FakeCarWifiLink : CarWifiLink<WifiId> {
        override var currentNetwork: WifiId? = null
        var activeWifiNetwork: WifiId? = null
        var liveSsid: String? = null
        var existingWifi: Boolean = false
        var uniqueWifiNetwork: WifiId? = null
        var systemStateBySsid: Map<String, CarWifiLink.SystemWifiState> = emptyMap()
        var verifiedBySsid: Map<String, WifiId> = emptyMap()
        var nextJoinResult: CarWifiLink.JoinResult<WifiId> = CarWifiLink.JoinResult.Unavailable
        var bindCurrentResult: Boolean = false
        var bindVerifiedResult: Boolean = true
        var lastJoinTimeoutMillis: Long? = null
        var releaseCount: Int = 0

        override fun currentWifiNetwork(): WifiId? = currentNetwork ?: activeWifiNetwork

        override fun currentWifiSsid(): String? = liveSsid

        override fun hasExistingWifi(): Boolean = existingWifi

        override fun singleWifiNetwork(): WifiId? = uniqueWifiNetwork

        override fun systemActiveWifiState(ssid: String): CarWifiLink.SystemWifiState =
            systemStateBySsid[ssid] ?: CarWifiLink.SystemWifiState.NONE

        override fun verifiedWifiNetwork(ssid: String): WifiId? = verifiedBySsid[ssid]

        override fun joinCarPlayNetwork(
            ssid: String,
            passphrase: String,
            securityType: Int,
            timeoutMillis: Long,
            isCancelled: () -> Boolean,
            quickFallback: (() -> WifiId?)?,
            deepFallback: (() -> WifiId?)?,
            userChoicePending: ((Long) -> Boolean)?,
        ): CarWifiLink.JoinResult<WifiId> {
            lastJoinTimeoutMillis = timeoutMillis
            if (isCancelled()) return CarWifiLink.JoinResult.Unavailable
            userChoicePending?.invoke(1_000L)
            val result =
                when (val preset = nextJoinResult) {
                    is CarWifiLink.JoinResult.Joined -> preset
                    CarWifiLink.JoinResult.Unsupported -> preset
                    CarWifiLink.JoinResult.Unavailable -> {
                        val fallback = quickFallback?.invoke() ?: deepFallback?.invoke()
                        if (fallback != null) CarWifiLink.JoinResult.Joined(fallback) else preset
                    }
                }
            if (result is CarWifiLink.JoinResult.Joined) {
                currentNetwork = result.network
            }
            return result
        }

        override fun bindCurrentWifi(
            ssidHint: String?,
            expectedSsid: String?,
            onResult: (Boolean) -> Unit,
        ) {
            onResult(bindCurrentResult)
        }

        override fun bindVerifiedNetwork(
            network: WifiId,
            ssidHint: String?,
        ): Boolean {
            if (bindVerifiedResult) {
                currentNetwork = network
            }
            return bindVerifiedResult
        }

        override fun release() {
            releaseCount++
            currentNetwork = null
        }
    }

    @Test
    fun joinResultPreservesExactSimpleNamesForLogOutput() {
        val joined: CarWifiLink.JoinResult<WifiId> = CarWifiLink.JoinResult.Joined(WifiId("wifi-a"))
        val unavailable: CarWifiLink.JoinResult<WifiId> = CarWifiLink.JoinResult.Unavailable
        val unsupported: CarWifiLink.JoinResult<WifiId> = CarWifiLink.JoinResult.Unsupported

        assertEquals("Joined", joined.javaClass.simpleName)
        assertEquals("Unavailable", unavailable.javaClass.simpleName)
        assertEquals("Unsupported", unsupported.javaClass.simpleName)
    }

    @Test
    fun systemWifiStatePreservesFourStatesInOrder() {
        assertEquals(
            listOf("NONE", "TARGET", "OTHER", "UNKNOWN"),
            CarWifiLink.SystemWifiState.entries.map { it.name },
        )
    }

    @Test
    fun fakeCarWifiLinkExercisesGenericContractWithoutAndroidNetworkStubs() {
        val link: CarWifiLink<WifiId> =
            FakeCarWifiLink().apply {
                existingWifi = true
                liveSsid = "CarPlay-Hotspot"
                uniqueWifiNetwork = WifiId("wifi-1")
                systemStateBySsid = mapOf("CarPlay-Hotspot" to CarWifiLink.SystemWifiState.OTHER)
                verifiedBySsid = mapOf("CarPlay-Hotspot" to WifiId("wifi-1"))
            }

        assertTrue(link.hasExistingWifi())
        assertEquals("CarPlay-Hotspot", link.currentWifiSsid())
        assertEquals(WifiId("wifi-1"), link.singleWifiNetwork())
        assertEquals(WifiId("wifi-1"), link.verifiedWifiNetwork("CarPlay-Hotspot"))
        // 系统默认 active Wi-Fi 与进程绑定独立
        assertNull(link.currentNetwork)
        assertEquals(CarWifiLink.SystemWifiState.OTHER, link.systemActiveWifiState("CarPlay-Hotspot"))

        // 验证默认参数（120_000L 等）及 fallback -> Joined 绑定
        val joined =
            link.joinCarPlayNetwork(
                ssid = "CarPlay-Hotspot",
                passphrase = "secret",
                securityType = 2,
                quickFallback = { WifiId("wifi-quick") },
            )
        assertEquals(CarWifiLink.JoinResult.Joined(WifiId("wifi-quick")), joined)
        assertEquals(WifiId("wifi-quick"), link.currentNetwork)

        assertTrue(link.bindVerifiedNetwork(WifiId("wifi-verified"), "CarPlay-Hotspot"))
        assertEquals(WifiId("wifi-verified"), link.currentNetwork)

        var currentBound = false
        link.bindCurrentWifi(ssidHint = "CarPlay-Hotspot") { currentBound = it }
        assertFalse(currentBound)

        link.release()
        assertNull(link.currentNetwork)
    }

    @Test
    fun sourceWiringPinsWifiCarPlayConnectorToCarWifiLinkAndKeepsNonJoinHelpersOut() {
        val linkCode = SourceTree.scan("net/CarWifiLink.kt").code
        val connectorCode = SourceTree.scan("net/WifiCarPlayConnector.kt").code
        val controllerCode = SourceTree.scan("SessionController.kt").code

        assertTrue(
            "WifiCarPlayConnector 必须实现 CarWifiLink<Network>",
            Regex("""class\s+WifiCarPlayConnector\b[^{]*:\s*CarWifiLink<Network>""").containsMatchIn(connectorCode),
        )
        assertFalse(
            "WifiCarPlayConnector 不得重复定义内嵌 JoinResult",
            Regex("""sealed\s+interface\s+JoinResult\b""").containsMatchIn(connectorCode),
        )
        assertFalse(
            "WifiCarPlayConnector 不得重复定义内嵌 SystemWifiState",
            Regex("""enum\s+class\s+SystemWifiState\b""").containsMatchIn(connectorCode),
        )
        assertFalse(
            "SessionController 不得残留 WifiCarPlayConnector.JoinResult 引用",
            controllerCode.contains("WifiCarPlayConnector.JoinResult"),
        )
        assertFalse(
            "SessionController 不得残留 WifiCarPlayConnector.SystemWifiState 引用",
            controllerCode.contains("WifiCarPlayConnector.SystemWifiState"),
        )
        assertTrue(
            "SessionController.bindExistingCarWifi 应面向 CarWifiLink<Network>",
            Regex("""fun\s+bindExistingCarWifi\(\s*connector:\s*CarWifiLink<Network>""").containsMatchIn(controllerCode),
        )

        // 非入网辅助留在 WifiCarPlayConnector，不扩大 CarWifiLink
        val nonJoinHelpers =
            listOf(
                "gatewayCandidates",
                "gatewayAddress",
                "localIpv4Address",
                "localIpv6Addresses",
                "wifiNetworkWithLocalAddress",
                "isConnectedTo",
                "onNetworkLost",
            )
        nonJoinHelpers.forEach { helper ->
            assertFalse(
                "CarWifiLink 不应混入非入网辅助 $helper",
                Regex("""\b${Regex.escape(helper)}\b""").containsMatchIn(linkCode),
            )
            assertTrue(
                "WifiCarPlayConnector 必须保留 $helper",
                Regex("""\b${Regex.escape(helper)}\b""").containsMatchIn(connectorCode),
            )
        }

        val vehicleWords =
            setOf(
                "passat",
                "audi",
                "honda",
                "cadillac",
                "diplay",
                "winplay",
                "macplay",
                "chery",
                "arrizo",
                "mib3",
                "id7",
                "vw",
                "cinemo",
            )
        val hits = KotlinSource.identifierWords(linkCode) intersect vehicleWords
        assertTrue("CarWifiLink 标识符不得含车型词：$hits", hits.isEmpty())
    }
}
