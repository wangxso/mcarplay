package com.mcarplay.app.bt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundProbePolicyTest {
    private val user = OutboundProbePolicy.USER_WINDOW_MILLIS
    private val grace = OutboundProbePolicy.SESSION_END_GRACE_MILLIS

    private fun up(at: Long) = BtAclTracker.Observation(connected = true, atMillis = at)

    private fun down(at: Long) = BtAclTracker.Observation(connected = false, atMillis = at)

    @After
    fun tearDown() = BtAclTracker.clear()

    @Test
    fun neverArmedAndUnknownAclDoesNotProbe() {
        assertFalse(OutboundProbePolicy().shouldProbe(null, 1_000L))
    }

    @Test
    fun connectedAclAlwaysProbes() {
        val policy = OutboundProbePolicy()
        assertTrue(policy.shouldProbe(up(0L), 10 * 3_600_000L))
    }

    @Test
    fun userArmProbesUntilWindowEndsWhenAclUnknown() {
        val policy = OutboundProbePolicy()
        policy.arm(1_000L, user)
        assertTrue(policy.shouldProbe(null, 1_000L))
        assertTrue(policy.shouldProbe(null, 1_000L + user - 1))
        assertFalse(policy.shouldProbe(null, 1_000L + user))
    }

    @Test
    fun userTapAfterAclDisconnectStillProbes() {
        val policy = OutboundProbePolicy()
        policy.arm(5_000L, user)
        assertTrue(policy.shouldProbe(down(4_000L), 6_000L))
    }

    @Test
    fun aclDisconnectInsideWindowStopsProbingImmediately() {
        val policy = OutboundProbePolicy()
        policy.arm(5_000L, user)
        assertFalse(policy.shouldProbe(down(6_000L), 7_000L))
    }

    /**
     * 2026-11-03/04 奇瑞日志的时序：会话中 ACL 先断开，推流终止后恢复探测，此后整夜每分钟一次。
     * 现在只剩会话结束后的宽限窗口，之后不再发起任何连接，直到 ACL 重新连上。
     */
    @Test
    fun overnightAfterSessionEndProbesOnlyDuringGrace() {
        val policy = OutboundProbePolicy()
        val tap = 0L
        policy.arm(tap, user)
        val aclDown = 600_000L
        val sessionEnd = 900_000L
        policy.arm(sessionEnd, grace)
        val acl = down(aclDown)
        assertTrue(policy.shouldProbe(acl, sessionEnd + 1_000L))
        val attempts =
            (0 until 10 * 60).count { minute ->
                policy.shouldProbe(acl, sessionEnd + grace + minute * 60_000L)
            }
        assertEquals(0, attempts)
        // 早上车机上电：ACL 连上即恢复
        assertTrue(policy.shouldProbe(up(sessionEnd + 10 * 3_600_000L), sessionEnd + 10 * 3_600_000L + 1))
    }

    @Test
    fun laterArmReplacesEarlierWindow() {
        val policy = OutboundProbePolicy()
        policy.arm(0L, user)
        policy.arm(10_000L, grace)
        assertFalse(policy.shouldProbe(null, 10_000L + grace))
    }

    @Test
    fun trackerIsCaseInsensitiveAndKeepsLatest() {
        assertNull(BtAclTracker.observe("aa:bb:cc:dd:ee:ff"))
        BtAclTracker.record("aa:bb:cc:dd:ee:ff", true, 1L)
        BtAclTracker.record("AA:BB:CC:DD:EE:FF", false, 2L)
        assertEquals(down(2L), BtAclTracker.observe("Aa:bB:cc:dd:ee:ff"))
    }
}
