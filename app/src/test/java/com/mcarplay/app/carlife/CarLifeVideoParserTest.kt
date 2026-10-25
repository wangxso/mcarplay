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
    fun firstMbInSliceParsesExpGolomb() {
        assertEquals(0, firstMbInSlice(byteArrayOf(0x65, 0x80.toByte(), 1)))
        assertEquals(1, firstMbInSlice(byteArrayOf(0x41, 0x40, 0)))
        assertNull(firstMbInSlice(byteArrayOf(0x61)))
        assertNull(firstMbInSlice(byteArrayOf(0x67, 0x80.toByte())))
    }
}
