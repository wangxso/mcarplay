package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** 帕萨特 2025（VW4211）2026-10-31 23:51–23:59 的连入时序：新鲜邀请才连，旧邀请/抢先连入被拒。 */
class CtrlInviteGateTest {
    private val start = 100_000L

    @Test
    fun recentInviteConnectsImmediately() {
        // 成功的两次：邀请在 7s / 13s 前
        for (ago in listOf(7_000L, 13_000L)) {
            assertEquals(
                CtrlInviteGate.Decision.INVITED,
                CtrlInviteGate.decide(start, start, start - ago, mdnsEndpointFound = true),
            )
        }
    }

    @Test
    fun staleInviteDoesNotCountAndMdnsWaitsForGrace() {
        // 失败的一次：邀请在 42s 前，_airplay._tcp 已可见
        val stale = start - 42_000L
        assertEquals(CtrlInviteGate.Decision.WAIT, CtrlInviteGate.decide(start + 160, start, stale, mdnsEndpointFound = true))
        assertEquals(
            CtrlInviteGate.Decision.MDNS_FALLBACK,
            CtrlInviteGate.decide(start + CtrlInviteGate.MDNS_GRACE_MS, start, stale, mdnsEndpointFound = true),
        )
    }

    @Test
    fun inviteArrivingDuringGraceWins() {
        // 失败的其余几次：邀请在连入后 0.3–0.6s 才到——等待期间到达即放行
        assertEquals(
            CtrlInviteGate.Decision.INVITED,
            CtrlInviteGate.decide(start + 600, start, start + 500, mdnsEndpointFound = true),
        )
    }

    @Test
    fun inviteExpiresWhileWaiting() {
        // 时效按当前时刻评估：开始等待时 19s 前的邀请仍新鲜，再等 2s 就过期了
        val invite = start - 19_000L
        assertEquals(CtrlInviteGate.Decision.INVITED, CtrlInviteGate.decide(start, start, invite, mdnsEndpointFound = false))
        assertEquals(CtrlInviteGate.Decision.WAIT, CtrlInviteGate.decide(start + 2_000, start, invite, mdnsEndpointFound = false))
    }

    @Test
    fun withoutInviteOrMdnsKeepWaiting() {
        assertEquals(CtrlInviteGate.Decision.WAIT, CtrlInviteGate.decide(start + 10_000, start, 0L, mdnsEndpointFound = false))
    }
}
