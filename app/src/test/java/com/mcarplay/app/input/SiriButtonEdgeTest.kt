package com.mcarplay.app.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiriButtonEdgeTest {
    @Test
    fun releaseRearmsImmediately() {
        val edge = SiriButtonEdge()
        assertFalse(edge.onAction(1L, 10))
        assertFalse(edge.onAction(null, 20))
        assertTrue(edge.onAction(2L, 130))
        assertFalse(edge.onAction(3L, 257))
        assertTrue(edge.onAction(2L, 500))
    }

    @Test
    fun headUnitLongPressRepeatsAndRearmsAfterSilence() {
        val edge = SiriButtonEdge()
        assertTrue(edge.onAction(2L, 0))
        for (repeatAt in listOf(1_515L, 1_733L, 1_922L, 2_169L, 2_330L)) {
            assertFalse(edge.onAction(2L, repeatAt))
        }
        assertTrue(edge.onAction(2L, 4_330))
    }
}
