package com.mcarplay.app.bt

import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：SETUP/RECORD、配对和热点加入失败都只结束时间线并关闭客户端，不经过
 * SessionController.disconnect。如果这些出口漏记账，连续快速失败会绕过熔断无限自动重连。
 */
class AutoReconnectOutcomeTest {
    @Test
    fun setupPairingAndJoinFailuresCountAsFastFailures() {
        listOf(
            DiagnosticCode.SETUP_FAILED,
            DiagnosticCode.PAIRING_FAILED,
            DiagnosticCode.WIFI_JOIN_FAILED,
            DiagnosticCode.BT_LINK_CLOSED,
            DiagnosticCode.WIFI_LOST,
            DiagnosticCode.CONNECTION_INCOMPLETE,
            DiagnosticCode.VIDEO_CLOSED,
        ).forEach { code ->
            assertTrue("$code 应计入自动重连熔断", countsAsAutoReconnectFailure(code, videoFrames = 0L, elapsedMs = 5_000L))
        }
    }

    @Test
    fun userStopAndNonLinkFailuresNeverCount() {
        assertFalse(countsAsAutoReconnectFailure(DiagnosticCode.USER_STOP, videoFrames = 0L, elapsedMs = 5_000L))
        assertFalse(countsAsAutoReconnectFailure(DiagnosticCode.SUPERSEDED, videoFrames = 0L, elapsedMs = 5_000L))
        assertFalse(countsAsAutoReconnectFailure(DiagnosticCode.CARWITH_FAILED, videoFrames = 0L, elapsedMs = 5_000L))
        assertFalse(countsAsAutoReconnectFailure(DiagnosticCode.CARWITH_BUSY, videoFrames = 0L, elapsedMs = 5_000L))
        assertFalse(countsAsAutoReconnectFailure(null, videoFrames = 0L, elapsedMs = 5_000L))
    }

    @Test
    fun framesOrLongSessionsAreNotFastFailures() {
        assertFalse(countsAsAutoReconnectFailure(DiagnosticCode.SETUP_FAILED, videoFrames = 1L, elapsedMs = 5_000L))
        assertFalse(
            countsAsAutoReconnectFailure(
                DiagnosticCode.SETUP_FAILED,
                videoFrames = 0L,
                elapsedMs = AUTO_RECONNECT_FAST_FAILURE_MAX_MS,
            ),
        )
        assertTrue(
            countsAsAutoReconnectFailure(
                DiagnosticCode.SETUP_FAILED,
                videoFrames = 0L,
                elapsedMs = AUTO_RECONNECT_FAST_FAILURE_MAX_MS - 1L,
            ),
        )
    }

    @Test
    fun peerComparisonIsCaseInsensitiveAndUnknownNeverConflicts() {
        assertTrue(sameKnownPeer("AA:BB:CC:DD:EE:01", "aa:bb:cc:dd:ee:01"))
        assertFalse(sameKnownPeer("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"))
        assertFalse(sameKnownPeer(null, "AA:BB:CC:DD:EE:01"))
        assertFalse(sameKnownPeer("AA:BB:CC:DD:EE:01", null))

        // 迟到链路 / 自动收摊：地址未知时不能当成“另一台车”，两边都已知且不同才冲突。
        assertTrue(peerUnknownOrMatches(null, "AA:BB:CC:DD:EE:01"))
        assertTrue(peerUnknownOrMatches("AA:BB:CC:DD:EE:01", null))
        assertTrue(peerUnknownOrMatches("AA:BB:CC:DD:EE:01", "aa:bb:cc:dd:ee:01"))
        assertFalse(peerUnknownOrMatches("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"))
    }
}
