package com.mcarplay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCloseAccountingTest {
    @Test
    fun secondLinkCloseDoesNotCountWhileTheHandoffIsHealthy() {
        // A 的交接健康（ownedHandoff=true）；同 peer 的另一条 RFCOMM 链路关闭时
        // ownedHandoff/ownedHandoffEnded/ownsCurrentSession 均为 false。
        assertFalse(countsLinkCloseForAutoReconnect(ownedHandoffEnded = false, ownedHandoff = false, ownsCurrentSession = false))
    }

    @Test
    fun currentHandoffOwnerCloseCounts() {
        assertTrue(countsLinkCloseForAutoReconnect(ownedHandoffEnded = false, ownedHandoff = true, ownsCurrentSession = false))
    }

    @Test
    fun ownerCloseAfterCancellationStillCounts() {
        assertTrue(countsLinkCloseForAutoReconnect(ownedHandoffEnded = true, ownedHandoff = true, ownsCurrentSession = false))
    }

    @Test
    fun closeOfLinkThatDeliveredTheCurrentSessionCounts() {
        // 推流阶段交接所有权已释放，但该链路就是当前会话的承载链路。
        assertTrue(countsLinkCloseForAutoReconnect(ownedHandoffEnded = false, ownedHandoff = false, ownsCurrentSession = true))
    }
}
