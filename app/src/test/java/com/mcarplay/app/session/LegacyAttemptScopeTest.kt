package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAttemptScopeTest {
    private val observation = ReceiverObservation(binding = "binding-1", epoch = 3, session = 7)

    /** 重构前 `onCarPlayEndpoint` 的两个局部函数，作为等价性的对照。 */
    private fun legacyOwnsConnection(owns: Boolean) = owns

    private fun legacyWasCancelled(
        connectCancelled: Boolean,
        owns: Boolean,
    ) = connectCancelled || !legacyOwnsConnection(owns)

    @Test
    fun matchesTheLegacyPredicatesForEveryFlagCombination() {
        for (cancelled in listOf(false, true)) {
            for (owns in listOf(false, true)) {
                val scope = LegacyAttemptScope("a1", observation, cancelled = { cancelled }, owns = { owns })
                val case = "cancelled=$cancelled owns=$owns"
                assertEquals(case, legacyOwnsConnection(owns), scope.isCurrent)
                assertEquals(case, legacyWasCancelled(cancelled, owns), !scope.isActive)
            }
        }
    }

    @Test
    fun userCancelKeepsOwnershipSoTheRoundCanStillReleaseItsMutex() {
        val scope = LegacyAttemptScope("a1", observation, cancelled = { true }, owns = { true })
        assertTrue(scope.isCurrent)
        assertFalse(scope.isActive)
    }

    @Test
    fun readsCurrentStateOnEveryAccess() {
        var cancelled = false
        var owns = true
        val scope = LegacyAttemptScope("a1", observation, cancelled = { cancelled }, owns = { owns })
        assertTrue(scope.isActive)
        cancelled = true
        assertFalse(scope.isActive)
        assertTrue(scope.isCurrent)
        cancelled = false
        owns = false
        assertFalse(scope.isActive)
        assertFalse(scope.isCurrent)
    }

    @Test
    fun ownershipIsAskedAboutThisRoundsObservation() {
        val seen = mutableListOf<ReceiverObservation?>()
        val scope =
            LegacyAttemptScope("a1", observation, cancelled = { false }, owns = { seen.add(it) })
        scope.isActive
        scope.isCurrent
        assertEquals(2, seen.size)
        seen.forEach { assertSame(observation, it) }
        assertEquals("a1", scope.id)
    }

    @Test
    fun cancellationIsCheckedBeforeOwnershipLikeTheLegacyShortCircuit() {
        var ownsCalls = 0
        val scope = LegacyAttemptScope("a1", null, cancelled = { true }, owns = { ownsCalls++ > -1 })
        assertFalse(scope.isActive)
        assertEquals(0, ownsCalls)
    }
}
