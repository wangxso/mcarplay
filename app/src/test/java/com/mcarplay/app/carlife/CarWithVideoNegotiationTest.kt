package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #127：出画协商的安全门控与请求尺寸（纯决策，不依赖 Android）。 */
class CarWithVideoNegotiationTest {
    @Test
    fun validGeometryDeclaresTheKeyAndRequestsExactCarScreenSize() {
        val decision =
            CarWithVideoNegotiation.decide(
                preferVehicleResolution = true,
                displayFromInfo = true,
                displayWidth = 2560,
                displayHeight = 1200,
            )
        assertTrue("有合法 displays[0] 几何时应声明 DISABLE_RESOLUTION_OPTION=1", decision.declareDisableResolutionOption)
        assertTrue(decision.exactVehicleGeometry)
        assertEquals(2560, decision.width)
        assertEquals(1200, decision.height)
    }

    @Test
    fun normal1080pGeometryIsAlsoRequestedExactly() {
        val decision =
            CarWithVideoNegotiation.decide(
                preferVehicleResolution = true,
                displayFromInfo = true,
                displayWidth = 1920,
                displayHeight = 1080,
            )
        assertTrue(decision.declareDisableResolutionOption)
        assertEquals(1920, decision.width)
        assertEquals(1080, decision.height)
    }

    @Test
    fun geometryMissingFromInfoFallsBackWithoutDeclaringTheKey() {
        val decision =
            CarWithVideoNegotiation.decide(
                preferVehicleResolution = true,
                // 兜底的手机分辨率不是车机几何：不能拿它精确协商。
                displayFromInfo = false,
                displayWidth = 2560,
                displayHeight = 1200,
            )
        assertFalse(decision.declareDisableResolutionOption)
        assertFalse(decision.exactVehicleGeometry)
        // 回退到原有 1920 长边请求（2560x1200 → 1920x900）。
        assertEquals(1920, decision.width)
        assertEquals(900, decision.height)
    }

    @Test
    fun invalidDisplayedGeometryFallsBackSafely() {
        listOf(0 to 0, -1 to 1200, 5000 to 3000).forEach { (width, height) ->
            val decision =
                CarWithVideoNegotiation.decide(
                    preferVehicleResolution = true,
                    displayFromInfo = true,
                    displayWidth = width,
                    displayHeight = height,
                )
            assertFalse("$width x $height 不应声明 DISABLE_RESOLUTION_OPTION", decision.declareDisableResolutionOption)
            assertFalse(decision.exactVehicleGeometry)
            val expected = CarLifeProtocol.videoRequestFor(width, height)
            assertEquals("$width x $height 应回退到默认长边请求", expected, decision.width to decision.height)
        }
    }

    @Test
    fun policyOffKeepsTheLegacy1920RequestEvenWithValidGeometry() {
        val decision =
            CarWithVideoNegotiation.decide(
                preferVehicleResolution = false,
                displayFromInfo = true,
                displayWidth = 2560,
                displayHeight = 1200,
            )
        assertFalse(decision.declareDisableResolutionOption)
        assertFalse(decision.exactVehicleGeometry)
        assertEquals(1920, decision.width)
        assertEquals(900, decision.height)
    }

    @Test
    fun exactPathIgnoresTheRaisedPanelCapButFallbackUsesIt() {
        // 无有效几何时，回退请求仍遵循调用方放开的长边上限（实验面板路径）。
        val fallback =
            CarWithVideoNegotiation.decide(
                preferVehicleResolution = false,
                displayFromInfo = true,
                displayWidth = 2288,
                displayHeight = 1728,
                maxLongSide = 2288,
            )
        assertEquals(2288 to 1728, fallback.width to fallback.height)
        // 精确路径本身就是车机几何，不受该上限影响。
        val exact =
            CarWithVideoNegotiation.decide(
                preferVehicleResolution = true,
                displayFromInfo = true,
                displayWidth = 2560,
                displayHeight = 1200,
                maxLongSide = 1920,
            )
        assertEquals(2560 to 1200, exact.width to exact.height)
    }
}
