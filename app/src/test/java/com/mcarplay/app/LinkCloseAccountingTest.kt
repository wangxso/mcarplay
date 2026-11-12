package com.mcarplay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCloseAccountingTest {
    @Test
    fun secondLinkCloseDoesNotCountWhileTheHandoffIsHealthy() {
        // A 的交接健康（ownedHandoff=true）；同 peer 的另一条 RFCOMM 链路关闭时
        // ownedHandoff/ownedHandoffEnded/ownsCurrentSession 均为 false。
        assertFalse(
            countsLinkCloseForAutoReconnect(
                ownedHandoffEnded = false,
                ownedHandoff = false,
                ownsCurrentSession = false,
                ownsPendingStartSession = false,
            ),
        )
    }

    @Test
    fun currentHandoffOwnerCloseCounts() {
        assertTrue(
            countsLinkCloseForAutoReconnect(
                ownedHandoffEnded = false,
                ownedHandoff = true,
                ownsCurrentSession = false,
                ownsPendingStartSession = false,
            ),
        )
    }

    @Test
    fun ownerCloseAfterCancellationStillCounts() {
        assertTrue(
            countsLinkCloseForAutoReconnect(
                ownedHandoffEnded = true,
                ownedHandoff = true,
                ownsCurrentSession = false,
                ownsPendingStartSession = false,
            ),
        )
    }

    @Test
    fun closeOfLinkThatDeliveredTheCurrentSessionCounts() {
        // 推流阶段交接所有权已释放，但该链路就是当前会话的承载链路。
        assertTrue(
            countsLinkCloseForAutoReconnect(
                ownedHandoffEnded = false,
                ownedHandoff = false,
                ownsCurrentSession = true,
                ownsPendingStartSession = false,
            ),
        )
    }

    @Test
    fun emptyEndpointCandidateCloseCounts() {
        // 空端点等待晚到 0x4301 时已释放 handoff ownership、且可能还没有 client；
        // 它的链路关闭是真正的零帧快速失败，不能被熔断过滤掉。
        assertTrue(
            countsLinkCloseForAutoReconnect(
                ownedHandoffEnded = false,
                ownedHandoff = false,
                ownsCurrentSession = false,
                ownsPendingStartSession = true,
            ),
        )
    }

    @Test
    fun duplicateLinkCloseWhileAnEmptyEndpointCandidateWaitsDoesNotCount() {
        // 候选端点是 A；同 peer 的 B 关闭不能替 A 累计失败。
        assertFalse(
            countsLinkCloseForAutoReconnect(
                ownedHandoffEnded = false,
                ownedHandoff = false,
                ownsCurrentSession = false,
                ownsPendingStartSession = false,
            ),
        )
    }
}
