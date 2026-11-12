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
}
