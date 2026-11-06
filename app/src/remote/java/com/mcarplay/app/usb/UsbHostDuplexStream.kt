package com.mcarplay.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.mcarplay.app.transport.DuplexStream
import java.io.IOException

/** 一对 bulk 端点的最小抽象：真实实现走 [UsbDeviceConnection]，单测用假实现。 */
interface BulkPipe {
    /** 返回读到的字节数；超时与错误都返回负数（Android API 无法区分二者）。 */
    fun bulkIn(
        buffer: ByteArray,
        timeoutMillis: Int,
    ): Int

    fun bulkOut(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int

    /** 设备是否仍连着（拔线后 bulkIn 只会一直返回负数）。 */
    fun isAttached(): Boolean

    fun close()
}

class UsbConnectionBulkPipe(
    private val connection: UsbDeviceConnection,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val attached: () -> Boolean,
) : BulkPipe {
    override fun bulkIn(
        buffer: ByteArray,
        timeoutMillis: Int,
    ): Int = connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMillis)

    override fun bulkOut(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int = connection.bulkTransfer(outEndpoint, buffer, offset, length, timeoutMillis)

    override fun isAttached(): Boolean = attached()

    override fun close() = connection.close()
}

/**
 * 有线 iAP2：角色切换后手机是 USB 主机，车机 iAP2 接口的 bulk IN/OUT 承载与蓝牙 RFCOMM
 * 完全相同的链路层字节流，所以只需实现 [DuplexStream] 即可接到现有的
 * [com.mcarplay.app.transport.Iap2LinkChannel]。
 *
 * 读用短超时轮询：既能响应 [close]，也能在拔线后结束（返回 -1）。
 */
class UsbHostDuplexStream(
    private val pipe: BulkPipe,
    private val readTimeoutMillis: Int = 200,
    private val writeTimeoutMillis: Int = 1_000,
    private val maxWriteChunk: Int = 16 * 1024,
) : DuplexStream {
    @Volatile
    private var closed = false

    override fun read(buffer: ByteArray): Int {
        while (!closed) {
            val n = pipe.bulkIn(buffer, readTimeoutMillis)
            if (n > 0) return n
            if (!pipe.isAttached()) return -1
        }
        return -1
    }

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (closed) throw IOException("USB 流已关闭")
            val length = minOf(maxWriteChunk, bytes.size - offset)
            val n = pipe.bulkOut(bytes, offset, length, writeTimeoutMillis)
            if (n <= 0) throw IOException("USB bulk 写入失败（$n），已写 $offset/${bytes.size}")
            offset += n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pipe.close()
    }
}
