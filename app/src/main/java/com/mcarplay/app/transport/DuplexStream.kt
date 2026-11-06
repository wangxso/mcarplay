package com.mcarplay.app.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream

/** 阻塞式双向字节流抽象：蓝牙 RFCOMM socket 与内存回环测试共用。 */
interface DuplexStream : Closeable {
    /** 阻塞读取；返回 -1 表示流结束。 */
    fun read(buffer: ByteArray): Int

    /** 阻塞写入全部字节或抛异常。 */
    fun write(bytes: ByteArray)

    override fun close()
}

class StreamDuplexStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : DuplexStream {
    /**
     * 注意：read/write 不能共享一把锁——读线程会阻塞在 read() 里持锁等待数据，
     * 写线程将永久拿不到锁（真机蓝牙上实际触发过）。写入的并发安全由
     * Iap2LinkChannel 的通道锁保证（所有写入都在 lock 内串行），读只发生在
     * 唯一的读线程，蓝牙 socket 的输入/输出流彼此独立，跨线程读写是安全的。
     */
    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }
}

/**
 * 内存回环流对：两端各持一个 DuplexStream，用于单元测试。
 *
 * 任一端 close 后，两个方向都不再接受写入；对端读完已写入的字节后读到 EOF（-1），
 * 与 socket 收到 FIN 一致。
 */
class InMemoryStreamPair {
    private val aToB = Pipe()
    private val bToA = Pipe()

    val sideA: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = aToB.read(buffer)

            override fun write(bytes: ByteArray) = bToA.write(bytes)

            override fun close() {
                aToB.close()
                bToA.close()
            }
        }

    val sideB: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = bToA.read(buffer)

            override fun write(bytes: ByteArray) = aToB.write(bytes)

            override fun close() {
                bToA.close()
                aToB.close()
            }
        }

    /**
     * 与线程无关的单向字节管道。
     *
     * 不用 PipedInputStream：它记住最后一个写入线程，该线程一退出、缓冲区又读空，
     * 读端就报 `Write end dead` / `Pipe broken`。测试里的模拟车机线程发完最后一帧就退出，
     * 于是链路被误判断开（CI 上 Iap2SenderSessionTest 偶发失败）；真 RFCOMM socket 没有这种语义。
     */
    private class Pipe {
        private val lock = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var headOffset = 0
        private var closed = false

        fun write(bytes: ByteArray) {
            synchronized(lock) {
                if (closed) throw DuplexClosedException("in-memory pipe closed")
                if (bytes.isEmpty()) return
                chunks.addLast(bytes.copyOf())
                lock.notifyAll()
            }
        }

        /** 阻塞到有数据；关闭且已读空时返回 -1。 */
        fun read(buffer: ByteArray): Int {
            if (buffer.isEmpty()) return 0
            synchronized(lock) {
                while (chunks.isEmpty()) {
                    if (closed) return -1
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("in-memory pipe read interrupted")
                    }
                }
                var copied = 0
                while (copied < buffer.size && chunks.isNotEmpty()) {
                    val head = chunks.first()
                    val count = minOf(head.size - headOffset, buffer.size - copied)
                    System.arraycopy(head, headOffset, buffer, copied, count)
                    copied += count
                    headOffset += count
                    if (headOffset == head.size) {
                        chunks.removeFirst()
                        headOffset = 0
                    }
                }
                return copied
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }
}

class DuplexClosedException(
    message: String,
) : IOException(message)
