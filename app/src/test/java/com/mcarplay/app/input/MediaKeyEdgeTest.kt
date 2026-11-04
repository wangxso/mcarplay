package com.mcarplay.app.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** 方向盘媒体键的边沿判定：状态式报告 → 一次按键一次触发，长按按间隔连发。 */
class MediaKeyEdgeTest {
    private val next = 0xB5
    private val previous = 0xB6

    @Test
    fun heldReportFiresOnce() {
        val edge = MediaKeyEdge()
        // 本田实测：按住「下一首」以 ~10Hz 连推 0x04
        assertEquals(listOf(next), edge.onReport(listOf(next), 1_000L))
        for (t in 1_100L..2_400L step 100L) {
            assertEquals("t=$t 按住期间不应重复切歌", emptyList<Int>(), edge.onReport(listOf(next), t))
        }
    }

    @Test
    fun releaseThenPressFiresAgain() {
        val edge = MediaKeyEdge()
        assertEquals(listOf(next), edge.onReport(listOf(next), 0L))
        assertEquals(emptyList<Int>(), edge.onReport(emptyList(), 50L))
        assertEquals(listOf(next), edge.onReport(listOf(next), 100L))
    }

    @Test
    fun longHoldRepeatsAfterInterval() {
        val edge = MediaKeyEdge(holdRepeatMillis = 1_500L)
        assertEquals(listOf(next), edge.onReport(listOf(next), 10_000L))
        assertEquals(emptyList<Int>(), edge.onReport(listOf(next), 11_400L))
        assertEquals(listOf(next), edge.onReport(listOf(next), 11_500L))
        // 连发后重新计时
        assertEquals(emptyList<Int>(), edge.onReport(listOf(next), 12_900L))
        assertEquals(listOf(next), edge.onReport(listOf(next), 13_000L))
    }

    @Test
    fun switchingKeysWithoutReleaseFiresTheNewKeyOnly() {
        val edge = MediaKeyEdge()
        assertEquals(listOf(next), edge.onReport(listOf(next), 0L))
        // Array 报告直接从「下一首」变成「上一首」，中间没有 0x00
        assertEquals(listOf(previous), edge.onReport(listOf(previous), 200L))
        assertEquals(listOf(next), edge.onReport(listOf(previous, next), 300L))
    }

    @Test
    fun resetForgetsHeldState() {
        val edge = MediaKeyEdge()
        assertEquals(listOf(next), edge.onReport(listOf(next), 0L))
        edge.reset()
        assertEquals(listOf(next), edge.onReport(listOf(next), 10L))
    }

    @Test
    fun bitmapReportThroughDescriptorFiresOncePerPress() {
        // 本田 HFT 位图描述符：bit2 = 下一首
        val descriptor =
            "05 0c 09 01 a1 01 15 00 25 01 75 01 95 05 09 b0 09 b1 09 b5 09 b6 09 b9 81 02 75 03 95 01 81 03 c0"
                .split(" ")
                .map { it.toInt(16).toByte() }
                .toByteArray()
        val map = ConsumerKeys.parseDescriptor(descriptor)!!
        val edge = MediaKeyEdge()
        val fired = ArrayList<Int>()
        listOf<Byte>(0x04, 0x04, 0x04, 0x00, 0x08, 0x00).forEachIndexed { index, report ->
            fired += edge.onReport(map.usagesFor(byteArrayOf(report)), index * 100L)
        }
        assertEquals(listOf(next, previous), fired)
    }
}
