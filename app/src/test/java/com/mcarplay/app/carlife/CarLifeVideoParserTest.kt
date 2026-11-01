package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeVideoParserTest {
    private val sps =
        byteArrayOf(
            0x67,
            0x42,
            0x80.toByte(),
            0x1F,
            0xDA.toByte(),
            0x01,
            0x40,
            0x16,
            0xE9.toByte(),
            0xA8.toByte(),
            0x28,
            0x30,
        )

    @Test
    fun sliceWithFirstMbZeroStartsNextAccessUnitAtSameTimestamp() {
        val parser = CarLifeVideoParser()
        parser.push(100, byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte(), 1, 2))
        val frames = parser.push(100, byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte(), 3, 4))
        assertEquals(1, frames.size)
        assertEquals(100L, frames.first().timestamp)
        assertTrue(frames.first().keyFrame)
        assertTrue(frames[0].data.contentEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte(), 1, 2)))
        assertTrue(
            parser.flush().single().data.contentEquals(
                byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte(), 3, 4),
            ),
        )
    }

    @Test
    fun newTimestampFlushesPreviousAccessUnit() {
        val parser = CarLifeVideoParser()
        parser.push(1, byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte()))
        val frames =
            parser.push(2, byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte())) +
                parser.flush()
        assertEquals(2, frames.size)
        assertEquals(1L, frames.first().timestamp)
        assertTrue(frames.first().keyFrame)
    }

    @Test
    fun parameterSetsAreRetainedForCarPlayConfig() {
        val parser = CarLifeVideoParser()
        parser.push(
            10,
            byteArrayOf(0, 0, 0, 1) +
                sps +
                byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x06, 0xE2.toByte()) +
                byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte(), 2, 3),
        )
        val frames = parser.flush()
        val frame = frames.single()
        assertTrue(frame.isCodecConfig)
        assertTrue(frame.keyFrame)
        assertEquals(1280 to 720, frame.dimensions())
    }

    @Test
    fun audStartsNextAccessUnitAtSameTimestamp() {
        val parser = CarLifeVideoParser()
        parser.push(5, byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte()))
        val frames =
            parser.push(
                5,
                byteArrayOf(0, 0, 0, 1, 0x09, 0x10) +
                    byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte()),
            )
        assertEquals(1, frames.size)
        assertTrue(frames.single().keyFrame)
    }

    @Test
    fun unknownNalIsStillForwardedWithoutConfig() {
        val parser = CarLifeVideoParser()
        val frames = parser.push(7, byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte()))
        assertTrue(frames.isEmpty())
        val flushed = parser.flush().single()
        assertFalse(flushed.isCodecConfig)
        assertFalse(flushed.keyFrame)
    }

    @Test
    fun fragmentedNalIsCompletedBeforeItIsDelivered() {
        val parser = CarLifeVideoParser()
        parser.push(9, byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte(), 1))
        val frames =
            parser.push(9, byteArrayOf(2, 3, 0, 0, 0, 1, 0x41, 0x80.toByte())) +
                parser.flush()
        assertEquals(2, frames.size)
        assertEquals(9, frames[0].data.size)
        assertTrue(frames[0].keyFrame)
    }

    @Test
    fun parameterSetAfterSliceStartsConfigurationAccessUnit() {
        val parser = CarLifeVideoParser()
        parser.push(11, byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte()))
        val frames =
            parser.push(
                11,
                byteArrayOf(0, 0, 0, 1) +
                    sps +
                    byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x06, 0xE2.toByte()),
            )
        assertEquals(1, frames.size)
        assertTrue(frames.single().keyFrame)
        assertNull(frames.single().dimensions())
        assertEquals(1280 to 720, parser.flush().single().dimensions())
    }

    @Test
    fun externalFrameTimestampConvertsUnixSecondsToMicroseconds() {
        val parser = CarLifeVideoParser()
        parser.push(1, byteArrayOf(0, 0, 0, 1, 0x41, 0x80.toByte()))
        val frame = parser.flush().single()
        assertEquals(1_000_000L, frame.toExternalVideoFrame().timestampUs)
    }

    @Test
    fun externalFrameCarriesCachedParameterSets() {
        // 转码器中途起步时，IDR 不内联 PPS 也能从帧携带的解析器缓存补齐
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
        val unit = CarLifeVideoAccessUnit(1, byteArrayOf(0, 0, 0, 1, 0x65, 0x80.toByte()), true, sps, pps)
        val frame = unit.toExternalVideoFrame()
        assertTrue(frame.sps!!.contentEquals(sps))
        assertTrue(frame.pps!!.contentEquals(pps))
    }

    @Test
    fun baselineSpsSummaryKeepsDimensions() {
        val info = parseSps(sps)!!
        assertEquals(66, info.profileIdc)
        assertEquals(0x80, info.constraintFlags)
        assertEquals(31, info.levelIdc)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertEquals(1280 to 720, spsDimensions(sps))
    }

    @Test
    fun highProfileSpsReportsVuiTimingAndReorder() {
        // High 3.1、1280×720、VUI 带 timing（60/2 = 30fps，含 00 00 00 01 需 emulation prevention）与 bitstream_restriction
        val nal =
            H264BitWriter()
                .bits(100, 8)
                .bits(0, 8)
                .bits(31, 8)
                .ue(0)
                .ue(1)
                .ue(0)
                .ue(0)
                .bit(0)
                .bit(0)
                .ue(0)
                .ue(0)
                .ue(0)
                .ue(1)
                .bit(0)
                .ue(79)
                .ue(44)
                .bit(1)
                .bit(1)
                .bit(0)
                .bit(1)
                .bit(0)
                .bit(0)
                .bit(1)
                .bits(5, 3)
                .bit(1)
                .bit(0)
                .bit(0)
                .bit(1)
                .bits(1, 32)
                .bits(60, 32)
                .bit(0)
                .bit(0)
                .bit(0)
                .bit(0)
                .bit(1)
                .bit(1)
                .ue(0)
                .ue(0)
                .ue(0)
                .ue(0)
                .ue(2)
                .ue(3)
                .nal(0x67)
        val info = parseSps(nal)!!
        assertEquals(100, info.profileIdc)
        assertEquals(1280 to 720, info.width to info.height)
        assertTrue(info.vuiParsed)
        assertEquals(true, info.fullRange)
        assertEquals(30.0, info.timingFps!!, 0.001)
        assertEquals(2, info.maxNumReorderFrames)
        assertEquals(3, info.maxDecFrameBuffering)
        assertTrue(info.describe().contains("reorder=2 dpb=3"))
    }

    @Test
    fun ppsReportsEntropyCodingAndTransform8x8() {
        val high =
            H264BitWriter()
                .ue(0)
                .ue(0)
                .bit(1)
                .bit(0)
                .ue(0)
                .ue(0)
                .ue(0)
                .bit(0)
                .bits(0, 2)
                .se(0)
                .se(0)
                .se(-2)
                .bit(1)
                .bit(0)
                .bit(0)
                .bit(1)
                .bit(0)
                .se(-2)
                .nal(0x68)
        val highInfo = parsePps(high)!!
        assertTrue(highInfo.cabac)
        assertEquals(true, highInfo.transform8x8)
        val baseline =
            H264BitWriter()
                .ue(0)
                .ue(0)
                .bit(0)
                .bit(0)
                .ue(0)
                .ue(0)
                .ue(0)
                .bit(0)
                .bits(0, 2)
                .se(0)
                .se(0)
                .se(0)
                .bit(1)
                .bit(0)
                .bit(0)
                .nal(0x68)
        val baselineInfo = parsePps(baseline)!!
        assertFalse(baselineInfo.cabac)
        assertNull(baselineInfo.transform8x8)
        assertNull(parsePps(sps))
    }

    @Test
    fun sliceTypeReadsSecondExpGolomb() {
        assertEquals(7, sliceType(byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())))
        assertEquals(5, sliceType(byteArrayOf(0x41, 0x98.toByte())))
        assertEquals(6, sliceType(byteArrayOf(0x41, 0x9C.toByte())))
        assertNull(sliceType(byteArrayOf(0x67, 0x80.toByte())))
    }

    @Test
    fun firstMbInSliceParsesExpGolomb() {
        assertEquals(0, firstMbInSlice(byteArrayOf(0x65, 0x80.toByte(), 1)))
        assertEquals(1, firstMbInSlice(byteArrayOf(0x41, 0x40, 0)))
        assertNull(firstMbInSlice(byteArrayOf(0x61)))
        assertNull(firstMbInSlice(byteArrayOf(0x67, 0x80.toByte())))
    }
}
