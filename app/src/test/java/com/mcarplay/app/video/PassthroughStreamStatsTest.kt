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
    fun sendTimingQueueAndFrameSizesAreTracked() {
        val stats = PassthroughStreamStats()
        stats.onFrameSent(listOf(idrSlice), keyFrame = true, nowMillis = 0, bytes = 90_000, writeMillis = 5, queueDepth = 1)
        stats.onFrameSent(listOf(pSlice), keyFrame = false, nowMillis = 50, bytes = 800, writeMillis = 70, queueDepth = 9)
        stats.onFrameSent(listOf(pSlice), keyFrame = false, nowMillis = 650, bytes = 600, writeMillis = 1, queueDepth = 0)
        stats.onFrameSent(listOf(idrSlice), keyFrame = true, nowMillis = 700, bytes = 120_000, writeMillis = 2, queueDepth = 0)
        assertEquals(1L, stats.onQueueDrop())
        val summary = stats.summary()
        assertTrue(summary, summary.contains("IDR=2（间隔 3–3 帧）"))
        assertTrue(summary, summary.contains("单帧最大 120000B IDR 最大 120000B"))
        assertTrue(summary, summary.contains("最大帧间隔 600ms（≥500ms 停顿 1 次） 最慢写入 70ms（≥50ms 1 次） 队列最深 9 队满丢帧 1"))
    }

    @Test
    fun carWithIdrResponseToKeyFrameRequestsIsMeasured() {
        val stats = PassthroughStreamStats()
        stats.onFrameSent(listOf(idrSlice), keyFrame = true, nowMillis = 0)
        // 第 1 次请求 400ms 后 CarWith 出了 IDR
        stats.onReceiverKeyFrameRequest(1_000, transcoding = false)
        stats.onFrameSent(listOf(idrSlice), keyFrame = true, nowMillis = 1_400)
        // 第 2 次请求后一直没有 IDR，第 3 次请求时判为未响应
        stats.onReceiverKeyFrameRequest(2_000, transcoding = false)
        stats.onReceiverKeyFrameRequest(4_000, transcoding = false)
        val summary = stats.summary()
        assertTrue(summary, summary.contains("要帧后 CarWith 出 IDR 中位 400ms 最小 400ms 最大 400ms（1 次）"))
        assertTrue(summary, summary.contains("1500ms 内未出 IDR 1 次"))
    }

    @Test
    fun transcodedIdrsResetTheRequestGap() {
        val stats = PassthroughStreamStats()
        stats.onTranscodedFrameSent(keyFrame = true, nowMillis = 5_000)
        assertEquals(250L, stats.onReceiverKeyFrameRequest(5_250, transcoding = true))
        val summary = stats.summary()
        assertTrue(summary, summary.contains("转码输出 帧=1 IDR=1；转码后距上个 IDR 中位 250ms"))
    }

    @Test
    fun parameterSetDescriptionNamesProfileAndEntropyMode() {
        val text = PassthroughStreamStats.describeParameterSets(sps, pps)
        assertTrue(text, text.startsWith("profile=66 constraint=0x80 level=31"))
        assertTrue(text, text.contains("| cabac=0"))
    }
}
