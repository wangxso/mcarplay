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
        assertTrue(gate.admit(phoneOwnsScreen = true, keyFrame = true, nowMs = 0).send)
        assertTrue(gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 16).send)
        assertEquals("主屏归车机时累计跳过 0 帧，最近一次暂停 0 帧，最近一次暂停请求关键帧 0 次", gate.summary())
    }

    @Test
    fun carOwnedScreenDropsEveryFrameIncludingIdr() {
        val gate = ScreenOutputGate()
        val first = gate.admit(phoneOwnsScreen = false, keyFrame = true, nowMs = 0)
        assertFalse(first.send)
        assertEquals(Transition.PAUSED, first.transition)
        val second = gate.admit(phoneOwnsScreen = false, keyFrame = false, nowMs = 16)
        assertFalse(second.send)
        assertNull(second.transition)
        assertEquals(2L, second.skippedThisPause)
        assertEquals(2L, second.skippedTotal)
    }

    @Test
    fun returningScreenWaitsForNextIdr() {
        val gate = ScreenOutputGate()
        gate.admit(phoneOwnsScreen = false, keyFrame = false, nowMs = 0)
        val resuming = gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 100)
        assertFalse(resuming.send)
        assertEquals(Transition.RESUMING, resuming.transition)
        assertTrue(resuming.requestKeyFrame)
        assertEquals(1, resuming.keyFrameRequestAttempt)
        assertFalse(gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 200).send)
        val resumed = gate.admit(phoneOwnsScreen = true, keyFrame = true, nowMs = 300)
        assertTrue(resumed.send)
        assertEquals(Transition.RESUMED, resumed.transition)
        assertEquals(3L, resumed.skippedThisPause)
        assertTrue(gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 316).send)
    }

    @Test
    fun suspendedStateIsObservableOnlyWhileFullyPaused() {
        val gate = ScreenOutputGate()
        assertFalse(gate.isSuspended)
        gate.admit(phoneOwnsScreen = false, keyFrame = true, nowMs = 0)
        assertTrue("The transcode feed path may stop while fully paused", gate.isSuspended)
        gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 100)
        assertFalse("AWAIT_KEY_FRAME must keep feeding so an IDR can be produced", gate.isSuspended)
        gate.admit(phoneOwnsScreen = true, keyFrame = true, nowMs = 200)
        assertFalse(gate.isSuspended)
    }

    @Test
    fun firstFrameAfterReturnCanResumeWhenItIsAnIdr() {
        val gate = ScreenOutputGate()
        gate.admit(phoneOwnsScreen = false, keyFrame = false, nowMs = 0)
        val resumed = gate.admit(phoneOwnsScreen = true, keyFrame = true, nowMs = 16)
        assertTrue(resumed.send)
        assertEquals(Transition.RESUMED, resumed.transition)
        assertEquals(1L, resumed.skippedThisPause)
        assertFalse(resumed.requestKeyFrame)
    }

    @Test
    fun screenTakenAgainWhileWaitingForIdrStartsANewPause() {
        val gate = ScreenOutputGate()
        gate.admit(phoneOwnsScreen = false, keyFrame = false, nowMs = 0)
        gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 100)
        val pausedAgain = gate.admit(phoneOwnsScreen = false, keyFrame = true, nowMs = 200)
        assertFalse(pausedAgain.send)
        assertEquals(Transition.PAUSED, pausedAgain.transition)
        assertEquals(1L, pausedAgain.skippedThisPause)
        assertEquals(3L, pausedAgain.skippedTotal)
    }

    @Test
    fun keyFrameRequestRetriesWhileWaitingForIdr() {
        val gate = ScreenOutputGate(keyFrameRetryMs = 500)
        gate.admit(phoneOwnsScreen = false, keyFrame = false, nowMs = 0)

        val first = gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 100)
        assertTrue(first.requestKeyFrame)
        assertEquals(1, first.keyFrameRequestAttempt)

        val tooSoon = gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 400)
        assertFalse(tooSoon.requestKeyFrame)

        val retry = gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 600)
        assertTrue(retry.requestKeyFrame)
        assertEquals(2, retry.keyFrameRequestAttempt)

        val secondRetry = gate.admit(phoneOwnsScreen = true, keyFrame = false, nowMs = 1_100)
        assertTrue(secondRetry.requestKeyFrame)
        assertEquals(3, secondRetry.keyFrameRequestAttempt)
    }
}
