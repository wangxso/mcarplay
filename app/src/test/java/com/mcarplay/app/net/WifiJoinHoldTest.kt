package com.mcarplay.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiJoinHoldTest {
    @Test
    fun pickerShownAfterRequestCountsAsPendingChoice() {
        assertTrue(WifiCarPlayConnector.isUserChoicePending(uiPausedAtMs = 1_015, requestedAtMs = 1_000))
    }

    @Test
    fun uiLeftBeforeRequestOrStillInFrontIsNotPendingChoice() {
        assertFalse(
            "Already in another app: cannot tell a hung request from a picker",
            WifiCarPlayConnector.isUserChoicePending(900, 1_000),
        )
        assertFalse(WifiCarPlayConnector.isUserChoicePending(null, 1_000))
    }

    @Test
    fun firstWindowExtendsOnlyWhileChoiceIsPendingAndWithinLimit() {
        assertTrue(WifiCarPlayConnector.shouldKeepWaiting(now = 4_000, deadline = 5_000, holdDeadline = 40_000, userChoicePending = false))
        assertFalse(WifiCarPlayConnector.shouldKeepWaiting(now = 5_000, deadline = 5_000, holdDeadline = 40_000, userChoicePending = false))
        assertTrue(WifiCarPlayConnector.shouldKeepWaiting(now = 12_000, deadline = 5_000, holdDeadline = 40_000, userChoicePending = true))
        assertFalse(WifiCarPlayConnector.shouldKeepWaiting(now = 40_000, deadline = 5_000, holdDeadline = 40_000, userChoicePending = true))
    }
}
