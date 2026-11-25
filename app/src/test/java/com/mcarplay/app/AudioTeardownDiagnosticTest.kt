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
    fun j24ExactLivedLabelsAndShapesAtEveryDesignBoundary() {
        val expected =
            linkedMapOf(
                -1L to "未知间隔",
                0L to "0ms",
                7_999L to "7999ms",
                8_000L to "8000ms",
                9_999L to "9999ms",
                10_000L to "10.0s",
                12_000L to "12.0s",
                12_001L to "12.0s",
                19_999L to "20.0s",
                20_000L to "20.0s",
            )
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            expected.forEach { (lived, label) ->
                assertEquals(label, audioLivedLabel(lived))
                val shape =
                    when {
                        lived < 0 -> "间隔未知"
                        lived < 8_000 -> "$label，短于 ~10s 看门狗"
                        lived <= 12_000 -> "$label，符合 ~10s 看门狗形态"
                        else -> "$label，长于 ~10s 看门狗"
                    }
                assertEquals("AUDIO: 音频加入后会话被车机拆掉（链路协商未完成，$shape）——不记音频档位", audioLinkTeardownNote(lived))
            }
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun shortTeardownKeepsTheRealIntervalInsteadOfAWatchdogLabel() {
        listOf(16L, 559L, 7_999L).forEach { lived ->
            val note = audioLinkTeardownNote(lived)
            assertTrue(note, note.contains(audioLivedLabel(lived)))
            assertFalse("$lived ms 的拆链不能写成 ~10s 看门狗形态", note.contains("看门狗形态"))
            assertTrue(note.contains("短于 ~10s 看门狗"))
        }
    }

    @Test
    fun watchdogSizedTeardownKeepsTheWatchdogExplanationAndMeasuredInterval() {
        // 实测看门狗 10.17–10.18 s；窗口内的 8 s / 10.18 s / 12 s 都归为看门狗形态。
        listOf(8_000L, 10_180L, 12_000L).forEach { lived ->
            val note = audioLinkTeardownNote(lived)
            assertTrue(note, note.contains(audioLivedLabel(lived)))
            assertTrue(note, note.contains("符合 ~10s 看门狗形态"))
        }
    }

    @Test
    fun teardownOutsideTheWatchdogWindowIsNotCalledAWatchdog() {
        // 长于窗口（包括多分钟）的拆链不能再套用 ~10s 看门狗结论。
        listOf(12_001L, 120_000L).forEach { lived ->
            val note = audioLinkTeardownNote(lived)
            assertTrue(note, note.contains(audioLivedLabel(lived)))
            assertFalse("$lived ms 长于看门狗窗口，不能写成看门狗形态", note.contains("看门狗形态"))
            assertTrue(note.contains("长于 ~10s 看门狗"))
        }
    }

    @Test
    fun intervalsNearTheWindowAndBelowTenSecondsKeepSubSecondPrecision() {
        assertEquals("16ms", audioLivedLabel(16))
        assertEquals("559ms", audioLivedLabel(559))
        assertEquals("7999ms", audioLivedLabel(7_999))
        assertEquals("9999ms", audioLivedLabel(9_999))
        assertEquals("10.2s", audioLivedLabel(10_180))
        assertEquals("12.0s", audioLivedLabel(12_000))
        assertEquals("120.0s", audioLivedLabel(120_000))
        assertEquals("未知间隔", audioLivedLabel(-1))
    }

    @Test
    fun zeroIntervalIsStillExplicit() {
        val note = audioLinkTeardownNote(0)
        assertTrue(note.contains("0ms"))
        assertFalse(note.contains("看门狗形态"))
    }
}
