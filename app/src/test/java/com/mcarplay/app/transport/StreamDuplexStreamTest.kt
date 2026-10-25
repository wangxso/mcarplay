package com.mcarplay.app.transport

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 回归测试：读线程阻塞在 read() 期间，写线程必须能完成 write()。
 * 旧实现 read/write 共享一把 @Synchronized 监视器，读阻塞时持锁不放，
 * 写永久等待——真机蓝牙上实际触发（日志停在 link ready 之后）。
 */
class StreamDuplexStreamTest {
    @Test
    fun writeCompletesWhileReaderBlocksInRead() {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()
        sink.connect(source)
        val stream = StreamDuplexStream(source, sink)

        val reader =
            Thread {
                val buffer = ByteArray(1024)
                try {
                    while (stream.read(buffer) >= 0) {
                        // 持续阻塞读，模拟链路通道的读线程
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }

        Thread.sleep(200) // 确保读线程已经阻塞在 read()

        val written = CountDownLatch(1)
        Thread({
            stream.write(byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1D, 0x00))
            written.countDown()
        }, "writer").apply {
            isDaemon = true
            start()
        }

        assertTrue("write 在读线程阻塞期间发生了死锁", written.await(2, TimeUnit.SECONDS))

        stream.close()
        reader.join(1_000)
    }
}
