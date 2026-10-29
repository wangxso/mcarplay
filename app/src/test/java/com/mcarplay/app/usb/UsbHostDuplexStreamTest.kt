package com.mcarplay.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque

class UsbHostDuplexStreamTest {
    private class FakePipe : BulkPipe {
        /** 空数组表示一次超时。 */
        val reads = ArrayDeque<ByteArray>()
        val written = ByteArrayOutputStream()
        val writeSizes = mutableListOf<Int>()
        var attached = true
        var failWrites = false
        var closeCount = 0

        override fun bulkIn(
            buffer: ByteArray,
            timeoutMillis: Int,
        ): Int {
            val next = reads.pollFirst() ?: return -1
            if (next.isEmpty()) return -1
            next.copyInto(buffer)
            return next.size
        }

        override fun bulkOut(
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): Int {
            if (failWrites) return -1
            written.write(buffer, offset, length)
            writeSizes += length
            return length
        }

        override fun isAttached() = attached

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun readSkipsTimeoutsUntilDataArrives() {
        val pipe = FakePipe()
        pipe.reads.addAll(listOf(ByteArray(0), ByteArray(0), byteArrayOf(0xFF.toByte(), 0x5A)))
        val buffer = ByteArray(16)

        assertEquals(2, UsbHostDuplexStream(pipe).read(buffer))
        assertEquals(0x5A, buffer[1].toInt())
    }

    @Test
    fun readEndsWhenDeviceDetached() {
        val pipe = FakePipe().apply { attached = false }
        assertEquals(-1, UsbHostDuplexStream(pipe).read(ByteArray(4)))
    }

    @Test
    fun writeSplitsIntoChunks() {
        val pipe = FakePipe()
        val bytes = ByteArray(10) { it.toByte() }

        UsbHostDuplexStream(pipe, maxWriteChunk = 4).write(bytes)

        assertEquals(listOf(4, 4, 2), pipe.writeSizes)
        assertArrayEquals(bytes, pipe.written.toByteArray())
    }

    @Test
    fun failedWriteThrows() {
        val pipe = FakePipe().apply { failWrites = true }
        assertTrue(runCatching { UsbHostDuplexStream(pipe).write(byteArrayOf(1)) }.exceptionOrNull() is IOException)
    }

    @Test
    fun closeIsIdempotentAndStopsReads() {
        val pipe = FakePipe()
        val stream = UsbHostDuplexStream(pipe)
        stream.close()
        stream.close()

        assertEquals(1, pipe.closeCount)
        assertEquals(-1, stream.read(ByteArray(4)))
    }
}
