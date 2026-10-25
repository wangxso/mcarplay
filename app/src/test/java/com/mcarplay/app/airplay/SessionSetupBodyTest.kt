package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级 SETUP 报文体的回归保护。
 *
 * WinPlay（2026-10-19 实测）按「用户选中的 iPhone 蓝牙地址」过滤会话，
 * 报文体里的 deviceID 对不上直接 403——曾用 catplay 的写死假值
 * ff:ee:dd:cc:bb:aa，表象是连上约 45 秒后自动断开（接收端等视频超时）。
 */
class SessionSetupBodyTest {
    private fun client() =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "DESKTOP-43KNTO6",
                passphrase = "secret",
                channel = 0,
                securityType = 2,
                airPlayPort = 17000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("192.168.137.1"),
            ),
            log = {},
        )

    @Test
    fun deviceIdIsCarriedVerbatimIntoSetupBody() {
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertEquals("10:C1:97:1A:C7:44", root["deviceID"])
    }

    @Test
    fun deviceIdIsNotTheCatplayPlaceholder() {
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertNotEquals("ff:ee:dd:cc:bb:aa", root["deviceID"])
    }

    @Test
    fun bodyKeepsTheFieldsReceiversNegotiateOn() {
        val port = 35449
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", port)
        val root = BinaryPlist.parse(body) as Map<*, *>
        // timingPort 是媒体时钟同步入口；features 决定 enabledFeatures（缺失即视频管线不启用）
        assertEquals(port.toLong(), root["timingPort"])
        assertTrue((root["features"] as List<*>).contains("viewAreas"))
        assertEquals(true, root["keepAliveLowPower"])
    }

    /**
     * 未配对（et=NONE）会话必须显式带 `et=1`（catplay `AirPlayEncryptionType::NONE`）。
     * pre-HomeKit 老车机只认明文会话；缺这个键时严格接收端会把它当成
     * 「声明加密但没有密钥」而拒绝。
     */
    @Test
    fun unpairedSessionDeclaresEtNone() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 35449, encrypted = false),
            ) as Map<*, *>
        assertEquals(1L, root["et"])
        assertEquals(false, root["updateSessionRequest"])
    }

    @Test
    fun pairedSessionOmitsEt() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 35449, encrypted = true),
            ) as Map<*, *>
        assertNull(root["et"])
    }
}
