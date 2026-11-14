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

    /**
     * bug #66：保时捷退化 0x1d01 形态下每轮都卡在 iAP2 协商超时（入站 60s、出站 90s），
     * elapsedMs 必然越过 [AUTO_RECONNECT_FAST_FAILURE_MAX_MS]，旧的时长门限会把它们
     * 全部放过，自动重连于是无限重试。端点未交付的轮次从未进入界面交接，
     * 「主屏归车机导致长时间零帧」的例外不适用，因此跳过时长门限仍计入。
     */
    @Test
    fun preEndpointFailuresCountEvenWhenTheRoundOutlivedTheFastFailureWindow() {
        listOf(60_000L, 90_000L, 224_000L).forEach { elapsed ->
            assertTrue(
                "端点前失败 elapsed=${elapsed}ms 应计入熔断",
                countsAsAutoReconnectFailure(
                    DiagnosticCode.BT_LINK_CLOSED,
                    videoFrames = 0L,
                    elapsedMs = elapsed,
                    beforeEndpoint = true,
                ),
            )
        }
        assertTrue(
            countsAsAutoReconnectFailure(
                DiagnosticCode.CONNECTION_INCOMPLETE,
                videoFrames = 0L,
                elapsedMs = 90_000L,
                beforeEndpoint = true,
            ),
        )
    }

    @Test
    fun preEndpointStillRequiresZeroFramesAndALinkFailureCode() {
        // 时长放宽不等于放弃其它判据：出过帧的轮次不是「快速失败」，
        // 用户停止也不会被算成车机问题。
        assertFalse(
            countsAsAutoReconnectFailure(
                DiagnosticCode.BT_LINK_CLOSED,
                videoFrames = 1L,
                elapsedMs = 90_000L,
                beforeEndpoint = true,
            ),
        )
        assertFalse(
            countsAsAutoReconnectFailure(
                DiagnosticCode.USER_STOP,
                videoFrames = 0L,
                elapsedMs = 90_000L,
                beforeEndpoint = true,
            ),
        )
        assertFalse(
            countsAsAutoReconnectFailure(
                DiagnosticCode.SUPERSEDED,
                videoFrames = 0L,
                elapsedMs = 90_000L,
                beforeEndpoint = true,
            ),
        )
        assertFalse(
            countsAsAutoReconnectFailure(null, videoFrames = 0L, elapsedMs = 90_000L, beforeEndpoint = true),
        )
    }

    /**
     * 端点已交付的轮次必须保持原有窄口：长时间停在原车界面（倒车影像等）后正常结束，
     * 不能因为放宽预端点轮次而被误判成「车机在无限失败」。
     */
    @Test
    fun postEndpointRoundsKeepTheFastFailureWindow() {
        assertFalse(
            countsAsAutoReconnectFailure(
                DiagnosticCode.BT_LINK_CLOSED,
                videoFrames = 0L,
                elapsedMs = AUTO_RECONNECT_FAST_FAILURE_MAX_MS,
            ),
        )
        assertFalse(
            countsAsAutoReconnectFailure(
                DiagnosticCode.SETUP_FAILED,
                videoFrames = 0L,
                elapsedMs = 224_000L,
                beforeEndpoint = false,
            ),
        )
        assertTrue(
            countsAsAutoReconnectFailure(
                DiagnosticCode.BT_LINK_CLOSED,
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
