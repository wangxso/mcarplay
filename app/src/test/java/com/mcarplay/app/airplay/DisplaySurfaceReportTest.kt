package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #62 奔驰 MB_E-P-4：`/info` 里 `displays[0]` 与 `displayPanels[].initialVideoStreams` 几何不一致。 */
class DisplaySurfaceReportTest {
    private val displayUuid = "6d1c2f9a-0b3e-4a55-9d8c-1f2e3a4b5c6d"
    private val display =
        mapOf(
            "widthPixels" to 2288L,
            "heightPixels" to 1278L,
            "primaryInputDevice" to 2L,
            "type" to 110L,
            "uuid" to displayUuid,
        )
    private val panel =
        mapOf(
            "widthPixels" to 2288L,
            "heightPixels" to 1728L,
            "initialVideoStreams" to listOf(mapOf("zIndex" to 1L, "uuid" to displayUuid)),
        )

    @Test
    fun reportsHeightMismatchBetweenDisplayAndVideoPanel() {
        val text = DisplaySurfaceReport.describe(display, listOf(panel))
        assertTrue(text, text.contains("displays[0]=2288x1278"))
        assertTrue(text, text.contains("2288x1728"))
        assertTrue("同一 UUID 只写「同 displays[0]」", text.contains("uuid=同 displays[0]"))
        assertTrue("高度不同必须明确说出来", text.contains("高度不同"))
    }

    @Test
    fun rawReceiverUuidNeverReachesTheLog() {
        // P2 review finding: a canonical hyphenated UUID bypasses DiagnosticRedactor's longHex,
        // so the report line must not carry the receiver-provided identifier at all.
        val text = DisplaySurfaceReport.describe(display, listOf(panel))
        assertFalse("原始 UUID 不得出现：$text", text.contains(displayUuid))
        assertTrue(text, !text.contains("6d1c2f9a"))
        // 仍然能比较表面：不同 UUID 给报告内别名，同一 UUID 写「同 displays[0]」
        val other = panel + ("initialVideoStreams" to listOf(mapOf("uuid" to "11111111-2222-3333-4444-555555555555")))
        val distinct = DisplaySurfaceReport.describe(display, listOf(other))
        assertTrue(distinct, distinct.contains("<DISPLAY-2>"))
        assertFalse(distinct, distinct.contains("11111111"))
    }

    @Test
    fun matchingHeightsDoNotClaimAMismatch() {
        val text = DisplaySurfaceReport.describe(display, listOf(panel + ("heightPixels" to 1278L)))
        assertFalse(text, text.contains("高度不同"))
        assertTrue(text.contains("2288x1278"))
    }

    @Test
    fun panelsWithoutInitialVideoStreamsAreStillListed() {
        // 面板没声明 initialVideoStreams 也照样报几何（车机可能只用默认表面），但与 displays 的高度差仍要写明。
        val text = DisplaySurfaceReport.describe(display, listOf(panel - "initialVideoStreams"))
        assertTrue(text, text.contains("displayPanels[0] uuid=缺 2288x1728"))
        assertTrue(text, text.contains("高度不同"))
    }

    @Test
    fun panelsWithoutVideoSurfacesAtAllSaySo() {
        val noGeometry = listOf(mapOf<String, Any?>("initialVideoStreams" to emptyList<Any>()))
        assertTrue(DisplaySurfaceReport.describe(display, noGeometry).contains("未声明可用的初始视频表面"))
    }

    @Test
    fun missingGeometrySaysSoInsteadOfPrintingNothing() {
        assertEquals("无显示屏几何（/info 未声明 displays/displayPanels）", DisplaySurfaceReport.describe(null, null))
        assertTrue(DisplaySurfaceReport.describe(display, null).startsWith("displays[0]=2288x1278"))
    }

    // ---- issue #62 实验开关：按 displayPanels 的视频表面取几何（纯函数，不涉及开关本身）----

