package com.mcarplay.app.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassatReturnPolicyTest {
    @Test
    fun unknownKeyFallsBackToOff() {
        assertEquals(PassatReturnStrategy.OFF, PassatReturnStrategy.fromKey(null))
        assertEquals(PassatReturnStrategy.OFF, PassatReturnStrategy.fromKey("x"))
        PassatReturnStrategy.entries.forEach { assertEquals(it, PassatReturnStrategy.fromKey(it.key)) }
    }

    @Test
    fun onlyNoRebuildSuppressesRebuild() {
        assertFalse(PassatReturnPolicy.rebuildStream(PassatReturnStrategy.NO_REBUILD, true))
        assertTrue(PassatReturnPolicy.rebuildStream(PassatReturnStrategy.OFF, true))
        assertTrue(PassatReturnPolicy.rebuildStream(PassatReturnStrategy.REBUILD_RETRY, true))
        assertFalse(PassatReturnPolicy.rebuildStream(PassatReturnStrategy.OFF, false))
    }

    @Test
    fun takeBackFailsOnlyWithoutAnyReaction() {
        assertTrue(PassatReturnPolicy.takeBackFailed(0, 0))
        assertFalse(PassatReturnPolicy.takeBackFailed(3, 0))
        assertFalse(PassatReturnPolicy.takeBackFailed(0, 1))
    }

    @Test
    fun retryOnlyOnceAndOnlyForRetryStrategy() {
        assertTrue(PassatReturnPolicy.retryRebuild(PassatReturnStrategy.REBUILD_RETRY, failed = true, alreadyRetried = false))
        assertFalse(PassatReturnPolicy.retryRebuild(PassatReturnStrategy.REBUILD_RETRY, failed = true, alreadyRetried = true))
        assertFalse(PassatReturnPolicy.retryRebuild(PassatReturnStrategy.REBUILD_RETRY, failed = false, alreadyRetried = false))
        assertFalse(PassatReturnPolicy.retryRebuild(PassatReturnStrategy.OFF, failed = true, alreadyRetried = false))
        assertFalse(PassatReturnPolicy.retryRebuild(PassatReturnStrategy.DISCONNECT_AFTER_FAILURES, failed = true, alreadyRetried = false))
    }

    @Test
    fun disconnectNeedsConsecutiveFailuresAndItsStrategy() {
        val s = PassatReturnStrategy.DISCONNECT_AFTER_FAILURES
        assertFalse(PassatReturnPolicy.disconnect(s, 1))
        assertTrue(PassatReturnPolicy.disconnect(s, PassatReturnPolicy.DISCONNECT_FAILURE_THRESHOLD))
        assertFalse(PassatReturnPolicy.disconnect(PassatReturnStrategy.OFF, 5))
        assertFalse(PassatReturnPolicy.disconnect(PassatReturnStrategy.REBUILD_RETRY, 5))
    }
}
