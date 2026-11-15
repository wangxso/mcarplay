package com.mcarplay.app.iap2

import com.mcarplay.app.net.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 车机画像判定（纯函数）：信号组合 → era/wifiGen + 可解释依据。 */
class ReceiverProfileTest {
    @Test
    fun unknownWhenNoSignals() {
        val p = ReceiverProfile.classify(ReceiverProfile.Signals())
        assertEquals(ReceiverProfile.Era.UNKNOWN, p.era)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertTrue(p.reasons.isNotEmpty())
    }

    @Test
    fun preHomeKitWhenBit38Clear() {
        // 奥迪 4xxx / CarPlay 210.81：features 无 bit38 → 明文 + AES-CTR 零密钥档
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) and
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT.inv()
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "210.81"),
            )
        assertEquals(ReceiverProfile.Era.PRE_HOMEKIT, p.era)
        assertTrue(p.reasons.any { it.contains("无 bit38") })
    }

    @Test
    fun modernWhenBit38Set() {
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) or
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "450.14.2"),
            )
        assertEquals(ReceiverProfile.Era.MODERN, p.era)
    }

    @Test
    fun wifiGenLegacyWhenListsDeclareOldProtocol() {
        // 奥迪形态：发送清单含 0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "Audi_MMI_3315",
                    model = "AUDI 5163",
                    manufacturer = "DELPHI",
                    sendIds = setOf(0x4301, 0xea03),
                    receiveIds = setOf(0x4e0c, 0x4e09, 0x4300, 0xea00),
                ),
            )
        // 奥迪清单稀疏（2+5 项）且未列 0x57xx——虽实际走旧协议，但清单不构成证据：
        // 稀疏清单判 UNKNOWN（保守旧协议），完整清单才判 NEW
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertEquals("Audi_MMI_3315 AUDI 5163 DELPHI", p.identityKey)
    }

    @Test
    fun wifiGenNewWhenFullListsLackOldProtocol() {
        // BMW iD9 形态：两清单完整（10+ 项）、均无 0x5702/0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.NEW, p.wifiGen)
        assertTrue(p.reasons.any { it.contains("0x4301 提供") })
    }

    @Test
    fun sparseSendListAloneStaysUnknown() {
        // 单份稀疏清单（2 项）无 0x5703：不判「新」——奥迪的反例证明稀疏清单
        // 不列 0x57xx 也可以走旧协议
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(sendIds = setOf(0x4301, 0xea03)),
            )
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
    }

    /**
     * issue #69 提议的判据（收 0x4300 + 发 0x4301 + 无 0x57xx → NEW）**不采纳**：
     * 本仓库记录的奥迪 3315 与上汽 AH4EM 在这条判据上同形，而奥迪必须继续判
     * UNKNOWN（跳过 0x5702 会让它不启动 AirPlay 服务，2026-10-22 真车定谳）。
     * 这条用例把「建议规则没有落地」固定下来；若将来要改，必须先解决两者的区分。
     */
    @Test
    fun availabilityOnlySparseListsStayUnknownForBothAudiAndSaicShapes() {
        // 上汽 AH4EM（issue #69 车主报告）：收 {0x4e09,0x4300}、发 {0xaa06,0x4301}
        val saic =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "AH4EM-ZXD END 01",
                    model = "AH4EM",
                    manufacturer = "SAIC MOTOR Overseas Intelligent Mobility Technology Co., Ltd.",
                    sendIds = setOf(0xaa06, 0x4301),
                    receiveIds = setOf(0x4e09, 0x4300),
                ),
            )
        // 本仓库记录的奥迪 3315 识别帧：可收清单含 0x4300，发送清单含 0x4301
        val audi =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "Audi_MMI_3315",
                    model = "AUDI 5163",
                    manufacturer = "DELPHI",
                    sendIds = setOf(0x4301, 0xea03),
                    receiveIds = setOf(0x4e0c, 0x4e09, 0x4300, 0xea00, 0xea01),
                ),
            )
        assertEquals("0x430x 能力不足以区分奥迪与上汽", audi.wifiGen, saic.wifiGen)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, saic.wifiGen)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, audi.wifiGen)
    }

    /** 宝马 iD9 完整清单仍是 NEW 的正例：两清单合计 ≥10 项且无 0x57xx。 */
    @Test
    fun bmwFullListsStayNew() {
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(0xaa01, 0xaa03, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4301),
                    receiveIds = setOf(0xaa00, 0xaa02, 0xaa04, 0xaa05, 0xea00, 0xea01, 0x5001, 0x5201, 0x4300, 0x4e0d),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.NEW, p.wifiGen)
    }

    /** 保时捷 MIB2+/ALPINE（issue #64/#66）：退化清单声明 0x5703 → LEGACY，走旧协议。 */
    @Test
    fun porscheDegradedListDeclaringWifiStaysLegacy() {
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "Porsche",
                    model = "MIB2+",
                    manufacturer = "ALPINE",
                    sendIds = setOf(0x5703, 0xea03),
                    receiveIds = setOf(0x5702, 0x4e09, 0x4e0b, 0x4e0d, 0x4e0e, 0x4300, 0xea00),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.LEGACY, p.wifiGen)
        assertTrue(p.reasons.any { it.contains("0x5703/0x5702") })
    }

    /** 混合清单：只要任一清单声明 0x57xx 就判 LEGACY，不看另一份是否稀疏。 */
    @Test
    fun mixedListsDeclaringOldProtocolOnOneSideStayLegacy() {
        val onlySend =
            ReceiverProfile.classify(ReceiverProfile.Signals(sendIds = setOf(0x5703), receiveIds = setOf(0x4300)))
        val onlyReceive =
            ReceiverProfile.classify(ReceiverProfile.Signals(sendIds = setOf(0x4301), receiveIds = setOf(0x5702)))
        assertEquals(ReceiverProfile.WifiGen.LEGACY, onlySend.wifiGen)
        assertEquals(ReceiverProfile.WifiGen.LEGACY, onlyReceive.wifiGen)
    }

    /** 空清单与「未提供」都不构成新协议证据：口径保持保守，仍判 UNKNOWN。 */
    @Test
    fun emptyOrMissingListsStayUnknown() {
        val empty =
            ReceiverProfile.classify(ReceiverProfile.Signals(sendIds = emptySet(), receiveIds = emptySet()))
        val missing =
            ReceiverProfile.classify(ReceiverProfile.Signals(sendIds = null, receiveIds = setOf(0x4300)))
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, empty.wifiGen)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, missing.wifiGen)
    }

    @Test
    fun describeCarriesReasons() {
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = 1L shl 38, srcvers = "450.14.2", sendIds = setOf(0x5703)),
            )
        val text = ReceiverProfile.describe(p)
        assertTrue(text.contains("era=MODERN"))
        assertTrue(text.contains("wifiGen=LEGACY"))
        assertTrue(text.contains("srcvers=450.14.2"))
    }

    // ---- 链路代际判定（linkGenerationOf）----
    // 判错方向的代价不对称：奥迪被误判 EVENT_LINK = marker 协商挂死车机 UI（要重启
    // 车机）；本田被误判 CLASSIC_MIB3 = 媒体键静默失效。三条件（delphi+audi+稀疏清单）
    // 是奥迪 3315/0628 实测签名，固件措辞一变即静默翻回事故行为——用例逐条钉住。

    private val audi3315Signals =
        ReceiverProfile.Signals(
            name = "Audi_MMI_3315",
            model = "AUDI 5163",
            manufacturer = "DELPHI",
            // 0x1D01 真实形态：发 2 项 + 收 5 项（2026-10-23 真机日志）
            sendIds = setOf(0x4301, 0xea03),
            receiveIds = setOf(0x5703, 0x4e09, 0x4e0b, 0x4e0d, 0x4e0e),
        )

    @Test
    fun delphiAudiWithSparseListsIsClassicMib3() {
        assertEquals(ReceiverProfile.LinkGeneration.CLASSIC_MIB3, ReceiverProfile.linkGenerationOf(audi3315Signals))
    }

    @Test
    fun fullListsAreNeverClassicMib3() {
        // 清单合计 ≥10 项（iD9 形态）：即便身份字段巧合命中也不判 CLASSIC
        val p =
            ReceiverProfile.linkGenerationOf(
                audi3315Signals.copy(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, p)
    }

    @Test
    fun missingManufacturerOrModelStaysEventLink() {
        // 固件少报身份参数（含 NUL 截空）：宁落兼容侧也不猜
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(manufacturer = null)),
        )
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(model = null)),
        )
    }

    @Test
    fun missingListsStaysEventLink() {
        // 0x1D01 未提供双清单：稀疏无从证明，不判 CLASSIC
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(sendIds = null)),
        )
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(receiveIds = null)),
        )
    }

    @Test
    fun nonAudiDelphiHardwareStaysEventLink() {
        // 本田 HFT（Alpine 件）与其它 DELPHI 代工的非奥迪件：任何形态都不判 CLASSIC
        val honda =
            ReceiverProfile.Signals(
                name = "HFT",
                model = "Honda HFT",
                manufacturer = "ALPINE",
                sendIds = setOf(0x4301),
                receiveIds = setOf(0x5703, 0x6800),
            )
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, ReceiverProfile.linkGenerationOf(honda))
        val delphiNonAudi = audi3315Signals.copy(model = "T-Roc 23")
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, ReceiverProfile.linkGenerationOf(delphiNonAudi))
    }
}
