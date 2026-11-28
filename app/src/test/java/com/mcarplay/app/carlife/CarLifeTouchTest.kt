package com.mcarplay.app.carlife

import com.mcarplay.app.input.TouchReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLifeTouchTest {
    @Test
    fun touchStateOnlyEmitsOrderedGesture() {
        val state = CarLifeTouchState()
        assertNull(state.onContact(10, 20, down = false, width = 100, height = 100))
        assertEquals(CarLifeTouchAction.DOWN, state.onContact(10, 20, down = true, width = 100, height = 100))
        assertEquals(CarLifeTouchAction.MOVE, state.onContact(20, 30, down = true, width = 100, height = 100))
        assertEquals(CarLifeTouchAction.UP, state.onContact(30, 40, down = false, width = 100, height = 100))
        assertNull(state.onContact(30, 40, down = false, width = 100, height = 100))
    }

    @Test
    fun releaseReturnsLastPointOnlyForActiveGesture() {
        val state = CarLifeTouchState()
        state.onContact(5, 6, down = true, width = 100, height = 100)
        state.onContact(7, 8, down = true, width = 100, height = 100)
        assertEquals(7 to 8, state.releasePoint())
        assertNull(state.releasePoint())
    }

    @Test
    fun touchCoordinatesOutsideVideoAreRejected() {
        val state = CarLifeTouchState()
        assertNull(state.onContact(100, 0, down = true, width = 100, height = 100))
        assertNull(state.onContact(0, -1, down = true, width = 100, height = 100))
    }

    @Test
    fun touchMappingScalesAndClampsToVideo() {
        val range = TouchReports.CoordinateRange(0, 1919, 0, 1079)
        assertEquals(639 to 359, mapCarLifeTouch(959, 539, range, 1280, 720))
        assertEquals(1279 to 719, mapCarLifeTouch(9999, 9999, range, 1280, 720))
        assertNull(mapCarLifeTouch(0, 0, TouchReports.CoordinateRange(0, 0, 0, 1079), 1280, 720))
    }

    @Test
    fun hidRangeIndependentFromAdvertisedDisplayFixesTouchOffset() {
        // 本车机 /info displays[0]=1280×720，但触摸描述符按物理面板 1920×720 报坐标。
        // 旧实现用 1280 当分母，1920 附近的点会被压到右边缘；用 HID 范围后应铺满目标。
        val physical = TouchReports.CoordinateRange(0, 1919, 0, 719)
        assertEquals(0 to 0, mapCarLifeTouch(0, 0, physical, 1280, 720))
        assertEquals(640 to 360, mapCarLifeTouch(960, 360, physical, 1280, 720))
        assertEquals(1279 to 719, mapCarLifeTouch(1919, 719, physical, 1280, 720))

        // CarWith 调到 1920×720 时，触摸与视频使用同一物理范围，应接近恒等映射。
        assertEquals(960 to 360, mapCarLifeTouch(960, 360, physical, 1920, 720))
        assertEquals(1919 to 719, mapCarLifeTouch(1919, 719, physical, 1920, 720))
    }
}
