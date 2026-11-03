package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectTimelineTest {
    private var now = 1_000L
    private val timeline = AutoConnectTimeline { now }

    @Test
    fun manualConnectionsWithoutAclLogNothing() {
        assertNull(timeline.mark(AutoConnectTimeline.Stage.SDP_REGISTERED))
    }

    @Test
    fun stagesAreMeasuredFromTheAclAndLoggedOnce() {
        timeline.arm(processAgeMs = 300)
        now += 420
        assertEquals(
            "AUTO-TIMING: iAP2 SDP 记录与监听就绪 +420ms（距车机 ACL）",
            timeline.mark(AutoConnectTimeline.Stage.SDP_REGISTERED),
        )
        now += 100
        assertNull(timeline.mark(AutoConnectTimeline.Stage.SDP_REGISTERED))
        assertEquals(
            "AUTO-TIMING: 车机连入 iAP2 +520ms（距车机 ACL）（目标车机）",
            timeline.mark(AutoConnectTimeline.Stage.INBOUND_IAP2, "（目标车机）"),
        )
    }

    @Test
    fun aNewAclRestartsTheClockAndForgetsEarlierStages() {
        timeline.arm(processAgeMs = null)
        timeline.mark(AutoConnectTimeline.Stage.SERVICE_STARTED)
        now += 10_000
        timeline.arm(processAgeMs = 60_000)
        now += 50
        assertEquals(
            "AUTO-TIMING: 前台服务收到自动启动 +50ms（距车机 ACL）",
            timeline.mark(AutoConnectTimeline.Stage.SERVICE_STARTED),
        )
    }

    @Test
    fun stagesAfterTheWindowAreNotAttributedToTheAcl() {
        timeline.arm(processAgeMs = 0)
        now += AutoConnectTimeline.WINDOW_MS + 1
        assertNull(timeline.mark(AutoConnectTimeline.Stage.INBOUND_IAP2))
        now -= 2
        assertNull(timeline.mark(AutoConnectTimeline.Stage.INBOUND_IAP2))
    }

    @Test
    fun armLineDistinguishesColdStartsFromARunningProcess() {
        assertTrue(timeline.arm(processAgeMs = 250).contains("进程由本次广播冷启动，250ms 前起"))
        assertTrue(timeline.arm(processAgeMs = 90_000).contains("进程已运行 90s"))
        assertTrue(timeline.arm(processAgeMs = null).contains("进程启动时刻未知"))
    }
}
