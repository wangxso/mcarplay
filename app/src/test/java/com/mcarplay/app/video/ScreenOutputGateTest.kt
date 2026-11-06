package com.mcarplay.app.video

import com.mcarplay.app.video.ScreenOutputGate.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOutputGateTest {
    @Test
    fun phoneOwnedScreenPassesEveryFrame() {
        val gate = ScreenOutputGate()
        assertTrue(gate.admit(phoneOwnsScreen = true, keyFrame = true).send)
        assertTrue(gate.admit(phoneOwnsScreen = true, keyFrame = false).send)
        assertEquals("主屏归车机时累计跳过 0 帧，最近一次暂停 0 帧", gate.summary())
    }

    @Test
    fun carOwnedScreenDropsEveryFrameIncludingIdr() {
        val gate = ScreenOutputGate()
        val first = gate.admit(phoneOwnsScreen = false, keyFrame = true)
        assertFalse(first.send)
        assertEquals(Transition.PAUSED, first.transition)
        val second = gate.admit(phoneOwnsScreen = false, keyFrame = false)
        assertFalse(second.send)
        assertNull(second.transition)
        assertEquals(2L, second.skippedThisPause)
        assertEquals(2L, second.skippedTotal)
    }

    @Test
    fun returningScreenWaitsForNextIdr() {
        val gate = ScreenOutputGate()
        gate.admit(phoneOwnsScreen = false, keyFrame = false)
        val resuming = gate.admit(phoneOwnsScreen = true, keyFrame = false)
        assertFalse(resuming.send)
        assertEquals(Transition.RESUMING, resuming.transition)
        assertFalse(gate.admit(phoneOwnsScreen = true, keyFrame = false).send)
        val resumed = gate.admit(phoneOwnsScreen = true, keyFrame = true)
        assertTrue(resumed.send)
        assertEquals(Transition.RESUMED, resumed.transition)
        assertEquals(3L, resumed.skippedThisPause)
        assertTrue(gate.admit(phoneOwnsScreen = true, keyFrame = false).send)
    }

    @Test
    fun firstFrameAfterReturnCanResumeWhenItIsAnIdr() {
        val gate = ScreenOutputGate()
        gate.admit(phoneOwnsScreen = false, keyFrame = false)
        val resumed = gate.admit(phoneOwnsScreen = true, keyFrame = true)
        assertTrue(resumed.send)
        assertEquals(Transition.RESUMED, resumed.transition)
        assertEquals(1L, resumed.skippedThisPause)
    }

    @Test
    fun screenTakenAgainWhileWaitingForIdrStartsANewPause() {
        val gate = ScreenOutputGate()
        gate.admit(phoneOwnsScreen = false, keyFrame = false)
        gate.admit(phoneOwnsScreen = true, keyFrame = false)
        val pausedAgain = gate.admit(phoneOwnsScreen = false, keyFrame = true)
        assertFalse(pausedAgain.send)
        assertEquals(Transition.PAUSED, pausedAgain.transition)
        assertEquals(1L, pausedAgain.skippedThisPause)
        assertEquals(3L, pausedAgain.skippedTotal)
    }
}
