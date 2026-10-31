package com.mcarplay.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁屏观测的日志/时间线文案（只读观测，不改系统状态）。 */
class ScreenLockMonitorTest {
    private val lockedNotExempt =
        ScreenLockMonitor.PowerSnapshot(
            interactive = false,
            keyguardLocked = true,
            keyguardSecure = true,
            deviceIdle = false,
            batteryExempt = false,
        )

    @Test
    fun timelineNoteIsStableKeyValue() {
        assertEquals(
            "POWER SCREEN_OFF interactive=0 keyguard=1 secure=1 idle=0 batteryExempt=0",
            ScreenLockMonitor.timelineNote("SCREEN_OFF", lockedNotExempt),
        )
    }

    @Test
    fun screenOffWithoutExemptionWarnsAboutFreezing() {
        val line = ScreenLockMonitor.describe("SCREEN_OFF", lockedNotExempt)
        assertTrue(line, line.startsWith("手机息屏（"))
        assertTrue(line, line.contains("未豁免电池优化"))
    }

    @Test
    fun screenOffWithExemptionPointsAtWifiLock() {
        val line = ScreenLockMonitor.describe("SCREEN_OFF", lockedNotExempt.copy(batteryExempt = true))
        assertTrue(line, line.contains("低延迟锁"))
    }

    @Test
    fun unlockHasNoHint() {
        val line = ScreenLockMonitor.describe("USER_PRESENT", lockedNotExempt.copy(interactive = true, keyguardLocked = false))
        assertTrue(line, line.endsWith("）"))
    }

    @Test
    fun endWhileScreenOnIsNotReported() {
        assertNull(ScreenLockMonitor.endedWhileScreenOff(0L, 5_000L))
    }

    @Test
    fun endWhileScreenOffReportsElapsed() {
        assertEquals(
            "推流在息屏期间结束：afterScreenOffMs=1200",
            ScreenLockMonitor.endedWhileScreenOff(10_000L, 11_200L),
        )
    }
}