    @Test
    fun panelGeometryTakesTheMatchingVideoSurface() {
        val geometry = DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel))
        assertEquals("2288x1728", geometry?.text)
    }

    @Test
    fun panelGeometryRefusesAnotherDisplaySurface() {
        // initialVideoStreams 指向别的屏幕：不能拿它的几何当本屏协商。
        val other = panel + ("initialVideoStreams" to listOf(mapOf("uuid" to "11111111-2222-3333-4444-555555555555")))
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(other)))
    }

    @Test
    fun panelGeometryIgnoresPanelsWithoutVideoStreams() {
        // 没声明 initialVideoStreams 的面板不是车机要画布的对象，即便高度不同也不能用。
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel - "initialVideoStreams")))
    }

    @Test
    fun panelGeometryFallsBackToPanelUuidWhenStreamsCarryNone() {
        // 流里没有 uuid 时，用面板自身的 uuid 判同一表面。
        val streamWithoutUuid = panel + ("initialVideoStreams" to listOf(mapOf("zIndex" to 1L)))
        assertEquals(
            "2288x1728",
            DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(streamWithoutUuid + ("uuid" to displayUuid)))?.text,
        )
        // 面板自身也没有 uuid → 无法证明是同一表面，必须放弃。
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(streamWithoutUuid)))
    }

    @Test
    fun panelGeometryNeedsBothWidthAndHeight() {
        val half = panel + ("heightPixels" to null)
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(half)))
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(null, listOf(panel)))
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, null))
    }

    @Test
    fun panelGeometrySkipsNonVideoPanelsBeforeTheMatchingOne() {
        val audioOnly = mapOf<String, Any?>("widthPixels" to 400L, "heightPixels" to 400L)
        assertEquals("2288x1728", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(audioOnly, panel))?.text)
    }

    // ---- PR #78 评审 P2：面板尺寸必须先校验再转换 ----

    @Test
    fun panelGeometryRejectsNonPositiveDimensions() {
        listOf(0L, -1L, -2288L).forEach { bad ->
            assertEquals(
                "widthPixels=$bad 必须拒绝",
                null,
                DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to bad))),
            )
            assertEquals(
                "heightPixels=$bad 必须拒绝",
                null,
                DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("heightPixels" to bad))),
            )
        }
    }

    /** PR #78 复审 P2：下限 2，且必须整数值——`1.9.toLong()` 不能悄悄变成 1。 */
    @Test
    fun panelGeometryRejectsTooSmallAndFractionalDimensions() {
        // 1 对齐后仍不足一个宏块。
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to 1))))
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("heightPixels" to 1L))))
        // 2 是可表示的最小偶数画布。
        assertEquals("2x1728", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to 2)))?.text)
        // 非整数值不能被截断成整数后接受。
        listOf(1.9, 2288.5, 3.01, 0.9).forEach { bad ->
            assertEquals(
                "Double $bad 必须拒绝（不能截断）",
                null,
                DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to bad))),
            )
        }
        listOf(1.9f, 2288.5f).forEach { bad ->
            assertEquals(
                "Float $bad 必须拒绝（不能截断）",
                null,
                DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("heightPixels" to bad))),
            )
        }
        // 整数值的浮点仍接受（bplist 可能把整数给成 Double）。
        assertEquals("2288x1728", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to 2288.0)))?.text)
        assertEquals("2288x1728", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("heightPixels" to 1728f)))?.text)
    }

    @Test
    fun panelGeometryRejectsOverflowingDimensionsInsteadOfTruncating() {
        // Long.MAX_VALUE.toInt() == -1：不校验就会被当成负数画布。
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to Long.MAX_VALUE))))
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("heightPixels" to Long.MAX_VALUE))))
        // 超出单边上限（4096）也拒绝。
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to 100_000L))))
        // 刚好在上限内、且总量在预算内的尺寸仍接受。
        assertEquals("4096x1728", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to 4_096L)))?.text)
    }

    /**
     * PR #78 Codex 复审 P2：双维各自「合法」不代表组合可用。
     *
     * 这些值来自车机自报，畸形报告不应让本机替一个 16K 画布分配编码器；「显示」缩放最高
     * 160%，16384 会进一步放大到 26214。上限按实际资源收紧，超限一律回退 displays[0]。
     */
    @Test
    fun panelGeometryRejectsGeometriesBeyondResourceLimits() {
        // 旧上限 16384 现在必须拒绝（单边超 4096）。
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to 16_384L))))
        // 单边都合法、总量超预算（4096×4096 = 1677 万像素）也必须拒绝。
        val square =
            panel + ("widthPixels" to 4_096L) + ("heightPixels" to 4_096L)
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(square)))
        // 总量刚好在预算内（4096×2024 < 8847360）仍接受。
        val budget =
            panel + ("widthPixels" to 4_096L) + ("heightPixels" to 2_024L)
        assertEquals("4096x2024", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(budget))?.text)
        // 实验目标 2288×1728 不受任何影响。
        assertEquals("2288x1728", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel))?.text)
    }

    /**
     * PR #78 Codex 复审第二轮 P2：预算必须落在**应用「显示」缩放后**的画布上。
     *
     * `SessionController` 用 `setup.displayWidth * scalePct/100`（最高 160%）生成编码画布，
     * 面板本身合法不代表缩放后合法。`fitWithinCanvasLimits` 在知道缩放值的那一层收敛。
     */
    @Test
    fun scaledCanvasIsFittedIntoResourceLimits() {
        // 已在预算内：原样返回（含实验预期尺寸按 100% 使用时）。
        assertEquals(2288 to 1728, DisplaySurfaceReport.fitWithinCanvasLimits(2288, 1728))
        assertEquals(1920 to 1080, DisplaySurfaceReport.fitWithinCanvasLimits(1920, 1080))
        // 实验 2288×1728 @160% = 3661×2765 ≈ 10.1MP：收敛到预算内且仍为偶数。
        val fitted = DisplaySurfaceReport.fitWithinCanvasLimits(3661, 2765)
        assertTrue(
            "收敛后 ${fitted.first}x${fitted.second} 应回到预算内",
            fitted.first.toLong() * fitted.second.toLong() <= 8_294_400L,
        )
        assertEquals("宽必须偶数", 0, fitted.first % 2)
        assertEquals("高必须偶数", 0, fitted.second % 2)
        // 远大于预算：3840×2160 @160% = 6144×3456 ≈ 21.2MP。
        val big = DisplaySurfaceReport.fitWithinCanvasLimits(6144, 3456)
        assertTrue(big.first.toLong() * big.second.toLong() <= 8_294_400L)
        assertTrue("不得超出缩放后单边上限", maxOf(big.first, big.second) <= 6_144)
        // 单边超上限但总量不大：同样收敛。
        val tall = DisplaySurfaceReport.fitWithinCanvasLimits(8_000, 100)
        assertTrue("单边必须收敛", maxOf(tall.first, tall.second) <= 6_144)
        // 退化输入不受影响（不产生 0 或负数）。
        assertEquals(0 to 0, DisplaySurfaceReport.fitWithinCanvasLimits(0, 0))
    }

    @Test
    fun panelGeometryRejectsNonFiniteAndNonNumericDimensions() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { bad ->
            assertEquals(
                "非有限值 $bad 必须拒绝",
                null,
                DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to bad))),
            )
        }
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel + ("widthPixels" to "2288"))))
        assertEquals(null, DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(panel - "widthPixels")))
    }

    @Test
    fun panelGeometryAcceptsIntBackedValues() {
        // 个别接收端把像素给成 Int 而非 bplist 的 Long。
        val intPanel = panel + ("widthPixels" to 2288) + ("heightPixels" to 1728)
        assertEquals("2288x1728", DisplaySurfaceReport.panelVideoGeometry(displayUuid, listOf(intPanel))?.text)
    }

    // ---- PR #78 评审 P2：实验是否真的对实际输出尺寸生效 ----

    @Test
    fun experimentIsEffectiveOnlyWhenActualOutputMatchesThePanel() {
        assertEquals(
            DisplaySurfaceReport.PanelExperimentOutcome.EFFECTIVE,
            DisplaySurfaceReport.panelExperimentOutcome(2288, 1728, 2288, 1728),
        )
        // 偶数对齐后仍算一致（VideoSender/编码器都按 2 对齐）。
        assertEquals(
            DisplaySurfaceReport.PanelExperimentOutcome.EFFECTIVE,
            DisplaySurfaceReport.panelExperimentOutcome(2289, 1729, 2288, 1728),
        )
    }

    @Test
    fun experimentWithoutATargetIsNotRequested() {
        assertEquals(
            DisplaySurfaceReport.PanelExperimentOutcome.NOT_REQUESTED,
            DisplaySurfaceReport.panelExperimentOutcome(null, null, 2288, 1728),
        )
        assertEquals(
            DisplaySurfaceReport.PanelExperimentOutcome.NOT_REQUESTED,
            DisplaySurfaceReport.panelExperimentOutcome(2288, null, 2288, 1728),
        )
    }

    @Test
    fun experimentReportsCarWithIgnoringTheRequestedSize() {
        // CarWith 返回了别的尺寸（含默认 1920 长边上限的结果）→ 实验未生效。
        listOf(1920 to 1450, 1920 to 1140, 1280 to 720).forEach { (w, h) ->
            assertEquals(
                "CarWith ${w}x$h 与面板不符时必须报告未生效",
                DisplaySurfaceReport.PanelExperimentOutcome.CARWITH_DIFFERENT,
                DisplaySurfaceReport.panelExperimentOutcome(2288, 1728, w, h),
            )
        }
        // 非法实际尺寸也不能被当成生效。
        assertEquals(
            DisplaySurfaceReport.PanelExperimentOutcome.CARWITH_DIFFERENT,
            DisplaySurfaceReport.panelExperimentOutcome(2288, 1728, 0, 0),
        )
    }

    @Test
    fun repeatedSurfaceUuidKeepsOneAlias() {
        // 同一别名必须复用，否则「两个表面是否相同」在报告里看不出来。
        val other = "11111111-2222-3333-4444-555555555555"
        val surfaces =
            listOf(
                panel + ("initialVideoStreams" to listOf(mapOf("uuid" to other))),
                panel + ("initialVideoStreams" to listOf(mapOf("uuid" to other))),
            )
        val text = DisplaySurfaceReport.describe(display, surfaces)
        assertTrue(text, text.contains("<DISPLAY-2>"))
        assertFalse("不得出现第二个别名：$text", text.contains("<DISPLAY-3>"))
        assertFalse(text, text.contains("11111111"))
    }

    @Test
    fun sixteenByteUuidIsFormattedLikeUuidText() {
        // videoSurfaceGeometry 仍返回规范 UUID（供内部比较用），但 describe 不把它写进日志。
        val raw = ByteArray(16) { it.toByte() }
        val surfaces = DisplaySurfaceReport.videoSurfaceGeometry(listOf(panel + ("initialVideoStreams" to listOf(mapOf("uuid" to raw)))))
        assertEquals("00010203-0405-0607-0809-0a0b0c0d0e0f", surfaces.single().uuid)
        val distinct = DisplaySurfaceReport.describe(display, listOf(panel + ("initialVideoStreams" to listOf(mapOf("uuid" to raw)))))
        assertFalse(distinct, distinct.contains("00010203"))
        assertTrue(distinct, distinct.contains("<DISPLAY-2>"))
    }

    @Test
    fun intPixelsFromTestsAreAcceptedLikeLongsFromBplist() {
        val asInts = mapOf("widthPixels" to 2288, "heightPixels" to 1728)
        assertEquals("2288x1728", DisplaySurfaceReport.videoSurfaceGeometry(listOf(asInts)).single().text)
    }
}
