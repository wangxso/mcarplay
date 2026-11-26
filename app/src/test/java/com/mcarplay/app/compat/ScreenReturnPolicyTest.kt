package com.mcarplay.app.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReturnPolicyTest {
    @Test
    fun enumKeepsHistoricalKeysAndOrder() {
        // key 是持久化契约（compat_passat_return_strategy 的值），顺序也不得调整。
        assertEquals(
            listOf("off", "rebuild_retry", "disconnect", "no_rebuild"),
            ScreenReturnStrategy.entries.map { it.key },
        )
        assertEquals(2, ScreenReturnPolicy.DISCONNECT_FAILURE_THRESHOLD)
    }

    @Test
    fun unknownKeyFallsBackToOff() {
        assertEquals(ScreenReturnStrategy.OFF, ScreenReturnStrategy.fromKey(null))
        assertEquals(ScreenReturnStrategy.OFF, ScreenReturnStrategy.fromKey("x"))
        ScreenReturnStrategy.entries.forEach { assertEquals(it, ScreenReturnStrategy.fromKey(it.key)) }
    }

    @Test
    fun keyMatchingStaysCaseSensitiveAndRejectsBlank() {
        listOf("", " ", "OFF", "Rebuild_Retry", " rebuild_retry", "rebuild_retry ", "disconnect_after_failures").forEach { raw ->
            assertEquals("$raw 必须回退 OFF", ScreenReturnStrategy.OFF, ScreenReturnStrategy.fromKey(raw))
        }
    }

    @Test
    fun onlyNoRebuildSuppressesRebuild() {
        assertFalse(ScreenReturnPolicy.rebuildStream(ScreenReturnStrategy.NO_REBUILD, true))
        assertTrue(ScreenReturnPolicy.rebuildStream(ScreenReturnStrategy.OFF, true))
        assertTrue(ScreenReturnPolicy.rebuildStream(ScreenReturnStrategy.REBUILD_RETRY, true))
        assertFalse(ScreenReturnPolicy.rebuildStream(ScreenReturnStrategy.OFF, false))
    }

    @Test
    fun rebuildStreamFollowsRequestForEveryStrategy() {
        // OFF 不是「禁止一切主屏重建」：requested=true 的原重建仍放行，只有 NO_REBUILD 抑制。
        ScreenReturnStrategy.entries.forEach { strategy ->
            assertEquals(strategy != ScreenReturnStrategy.NO_REBUILD, ScreenReturnPolicy.rebuildStream(strategy, requested = true))
            assertFalse(ScreenReturnPolicy.rebuildStream(strategy, requested = false))
        }
    }

    @Test
    fun takeBackFailsOnlyWithoutAnyReaction() {
        assertTrue(ScreenReturnPolicy.takeBackFailed(0, 0))
        assertFalse(ScreenReturnPolicy.takeBackFailed(3, 0))
        assertFalse(ScreenReturnPolicy.takeBackFailed(0, 1))
    }

    @Test
    fun takeBackFailedUsesNonPositiveBothCounts() {
        // 负增量（计数重置/溢出可出现）也按 <= 0 处理，不能用 == 0。
        listOf(-1, 0, 1).forEach { touches ->
            listOf(-1, 0, 1).forEach { keyFrames ->
                assertEquals(
                    "touches=$touches keyFrames=$keyFrames",
                    touches <= 0 && keyFrames <= 0,
                    ScreenReturnPolicy.takeBackFailed(touches, keyFrames),
                )
            }
        }
    }

    @Test
    fun retryOnlyOnceAndOnlyForRetryStrategy() {
        assertTrue(ScreenReturnPolicy.retryRebuild(ScreenReturnStrategy.REBUILD_RETRY, failed = true, alreadyRetried = false))
        assertFalse(ScreenReturnPolicy.retryRebuild(ScreenReturnStrategy.REBUILD_RETRY, failed = true, alreadyRetried = true))
        assertFalse(ScreenReturnPolicy.retryRebuild(ScreenReturnStrategy.REBUILD_RETRY, failed = false, alreadyRetried = false))
        assertFalse(ScreenReturnPolicy.retryRebuild(ScreenReturnStrategy.OFF, failed = true, alreadyRetried = false))
        assertFalse(ScreenReturnPolicy.retryRebuild(ScreenReturnStrategy.DISCONNECT_AFTER_FAILURES, failed = true, alreadyRetried = false))
    }

    @Test
    fun retryRebuildMatrixStaysPurePerStrategy() {
        ScreenReturnStrategy.entries.forEach { strategy ->
            listOf(false, true).forEach { failed ->
                listOf(false, true).forEach { alreadyRetried ->
                    assertEquals(
                        "strategy=$strategy failed=$failed retried=$alreadyRetried",
                        strategy == ScreenReturnStrategy.REBUILD_RETRY && failed && !alreadyRetried,
                        ScreenReturnPolicy.retryRebuild(strategy, failed, alreadyRetried),
                    )
                }
            }
        }
    }

    @Test
    fun disconnectNeedsConsecutiveFailuresAndItsStrategy() {
        val s = ScreenReturnStrategy.DISCONNECT_AFTER_FAILURES
        assertFalse(ScreenReturnPolicy.disconnect(s, 1))
        assertTrue(ScreenReturnPolicy.disconnect(s, ScreenReturnPolicy.DISCONNECT_FAILURE_THRESHOLD))
        assertFalse(ScreenReturnPolicy.disconnect(ScreenReturnStrategy.OFF, 5))
        assertFalse(ScreenReturnPolicy.disconnect(ScreenReturnStrategy.REBUILD_RETRY, 5))
    }

    @Test
    fun disconnectThresholdMatrixStaysTwoAndStrategyBound() {
        ScreenReturnStrategy.entries.forEach { strategy ->
            listOf(0, 1, 2, 3).forEach { failures ->
                assertEquals(
                    "strategy=$strategy failures=$failures",
                    strategy == ScreenReturnStrategy.DISCONNECT_AFTER_FAILURES && failures >= 2,
                    ScreenReturnPolicy.disconnect(strategy, failures),
                )
            }
        }
    }
}
