package com.mcarplay.app.carlife

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
        assertEquals(640 to 360, mapCarLifeTouch(960, 540, 1920, 1080, 1280, 720))
        assertEquals(1279 to 719, mapCarLifeTouch(9999, 9999, 1920, 1080, 1280, 720))
        assertNull(mapCarLifeTouch(0, 0, 0, 1080, 1280, 720))
    }
}
