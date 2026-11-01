package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughStreamStatsTest {
    private val sps =
        byteArrayOf(0x67, 0x42, 0x80.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40, 0x16, 0xE9.toByte(), 0xA8.toByte(), 0x28, 0x30)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
    private val idrSlice = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())
    private val pSlice = byteArrayOf(0x41, 0x98.toByte())
    private val bSlice = byteArrayOf(0x41, 0x9C.toByte())

    @Test
    fun countsSliceTypesAndInbandParameterSets() {
        val stats = PassthroughStreamStats()
        stats.onFrameSent(listOf(byteArrayOf(0x09, 0x10), sps, pps, byteArrayOf(0x06, 0x05), idrSlice), keyFrame = true, nowMillis = 0)
        stats.onFrameSent(listOf(pSlice, pSlice), keyFrame = false, nowMillis = 50)
        stats.onFrameSent(listOf(bSlice), keyFrame = false, nowMillis = 100)
        val summary = stats.summary()
        assertTrue(summary, summary.contains("帧=3 IDR=1"))
        assertTrue(summary, summary.contains("slice I/P/B/其它=1/2/1/0"))
        assertTrue(summary, summary.contains("每帧最多 2 片（多片帧 1）"))
        assertTrue(summary, summary.contains("帧内 SPS/PPS=1/1 AUD=1 SEI=1"))
        assertTrue(summary, summary.contains("首个 IDR 组成=9:2 7:12 8:4 6:2 5:3"))
    }

    @Test
    fun keyFrameRequestGapIsMeasuredFromLastSentIdr() {
        val stats = PassthroughStreamStats()
        assertNull(stats.onReceiverKeyFrameRequest(10, transcoding = false))
        stats.onFrameSent(listOf(idrSlice), keyFrame = true, nowMillis = 1_000)
        assertEquals(700L, stats.onReceiverKeyFrameRequest(1_700, transcoding = false))
        stats.onFrameSent(listOf(idrSlice), keyFrame = true, nowMillis = 2_000)
        assertEquals(300L, stats.onReceiverKeyFrameRequest(2_300, transcoding = false))
        stats.onReceiverKeyFrameRequest(2_400, transcoding = true)
        val summary = stats.summary()
        assertTrue(summary, summary.contains("车机要帧 4 次（首个 IDR 前 1、转码后 1）"))
        assertTrue(summary, summary.contains("中位 700ms 最小 300ms 最大 700ms（2 次）"))
    }

    @Test
    fun parameterSetDescriptionNamesProfileAndEntropyMode() {
        val text = PassthroughStreamStats.describeParameterSets(sps, pps)
        assertTrue(text, text.startsWith("profile=66 constraint=0x80 level=31"))
        assertTrue(text, text.contains("| cabac=0"))
    }
}
