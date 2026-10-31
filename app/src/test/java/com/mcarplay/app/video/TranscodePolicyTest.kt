package com.mcarplay.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodePolicyTest {
    @Test
    fun transcodesOnlyWhenSourceDiffersFromStream() {
        // 帕萨特 2025：CarWith 1920×1140，车机流 1280×720
        assertTrue(TranscodePolicy.needsTranscode(1920, 1140, 1280, 720))
        assertFalse(TranscodePolicy.needsTranscode(1280, 720, 1280, 720))
        // 尚未收到 CarWith 首帧
        assertFalse(TranscodePolicy.needsTranscode(0, 0, 1280, 720))
    }

    @Test
    fun encoderFpsRespectsReceiverMaxFps() {
        assertEquals(40, TranscodePolicy.cappedFps(targetFps = 60, receiverMaxFps = 40))
        assertEquals(30, TranscodePolicy.cappedFps(targetFps = 30, receiverMaxFps = 40))
        assertEquals(60, TranscodePolicy.cappedFps(targetFps = 60, receiverMaxFps = 0))
    }

    @Test
    fun passatKeyFrameCadenceIsAStorm() {
        // 帕萨特：约每 300ms 一次
        val storm = KeyFrameStorm()
        val results = (0 until 4).map { storm.record(1_000L + it * 300L) }
        assertEquals(listOf(false, false, false, true), results)
    }

    @Test
    fun occasionalKeyFrameRequestsAreNotAStorm() {
        // 宝马 iD9 起步一次；或每秒一次的零星请求
        val storm = KeyFrameStorm()
        assertFalse(storm.record(0L))
        assertFalse(storm.record(1_000L))
        assertFalse(storm.record(2_000L))
        assertFalse(storm.record(3_000L))
        assertFalse(storm.record(4_000L))
    }

    @Test
    fun annexBSplitHandlesBothStartCodes() {
        val bytes = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5)
        val units = AnnexB.split(bytes)
        assertEquals(3, units.size)
        assertArrayEquals(byteArrayOf(0x67, 1, 2), units[0])
        assertArrayEquals(byteArrayOf(0x68, 3), units[1])
        assertArrayEquals(byteArrayOf(0x65, 4, 5), units[2])
    }

    @Test
    fun parameterSetsComeFromKeyFrameAccessUnit() {
        val keyFrame = byteArrayOf(0, 0, 0, 1, 0x67, 9, 9, 0, 0, 0, 1, 0x68, 8, 0, 0, 0, 1, 0x65, 7)
        val sets = CarWithTranscoder.parameterSets(keyFrame)
        assertArrayEquals(byteArrayOf(0x67, 9, 9), sets!!.first)
        assertArrayEquals(byteArrayOf(0x68, 8), sets.second)
        // 普通 P 帧没有参数集，解码器不能据此创建
        assertNull(CarWithTranscoder.parameterSets(byteArrayOf(0, 0, 0, 1, 0x41, 1)))
    }
}
