package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class WifiMacResolverTest {
    /** 车机热点上看到的是随机化地址（本地管理位置位），必须照收。 */
    @Test
    fun acceptsRandomizedAddressFromSysfsText() {
        assertEquals("c2:11:22:33:44:55", WifiMacResolver.parseMac("C2:11:22:33:44:55\n"))
    }

    @Test
    fun rejectsPlaceholderZeroAndMulticast() {
        assertNull(WifiMacResolver.parseMac("02:00:00:00:00:00"))
        assertNull(WifiMacResolver.parseMac("00:00:00:00:00:00"))
        assertNull(WifiMacResolver.parseMac("01:00:5e:00:00:fb"))
        assertNull(WifiMacResolver.parseMac("Shizuku 未连接"))
        assertNull(WifiMacResolver.parseMac(null))
        assertNull(WifiMacResolver.formatMac(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun derivesMacFromEui64LinkLocal() {
        assertEquals("c2:11:22:33:44:55", WifiMacResolver.macFromEui64(InetAddress.getByName("fe80::c011:22ff:fe33:4455")))
    }

    /** RFC 7217 / 随机接口标识的链路本地地址没有 ff:fe，不能硬推。 */
    @Test
    fun ignoresNonEui64AndNonLinkLocal() {
        assertNull(WifiMacResolver.macFromEui64(InetAddress.getByName("fe80::1c3a:9d42:77e1:5b20")))
        assertNull(WifiMacResolver.macFromEui64(InetAddress.getByName("2001:db8::c011:22ff:fe33:4455")))
        assertNull(WifiMacResolver.macFromEui64(InetAddress.getByName("192.168.33.25")))
    }
}
