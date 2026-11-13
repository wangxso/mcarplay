package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #55：音频加入后 16–559 ms 就被车机拆链，旧日志固定写成「~10s 看门狗形态」，
 * 且存活时间按整秒取整显示成「0s」，会把排查引向错误方向。
 */
class AudioTeardownDiagnosticTest {
    @Test
    fun shortTeardownKeepsTheRealIntervalInsteadOfAWatchdogLabel() {
        val note = audioLinkTeardownNote(559)
        assertTrue(note.contains("559ms"))
        assertFalse("16–559 ms 的拆链不能写成 ~10s 看门狗形态", note.contains("看门狗形态"))
        assertTrue(note.contains("短于 ~10s 看门狗"))
    }

    @Test
    fun watchdogSizedTeardownKeepsTheWatchdogExplanation() {
        assertTrue(audioLinkTeardownNote(10_180).contains("~10s 看门狗形态"))
    }

    @Test
    fun subSecondDurationsAreNotRoundedToZeroSeconds() {
        assertEquals("16ms", audioLivedLabel(16))
        assertEquals("559ms", audioLivedLabel(559))
        assertEquals("1s", audioLivedLabel(1_000))
        assertEquals("6s", audioLivedLabel(6_500))
        assertEquals("未知间隔", audioLivedLabel(-1))
    }

    @Test
    fun zeroIntervalIsStillExplicit() {
        val note = audioLinkTeardownNote(0)
        assertTrue(note.contains("0ms"))
        assertFalse(note.contains("看门狗形态"))
    }
}
