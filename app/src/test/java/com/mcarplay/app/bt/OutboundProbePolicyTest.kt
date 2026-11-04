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

    /** PR #29 审查：DISCONNECT_PROFILES 主动断开引起的 ACL 断开，不能挡住后续 RFCOMM 重试。 */
    @Test
    fun selfInitiatedProfileDisconnectKeepsRetrying() {
        val policy = OutboundProbePolicy()
        policy.arm(0L, user)
        policy.beginSelfLinkChange(1_000L) // 断配置文件
        policy.endSelfLinkChange(2_500L)
        policy.beginSelfLinkChange(2_600L) // RFCOMM 尝试
        policy.endSelfLinkChange(2_700L)
        // ACL 断开广播晚到，落在最后一次记录后的尾巴里
        val acl = down(2_700L + OutboundProbePolicy.SELF_LINK_TAIL_MILLIS - 1)
        assertTrue(policy.shouldProbe(acl, 5_700L))
        assertTrue(policy.shouldProbe(acl, user - 1))
        // 窗口结束照常停
        assertFalse(policy.shouldProbe(acl, user))
    }

    @Test
    fun carDisconnectOutsideSelfRangeStillStops() {
        val policy = OutboundProbePolicy()
        policy.arm(0L, user)
        policy.beginSelfLinkChange(1_000L)
        policy.endSelfLinkChange(1_000L)
        assertFalse(policy.shouldProbe(down(1_000L + OutboundProbePolicy.SELF_LINK_TAIL_MILLIS + 1), 20_000L))
        // 自己的操作开始之前就断开（且在窗口打开之后）：同样是车机离线
        policy.beginSelfLinkChange(30_000L)
        assertFalse(policy.shouldProbe(down(25_000L), 31_000L))
        policy.endSelfLinkChange(31_000L)
    }

    /** PR #29 第二次审查：阻塞超过 10 s 的连接，结束时不能丢掉操作期间的自身断链。 */
    @Test
    fun selfDisconnectDuringLongBlockingConnectKeepsRetrying() {
        val policy = OutboundProbePolicy()
        policy.arm(0L, user)
        policy.beginSelfLinkChange(1_000L)
        // 操作进行中（已超过尾巴时长）断开，仍算自己引起
        assertTrue(policy.shouldProbe(down(15_000L), 16_000L))
        policy.endSelfLinkChange(20_000L)
        assertTrue(policy.shouldProbe(down(15_000L), 23_000L))
        assertTrue(policy.shouldProbe(down(20_000L + OutboundProbePolicy.SELF_LINK_TAIL_MILLIS), 31_000L))
        assertFalse(policy.shouldProbe(down(20_000L + OutboundProbePolicy.SELF_LINK_TAIL_MILLIS + 1), 31_000L))
    }

    @Test
    fun operationStartedInsidePreviousTailKeepsTheOriginalStart() {
        val policy = OutboundProbePolicy()
        policy.arm(0L, user)
        policy.beginSelfLinkChange(1_000L)
        policy.endSelfLinkChange(2_000L)
        // 3 秒后的重试落在上一段尾巴里：并入同一段，起点仍是 1 s
        policy.beginSelfLinkChange(5_000L)
        policy.endSelfLinkChange(30_000L)
        assertTrue(policy.shouldProbe(down(1_500L), 32_000L))
        assertFalse(policy.shouldProbe(down(999L), 32_000L))
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
