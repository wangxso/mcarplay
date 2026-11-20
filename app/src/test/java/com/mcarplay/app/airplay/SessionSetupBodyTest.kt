package com.mcarplay.app.airplay

import com.mcarplay.app.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 艾瑞泽 8：真 iPhone 在 macAddress 填自己的 Wi-Fi 地址，车机按它匹配热点终端（2026-11-02）。 */
    @Test
    fun macAddressDefaultsToPlaceholderAndCarriesRealWifiAddress() {
        val placeholder = BinaryPlist.parse(client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449)) as Map<*, *>
        assertEquals("aa:bb:cc:dd:ee:ff", placeholder["macAddress"])
        val real =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449, macAddress = "c2:11:22:33:44:55"),
            ) as Map<*, *>
        assertEquals("c2:11:22:33:44:55", real["macAddress"])
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

    @Test
    fun mainBufferedOfferCanBeWithdrawnForReceiversWithoutBufferedAudio() {
        val defaultFeatures =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 35449),
            ) as Map<*, *>
        assertTrue((defaultFeatures["features"] as List<*>).contains("mainBuffered"))

        val withoutBuffered =
            BinaryPlist.parse(
                client().buildSessionSetupBody(
                    "10:C1:97:1A:C7:44",
                    35449,
                    includeMainBuffered = false,
                ),
            ) as Map<*, *>
        val features = withoutBuffered["features"] as List<*>
        assertFalse(features.contains("mainBuffered"))
        assertTrue("其余能力不得被误删", features.contains("viewAreas"))
        assertTrue("默认仍保留 HEVC；MB_E 手动预设单独撤回", features.contains("hevc"))
    }

    @Test
    fun hevcOfferCanBeWithdrawnForH264OnlyReceivers() {
        val withoutHevc =
            BinaryPlist.parse(
                client().buildSessionSetupBody(
                    "10:C1:97:1A:C7:44",
                    35449,
                    includeHevc = false,
                ),
            ) as Map<*, *>
        val features = withoutHevc["features"] as List<*>
        assertFalse(features.contains("hevc"))
        assertTrue("H.264 能力必须保留", features.contains("h.264Level5.1"))
        assertTrue("其它通用能力不得被误删", features.contains("viewAreas"))
        assertTrue("默认仍提议 mainBuffered", features.contains("mainBuffered"))

        val h264OnlyWithoutBuffered =
            BinaryPlist.parse(
                client().buildSessionSetupBody(
                    "10:C1:97:1A:C7:44",
                    35449,
                    includeMainBuffered = false,
                    includeHevc = false,
                ),
            ) as Map<*, *>
        val h264OnlyFeatures = h264OnlyWithoutBuffered["features"] as List<*>
        assertFalse(h264OnlyFeatures.contains("hevc"))
        assertFalse(h264OnlyFeatures.contains("mainBuffered"))
        assertTrue(h264OnlyFeatures.contains("h.264Level5.1"))
        assertTrue(h264OnlyFeatures.contains("viewAreas"))
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
