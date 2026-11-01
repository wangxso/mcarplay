package com.mcarplay.app.video

import com.mcarplay.app.airplay.StreamFrameCipher
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
    fun droppedInputWaitsForNextIdr() {
        val resync = IdrResync(requestIntervalMillis = 1_000L)
        assertTrue(resync.admit(keyFrame = false))
        // 输入缓冲繁忙丢了一帧：之后的 P 帧缺参考，不再送解码器
        resync.discontinuity()
        assertTrue(resync.requestDue(0L))
        assertFalse(resync.admit(keyFrame = false))
        assertFalse(resync.admit(keyFrame = false))
        // IDR 到达后恢复，P 帧照常放行
        assertTrue(resync.admit(keyFrame = true))
        assertTrue(resync.admit(keyFrame = false))
        assertFalse(resync.awaitingIdr)
    }

    @Test
    fun sourceKeyFrameRequestsAreThrottledWhileWaiting() {
        val resync = IdrResync(requestIntervalMillis = 1_000L)
        // 没断流时不打扰 CarWith
        assertFalse(resync.requestDue(0L))
        resync.discontinuity()
        assertTrue(resync.requestDue(100L))
        assertFalse(resync.requestDue(600L))
        // 一直等不到 IDR：每秒再要一次
        assertTrue(resync.requestDue(1_100L))
        assertTrue(resync.admit(keyFrame = true))
        assertFalse(resync.requestDue(5_000L))
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

    @Test
    fun midStreamIdrWithoutInlinePpsUsesParserCache() {
        // 中途切换转码：后续 IDR 只内联 SPS（或都不内联），PPS 取解析器缓存
        val spsOnlyIdr = byteArrayOf(0, 0, 0, 1, 0x67, 9, 9, 0, 0, 0, 1, 0x65, 7)
        val sets =
            CarWithTranscoder.decoderParameterSets(
                ExternalVideoFrame(spsOnlyIdr, true, 0, sps = byteArrayOf(0x67, 1), pps = byteArrayOf(0x68, 8)),
            )
        // 内联的 SPS 优先于缓存
        assertArrayEquals(byteArrayOf(0x67, 9, 9), sets!!.first)
        assertArrayEquals(byteArrayOf(0x68, 8), sets.second)

        val bareIdr = byteArrayOf(0, 0, 0, 1, 0x65, 7)
        val cached =
            CarWithTranscoder.decoderParameterSets(
                ExternalVideoFrame(bareIdr, true, 0, sps = byteArrayOf(0x67, 1), pps = byteArrayOf(0x68, 2)),
            )
        assertArrayEquals(byteArrayOf(0x67, 1), cached!!.first)
        assertArrayEquals(byteArrayOf(0x68, 2), cached.second)
    }

    @Test
    fun decoderWaitsWhenNoParameterSetIsKnown() {
        val bareIdr = byteArrayOf(0, 0, 0, 1, 0x65, 7)
        assertNull(CarWithTranscoder.decoderParameterSets(ExternalVideoFrame(bareIdr, true, 0)))
        assertNull(CarWithTranscoder.decoderParameterSets(ExternalVideoFrame(bareIdr, true, 0, sps = byteArrayOf(0x67, 1))))
    }

    @Test
    fun sourceSizeChangeIsTrackedApartFromStreamSize() {
        // 帕萨特：车机流 1280×720，CarWith 1920×1140；转码后画布固定为流尺寸，
        // 格式变化必须按源尺寸判断，否则每帧都被当成新格式写日志
        val sender =
            VideoSender(
                "127.0.0.1",
                1,
                StreamFrameCipher.ChaCha(ByteArray(32)),
                1280,
                720,
                log = {},
                externalVideoSource =
                    object : ExternalVideoSource {
                        override fun requestKeyFrame(): Boolean = true
                    },
            )
        assertTrue(sender.isNewSourceSize(1920, 1140))
        sender.setVideoDimensions(1920, 1140)
        assertFalse(sender.isNewSourceSize(1920, 1140))
        assertTrue(sender.isNewSourceSize(1280, 720))
    }
}
