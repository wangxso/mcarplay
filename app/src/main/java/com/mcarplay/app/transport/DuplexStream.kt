package com.mcarplay.app.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

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

/** 内存回环流对：两端各持一个 DuplexStream，用于单元测试。 */
class InMemoryStreamPair {
    private val aToB = Pipe()
    private val bToA = Pipe()

    val sideA: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = aToB.source.read(buffer)

            override fun write(bytes: ByteArray) = bToA.writeAndFlush(bytes)

            override fun close() {
                aToB.close()
                bToA.closePartner()
            }
        }

    val sideB: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = bToA.source.read(buffer)

            override fun write(bytes: ByteArray) = aToB.writeAndFlush(bytes)

            override fun close() {
                bToA.close()
                aToB.closePartner()
            }
        }

    private class Pipe {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()

        init {
            sink.connect(source)
        }

        fun writeAndFlush(bytes: ByteArray) {
            sink.write(bytes)
            sink.flush()
        }

        fun close() {
            runCatching { sink.close() }
            runCatching { source.close() }
        }

        /** 关闭对端出口，让对方读到 EOF。 */
        fun closePartner() {
            runCatching { source.close() }
        }
    }
}

class DuplexClosedException(
    message: String,
) : IOException(message)
