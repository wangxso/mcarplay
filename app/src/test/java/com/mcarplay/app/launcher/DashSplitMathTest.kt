package com.mcarplay.app.launcher

import com.mcarplay.app.render.CARD_MARGIN_PX
import com.mcarplay.app.render.DASH_GAP_PX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 仪表盘分屏几何纯函数测试：分屏比例由把手拖动调整，几何推导（tile 尺寸/把手中心/
 * 音乐卡起始）必须四方一致（composer 绘制、地图屏 resize、音乐卡 margin、把手布局）。
 * 历史上四方各自裸写公式导致过静默错位（CARD_MARGIN_PX 契约注释），这里把
 * [VirtualLauncher.dashSplitGeom] 的推导钉死。
 */
class DashSplitMathTest {
    private val appLen = 1000

    @Test
    fun defaultFractionCentersHandle() {
        // 0.5 恰使把手中心=应用区中点（与旧版中线分屏视觉一致）
        val g = VirtualLauncher.dashSplitGeom(appLen, 0.5f)
        assertEquals(500f, g.splitCenter, 0.001f)
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.2f, VirtualLauncher.clampDashSplitFraction(0.05f), 0.001f)
        assertEquals(0.8f, VirtualLauncher.clampDashSplitFraction(0.95f), 0.001f)
        assertEquals(0.6f, VirtualLauncher.clampDashSplitFraction(0.6f), 0.001f)
        // geom 侧同样钳制（防「卡片拉到 0」回退的另一处入口）
        assertEquals(0.2f, VirtualLauncher.dashSplitGeom(appLen, 0.05f).fraction, 0.001f)
        assertEquals(0.8f, VirtualLauncher.dashSplitGeom(appLen, 0.99f).fraction, 0.001f)
    }

    @Test
    fun tileLenMatchesUsableShare() {
        val usable = appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()
        assertEquals((0.2f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.2f).tileLen)
        assertEquals((0.8f * usable).toInt(), VirtualLauncher.dashSplitGeom(appLen, 0.8f).tileLen)
    }

    @Test
    fun geometryRelationsHold() {
        // 把手中心=tile 边缘+半把手；音乐卡起始=tile 边缘+整把手（中缝=把手宽）
        for (f in listOf(0.2f, 0.35f, 0.5f, 0.66f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            assertEquals(DASH_GAP_PX / 2f, g.splitCenter - g.tileEdge, 0.001f)
            assertEquals(DASH_GAP_PX, g.otherStart - g.tileEdge, 0.001f)
        }
    }

    @Test
    fun commitRoundTripsFraction() {
        // commit 的反推路径（把手中心流像素 → 比例）与 geom 正推一致：
        // streamPos = 主轴偏移 + 边距 + tileLen + 半把手 → fraction 复原
        val offset = 126f // 假想 dock 宽
        for (f in listOf(0.2f, 0.5f, 0.8f)) {
            val g = VirtualLauncher.dashSplitGeom(appLen, f)
            val streamPos = offset + CARD_MARGIN_PX + g.tileLen + DASH_GAP_PX / 2f
            val derived =
                (streamPos - offset - CARD_MARGIN_PX - DASH_GAP_PX / 2f) /
                    (appLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt())
            assertEquals(f, derived, 0.001f)
        }
    }
}
