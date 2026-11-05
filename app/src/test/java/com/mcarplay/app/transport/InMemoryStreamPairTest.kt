package com.mcarplay.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * 内存回环流对：读端只认 close，不认写线程的存活。
 *
 * 回归：旧实现基于 PipedInputStream，模拟车机线程发完最后一帧退出后，对端读线程报
 * `Write end dead`，链路被误判断开（CI 上 Iap2SenderSessionTest 偶发失败）。
 */
class InMemoryStreamPairTest {
    @Test(timeout = 5_000)
    fun readerKeepsWaitingAfterWriterThreadExits() {
        val pair = InMemoryStreamPair()
        Thread({ pair.sideB.write(byteArrayOf(1, 2, 3)) }, "short-lived-writer").apply {
            start()
            join()
        }

        val buffer = ByteArray(16)
        assertEquals(3, pair.sideA.read(buffer))
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.copyOf(3))

        // 写线程已退出、缓冲区已读空：读端必须继续阻塞，等到下一次写入
        val second = AtomicReference<Any?>()
        val reader =
            Thread({
                second.set(runCatching { pair.sideA.read(buffer) }.fold({ it }, { it }))
            }, "reader").apply { start() }
        reader.join(1_200)
        assertTrue("读端不应在写线程退出后结束：${second.get()}", reader.isAlive)

        pair.sideB.write(byteArrayOf(4))
        reader.join(1_000)
        assertEquals(1, second.get())
        assertEquals(4.toByte(), buffer[0])
    }

    @Test(timeout = 5_000)
    fun closeDeliversPendingBytesThenEof() {
        val pair = InMemoryStreamPair()
        pair.sideA.write(byteArrayOf(9, 8, 7, 6, 5))
        pair.sideA.close()

        val buffer = ByteArray(3)
        assertEquals(3, pair.sideB.read(buffer))
        assertArrayEquals(byteArrayOf(9, 8, 7), buffer)
        assertEquals(2, pair.sideB.read(buffer))
        assertArrayEquals(byteArrayOf(6, 5), buffer.copyOf(2))
        assertEquals(-1, pair.sideB.read(buffer))
        assertEquals(-1, pair.sideA.read(buffer))
    }

    @Test(timeout = 5_000)
    fun closeWakesBlockedReaderAndRejectsWrites() {
        val pair = InMemoryStreamPair()
        val result = AtomicReference<Int>()
        val reader = Thread({ result.set(pair.sideA.read(ByteArray(8))) }, "reader").apply { start() }
        Thread.sleep(100)

        pair.sideB.close()
        reader.join(1_000)
        assertEquals(-1, result.get())

        for (side in listOf(pair.sideA, pair.sideB)) {
            try {
                side.write(byteArrayOf(1))
                fail("关闭后写入应失败")
            } catch (_: IOException) {
            }
        }
    }
}
