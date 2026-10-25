package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cmd wifi list-networks` 解析：只用于「询问用户是否清除」，
 * 判错会误删用户网络，因此边界必须锁死。
 */
class WifiSavedNetworksTest {
    /** 真机（荣耀 Android 17）实测输出：空格对齐、无制表符。 */
    private val realOutput =
        """
        Network Id      SSID                         Security type
        0            2-502                            wpa2-psk
        0            2-502                            wpa3-sae^
        1            李志华的iPhone                    wpa3-sae
        2            Weimob_office                    open
        2            Weimob_office                    owe 
        3            Audi_MMI_9310_5GHz               wpa2-psk
        """.trimIndent()

    @Test
    fun findsExactMatchIgnoringOtherNetworks() {
        assertEquals(listOf(3), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310_5GHz"))
    }

    @Test
    fun dedupesMultipleSecurityRowsForSameNetwork() {
        // 同一 netId 可能有 wpa2/wpa3 两行
        assertEquals(listOf(0), WifiSavedNetworks.idsOf(realOutput, "2-502"))
    }

    @Test
    fun handlesSsidWithSpacesAndNonAscii() {
        // SSID 中间的空白被重组为单个空格
        assertEquals(listOf(2), WifiSavedNetworks.idsOf(realOutput, "Weimob_office"))
        assertEquals(listOf(1), WifiSavedNetworks.idsOf(realOutput, "李志华的iPhone"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        // 不存在的 SSID 绝不能被近似匹配到（否则就是误删别人的网络）
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Redmi K40"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, ""))
    }

    @Test
    fun headerOnlyOrEmptyOutputIsSafe() {
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("Network Id SSID Security type", "X"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("", "X"))
    }

    @Test
    fun skipsMalformedRows() {
        val messy =
            """
            Network Id      SSID                         Security type
            notanumber   Foo                          wpa2-psk
            7            OnlyTwoColumns
            8            Bar                          wpa2-psk
            """.trimIndent()
        assertEquals(listOf(8), WifiSavedNetworks.idsOf(messy, "Bar"))
    }
}
