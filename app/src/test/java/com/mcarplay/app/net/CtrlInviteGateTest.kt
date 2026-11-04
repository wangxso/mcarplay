package com.mcarplay.app.net

import com.mcarplay.app.net.CtrlInviteGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** 帕萨特 2025（VW4211）的连入时序：新鲜邀请才连，旧邀请/抢先连入被拒。 */
class CtrlInviteGateTest {
    private val start = 100_000L

    @Test
    fun recentInviteConnectsImmediately() {
        // 2026-10-31 成功的两次：邀请在 7s / 13s 前
        for (ago in listOf(7_000L, 13_000L)) {
            assertEquals(Decision.INVITED, CtrlInviteGate.decide(start, start, start - ago, mdnsEndpointFound = true))
        }
    }

    @Test
    fun inviteArrivingDuringGraceWins() {
        // 2026-10-31 失败的几次：邀请在连入后 0.3–0.6s 才到——等待期间到达即放行
        assertEquals(Decision.INVITED, CtrlInviteGate.decide(start + 600, start, start + 500, mdnsEndpointFound = true))
    }

    @Test
    fun inviteExpiresWhileWaiting() {
        // 时效按当前时刻评估：开始等待时 19s 前的邀请仍新鲜，再等 2s 就过期了
        val invite = start - 19_000L
        assertEquals(Decision.INVITED, CtrlInviteGate.decide(start, start, invite, mdnsEndpointFound = false))
        assertEquals(
            Decision.WAIT,
            CtrlInviteGate.decide(start + 2_000, start, invite, mdnsEndpointFound = false, advertisedAtMillis = start + 500),
        )
    }

    @Test
    fun graceCountsFromAdvertVisibilityNotWaitStart() {
        // 2026-11-01 22:07：开始等待 59.067，广播 59.895 可见；旧实现 01.087 重公告、02.097 直连被拒
        val advertised = start + 828
        // 旧实现重公告的时刻：广播可见才 1.2 s，不得注销它
        assertEquals(
            Decision.WAIT,
            CtrlInviteGate.decide(start + 2_020, start, 0L, mdnsEndpointFound = true, advertisedAtMillis = advertised),
        )
        // 旧实现直连的时刻：同样继续等
        assertEquals(
            Decision.WAIT,
            CtrlInviteGate.decide(start + 3_030, start, 0L, mdnsEndpointFound = true, advertisedAtMillis = advertised),
        )
        // 广播可见满 3 s 仍无邀请：先补一次公告，而不是直连
        assertEquals(
            Decision.REANNOUNCE,
            CtrlInviteGate.decide(advertised + 3_000, start, 0L, mdnsEndpointFound = true, advertisedAtMillis = advertised),
        )
    }

    @Test
    fun fallbackWaitsForGraceAfterReannounce() {
        val reannounced = start + 4_000
        // 重公告后注销期间（广播不可见）不直连
        assertEquals(
            Decision.WAIT,
            CtrlInviteGate.decide(reannounced + 1_000, start, 0L, mdnsEndpointFound = true, reannouncedAtMillis = reannounced),
        )
        // 重新可见后再满 3 s 才直连
        val visible = reannounced + 1_000
        assertEquals(
            Decision.WAIT,
            CtrlInviteGate.decide(
                reannounced + 3_500,
                start,
                0L,
                mdnsEndpointFound = true,
                advertisedAtMillis = visible,
                reannouncedAtMillis = reannounced,
            ),
        )
        assertEquals(
            Decision.MDNS_FALLBACK,
            CtrlInviteGate.decide(
                visible + 3_000,
                start,
                0L,
                mdnsEndpointFound = true,
                advertisedAtMillis = visible,
                reannouncedAtMillis = reannounced,
            ),
        )
    }

    @Test
    fun unseenAdvertIsReannouncedAfterTwoSeconds() {
        // catplay force_announce：绑网瞬间的公告丢失，广播一直没确认可见
        assertEquals(Decision.WAIT, CtrlInviteGate.decide(start + 1_900, start, 0L, mdnsEndpointFound = true))
        assertEquals(Decision.REANNOUNCE, CtrlInviteGate.decide(start + 2_000, start, 0L, mdnsEndpointFound = true))
    }

    @Test
    fun staleInviteDoesNotCount() {
        // 2026-10-31 失败的一次：邀请在 42s 前
        val stale = start - 42_000L
        assertEquals(
            Decision.WAIT,
            CtrlInviteGate.decide(start + 160, start, stale, mdnsEndpointFound = true, advertisedAtMillis = start),
        )
    }

    @Test
    fun withoutMdnsEndpointNeverFallsBack() {
        assertEquals(
            Decision.WAIT,
            CtrlInviteGate.decide(start + 30_000, start, 0L, mdnsEndpointFound = false, reannouncedAtMillis = start + 2_000),
        )
    }
}
