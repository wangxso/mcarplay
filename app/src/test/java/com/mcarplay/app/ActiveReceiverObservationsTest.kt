package com.mcarplay.app

import com.mcarplay.app.compat.ReceiverObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveReceiverObservationsTest {
    @Test
    fun uniqueLinkCanIdentifyAutoSelectedReceiverButCompetingOrUnknownLinksCannot() {
        val tracker = ActiveReceiverObservations()
        val first = ReceiverObservation("receiver-a", 1, 1)
        val second = ReceiverObservation("receiver-b", 1, 2)
        tracker.begin(first)
        assertEquals(first, tracker.single())
        tracker.begin(second)
        assertNull(tracker.single())
        tracker.end(second)
        assertEquals(first, tracker.single())
        tracker.begin(null)
        assertNull(tracker.single())
        tracker.end(null)
        assertEquals(first, tracker.single())
        tracker.end(first)
        assertNull(tracker.single())
    }

    @Test
    fun closedObservationIsNotActiveEvenWhenDetectionStillCallsItCurrent() {
        val tracker = ActiveReceiverObservations()
        val first = ReceiverObservation("receiver-a", 1, 1)
        val second = ReceiverObservation("receiver-b", 1, 2)
        tracker.begin(first)
        tracker.begin(second)
        assertTrue(tracker.isActive(first))
        tracker.end(first)
        assertFalse(tracker.isActive(first))
        assertTrue(tracker.isActive(second))
        tracker.end(second)
        assertFalse(tracker.isActive(second))
        // 全部链路关闭时，身份未知的 observation 也不能被当成存活。
        assertFalse(tracker.isActive(null))
        tracker.begin(null)
        assertTrue(tracker.isActive(null))
    }

    /**
     * 回归（PR #67 审查 P2）：`single() != null` 不能用来判断「还有别的链路活着」。
     * 只要有未知身份的链路、或剩下两条以上已知链路，[single] 就返回 null，
     * 那会被误读成「没有竞争者」而替一条仍在协商的链路收尾并记一次失败。
     */
    @Test
    fun hasAnyStaysTrueWhereSingleReportsNoUniqueReceiver() {
        val tracker = ActiveReceiverObservations()
        val first = ReceiverObservation("receiver-a", 1, 1)
        val second = ReceiverObservation("receiver-b", 1, 2)

        assertFalse("没有链路时不该说还有链路", tracker.hasAny())
        assertEquals(0, tracker.count())

        tracker.begin(first)
        assertTrue(tracker.hasAny())
        assertEquals(first, tracker.single())
        assertEquals(1, tracker.count())

        // 两条已知链路：single() 为 null，但确实还有链路活着。
        tracker.begin(second)
        assertNull(tracker.single())
        assertTrue("两条已知链路仍算有链路存活", tracker.hasAny())
        assertEquals(2, tracker.count())

        // 失败的那条移除后只剩它自己：single() 能唯一识别，但确实仍有一条链路存活。
        tracker.end(first)
        assertEquals(second, tracker.single())
        assertTrue(tracker.hasAny())
        assertEquals(1, tracker.count())

        // 身份未知的链路：single() 为 null，但确实还有链路（真正的误判来源）。
        tracker.begin(null)
        assertNull("有未知身份链路时 single() 识别不出唯一接收端", tracker.single())
        assertTrue("未知身份的链路也算存活", tracker.hasAny())
        assertEquals(2, tracker.count())

        tracker.end(null)
        tracker.end(second)
        assertNull(tracker.single())
        assertFalse(tracker.hasAny())
        assertEquals(0, tracker.count())
    }
}
