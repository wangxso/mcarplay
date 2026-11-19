package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LegacyAttemptScope] 与真实交接 helper 的行为契约。
 *
 * 前五个用例移植自历史分支 `90e48aab` 的最小接口测试意图；其余用例补齐交接归属 H 的
 * 独立维度与读取短路。断言只引用作用域对象与 helper 本身，不复制生产表达式。
 */
class LegacyAttemptScopeTest {
    private val observation = ReceiverObservation(binding = "binding-1", epoch = 3, session = 7)

    /** 重构前 `onCarPlayEndpoint` 的两个局部函数，作为等价性的对照。 */
    private fun legacyOwnsConnection(owns: Boolean) = owns

    private fun legacyWasCancelled(
        connectCancelled: Boolean,
        owns: Boolean,
    ) = connectCancelled || !legacyOwnsConnection(owns)

    /** 重构前 `handoffCancelled()` 的两层组合。 */
    private fun legacyHandoffCancelled(
        connectCancelled: Boolean,
        owns: Boolean,
        handoffOwned: Boolean,
    ) = legacyWasCancelled(connectCancelled, owns) || !handoffOwned

    private fun scope(
        cancelled: Boolean,
        owns: Boolean,
    ) = LegacyAttemptScope(
        id = "a1",
        observation = observation,
        cancelled = { cancelled },
        owns = { owns },
    )

    @Test
    fun matchesTheLegacyPredicatesForEveryFlagCombination() {
        for (cancelled in listOf(false, true)) {
            for (owns in listOf(false, true)) {
                val candidate = scope(cancelled = cancelled, owns = owns)
                val case = "cancelled=$cancelled owns=$owns"
                assertEquals(case, legacyOwnsConnection(owns), candidate.isCurrent)
                assertEquals(case, legacyWasCancelled(cancelled, owns), !candidate.isActive)
            }
        }
    }

    @Test
    fun userCancelKeepsOwnershipSoTheRoundCanStillReleaseItsMutex() {
        val candidate = scope(cancelled = true, owns = true)
        assertTrue("用户取消后本轮仍拥有连接，需负责释放交接", candidate.isCurrent)
        assertFalse("用户取消后本轮不再活跃", candidate.isActive)
    }

    @Test
    fun readsCurrentStateOnEveryAccess() {
        var cancelled = false
        var owns = true
        val candidate =
            LegacyAttemptScope("a1", observation, cancelled = { cancelled }, owns = { owns })
        assertTrue(candidate.isActive)
        cancelled = true
        assertFalse(candidate.isActive)
        assertTrue(candidate.isCurrent)
        cancelled = false
        owns = false
        assertFalse(candidate.isActive)
        assertFalse(candidate.isCurrent)
    }

    @Test
    fun ownershipIsAskedAboutThisRoundsObservation() {
        val seen = mutableListOf<ReceiverObservation?>()
        val candidate = LegacyAttemptScope("a1", observation, cancelled = { false }, owns = { seen.add(it) })
        candidate.isActive
        candidate.isCurrent
        assertEquals("两个 getter 各读一次归属", 2, seen.size)
        seen.forEach { assertSame("归属回调必须收到本轮 observation", observation, it) }
        assertEquals("a1", candidate.id)
        assertSame(observation, candidate.observation)
    }

    @Test
    fun cancellationIsCheckedBeforeOwnershipLikeTheLegacyShortCircuit() {
        var ownsCalls = 0
        val candidate = LegacyAttemptScope("a1", null, cancelled = { true }, owns = { ownsCalls++ > -1 })
        assertFalse(candidate.isActive)
        assertEquals("取消后不得再问归属", 0, ownsCalls)
    }

    @Test
    fun handoffPredicateMatchesTheLegacyTwoLayerFormulaForEveryCombination() {
        for (cancelled in listOf(false, true)) {
            for (owns in listOf(false, true)) {
                for (handoffOwned in listOf(false, true)) {
                    var handoffCalls = 0
                    val candidate = scope(cancelled = cancelled, owns = owns)
                    val actual =
                        attemptHandoffCancelled(candidate) {
                            handoffCalls++
                            handoffOwned
                        }
                    val case = "cancelled=$cancelled owns=$owns handoffOwned=$handoffOwned"
                    assertEquals(case, legacyHandoffCancelled(cancelled, owns, handoffOwned), actual)
                    assertEquals(case, if (!cancelled && owns) 1 else 0, handoffCalls)
                }
            }
        }
    }

    @Test
    fun handoffOwnershipIsIndependentOfCurrentAndActive() {
        // 本轮仍拥有连接、未取消，但交接所有权已不属于本轮：isCurrent/isActive 不变，只熔断交接。
        val candidate = scope(cancelled = false, owns = true)
        assertTrue(candidate.isCurrent)
        assertTrue(candidate.isActive)
        assertTrue("交接归属不能混入 isCurrent", attemptHandoffCancelled(candidate) { false })
        assertTrue("读交接判据不能反过来改变 isCurrent", candidate.isCurrent)
        assertTrue("读交接判据不能反过来改变 isActive", candidate.isActive)
        assertFalse("交接仍归本轮时不得熔断", attemptHandoffCancelled(candidate) { true })
    }

    @Test
    fun inactiveScopeDoesNotReadHandoffOwnership() {
        var handoffCalls = 0
        val cancelledScope = scope(cancelled = true, owns = true)
        assertTrue(
            attemptHandoffCancelled(cancelledScope) {
                handoffCalls++
                true
            },
        )
        assertEquals("C=true 时短路，不问交接归属", 0, handoffCalls)

        val staleScope = scope(cancelled = false, owns = false)
        assertTrue(
            attemptHandoffCancelled(staleScope) {
                handoffCalls++
                true
            },
        )
        assertEquals("I=false 时短路，不问交接归属", 0, handoffCalls)
    }

    @Test
    fun activeScopeReadsHandoffOwnershipExactlyOncePerEvaluation() {
        var handoffCalls = 0
        val candidate = scope(cancelled = false, owns = true)
        assertFalse(
            attemptHandoffCancelled(candidate) {
                handoffCalls++
                true
            },
        )
        assertEquals("活跃轮次每次判定只读一次交接归属", 1, handoffCalls)
    }

    @Test
    fun handoffOwnershipIsReadDynamicallyOnEveryEvaluation() {
        var handoffOwned = true
        val candidate = scope(cancelled = false, owns = true)
        assertFalse(attemptHandoffCancelled(candidate) { handoffOwned })
        handoffOwned = false
        assertTrue("交接归属变化必须在下一次判定立即生效", attemptHandoffCancelled(candidate) { handoffOwned })
        assertTrue(candidate.isActive)
    }
}
