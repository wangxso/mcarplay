package com.mcarplay.app.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDisconnectCooldownTest {
    private val stoppedAtMs = 100_000L
    private val cooldown = ManualDisconnectCooldown("AA:BB:CC:DD:EE:01", stoppedAtMs)

    @Test
    fun blocksImmediateAndQueuedReconnectsForTheSameReceiver() {
        assertTrue(cooldown.blocks("aa:bb:cc:dd:ee:01", stoppedAtMs))
        assertTrue(cooldown.blocks("AA:BB:CC:DD:EE:01", stoppedAtMs + 29_999L))
        assertFalse(cooldown.blocks("AA:BB:CC:DD:EE:01", stoppedAtMs + 30_000L))
    }

    @Test
    fun selectingAnotherReceiverDoesNotInheritTheCooldown() {
        assertFalse(cooldown.blocks("AA:BB:CC:DD:EE:02", stoppedAtMs + 500L))
    }

    @Test
    fun automaticReceiverSelectionStillHonorsManualStop() {
        assertTrue(ManualDisconnectCooldown(null, stoppedAtMs).blocks("AA:BB:CC:DD:EE:01", stoppedAtMs + 500L))
        assertTrue(cooldown.blocks(null, stoppedAtMs + 500L))
    }

    @Test
    fun wallClockChangesCannotLeaveAPermanentBlock() {
        assertTrue(cooldown.blocks(null, stoppedAtMs - 1L))
        assertFalse(cooldown.blocks(null, stoppedAtMs - 30_001L))
    }
}
