package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #62：MIB3 突发后车机一条订阅都没有，必须在报告里有明确一行，而不是靠缺席推断。 */
class Mib3SubscriptionWatchdogTest {
    @Test
    fun noSubscriptionWithinWindowIsReportedOnce() {
        val watchdog = Mib3SubscriptionWatchdog(60_000)
        watchdog.arm(1_000)
        assertNull("窗口未到不报", watchdog.poll(59_000))
        val line = watchdog.poll(61_000)
        assertTrue(line != null && line.contains("60s") && line.contains("未发任何订阅"))
        assertNull("只报一次", watchdog.poll(120_000))
    }

    @Test
    fun anySubscriptionSuppressesTheWarning() {
        val watchdog = Mib3SubscriptionWatchdog(60_000)
        watchdog.arm(0)
        watchdog.observe(0x4157)
        assertNull(watchdog.poll(60_000))
    }

    @Test
    fun unarmedWatchdogNeverWarns() {
        val watchdog = Mib3SubscriptionWatchdog(1_000)
        assertNull(watchdog.poll(99_999))
        assertEquals("MIB3 突发未发送", watchdog.summary(99_999))
    }

    @Test
    fun summaryListsObservedIdsAndElapsedTime() {
        val watchdog = Mib3SubscriptionWatchdog(60_000)
        watchdog.arm(1_000)
        watchdog.observe(0x5000)
        watchdog.observe(0x4154)
        assertEquals(setOf(0x5000, 0x4154), watchdog.seenIds())
        assertEquals("突发后 500ms 收到订阅：0x4154 0x5000", watchdog.summary(1_500))
    }

    @Test
    fun summaryWithNoSubscriptionSaysNone() {
        val watchdog = Mib3SubscriptionWatchdog(60_000)
        watchdog.arm(0)
        assertEquals("突发后 109000ms 收到订阅：无", watchdog.summary(109_000))
    }

    @Test
    fun rearmingRestartsTheWindowWithoutDroppingSeenIds() {
        val watchdog = Mib3SubscriptionWatchdog(10_000)
        watchdog.arm(0)
        watchdog.observe(0x4157)
        watchdog.arm(5_000)
        assertTrue(watchdog.armed)
        assertEquals(setOf(0x4157), watchdog.seenIds())
        // 已见订阅：窗口到期也不告警。
        assertNull(watchdog.poll(16_000))
    }

    @Test
    fun subscriptionsBeforeArmingDoNotCount() {
        // P2 review finding: a subscription that arrived before the burst proves nothing about
        // whether the head unit received this burst, so it must not suppress the warning.
        val watchdog = Mib3SubscriptionWatchdog(60_000)
        watchdog.observe(0x4157)
        watchdog.observe(0x5000)
        assertEquals(emptySet<Int>(), watchdog.seenIds())
        watchdog.arm(0)
        val line = watchdog.poll(60_000)
        assertTrue("布防前的订阅不得抹掉告警：$line", line != null && line.contains("未发任何订阅"))
        assertEquals("突发后 60000ms 收到订阅：无", watchdog.summary(60_000))
    }
}
