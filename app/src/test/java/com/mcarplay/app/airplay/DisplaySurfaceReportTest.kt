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
        assertTrue(text, text.contains(displayUuid))
        assertTrue("高度不同必须明确说出来", text.contains("高度不同"))
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

    @Test
    fun sixteenByteUuidIsFormattedLikeUuidText() {
        val raw = ByteArray(16) { it.toByte() }
        val surfaces = DisplaySurfaceReport.videoSurfaceGeometry(listOf(panel + ("initialVideoStreams" to listOf(mapOf("uuid" to raw)))))
        assertEquals("00010203-0405-0607-0809-0a0b0c0d0e0f", surfaces.single().uuid)
    }

    @Test
    fun intPixelsFromTestsAreAcceptedLikeLongsFromBplist() {
        val asInts = mapOf("widthPixels" to 2288, "heightPixels" to 1728)
        assertEquals("2288x1728", DisplaySurfaceReport.videoSurfaceGeometry(listOf(asInts)).single().text)
    }
}
