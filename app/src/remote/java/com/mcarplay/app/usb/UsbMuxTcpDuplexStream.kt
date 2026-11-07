package com.mcarplay.app.usb

import com.mcarplay.app.transport.DuplexStream
import java.io.IOException

/** 把 USBMUX TCP 连接适配成 iAP2 链路使用的阻塞式双向流。 */
class UsbMuxTcpDuplexStream(
    private val connection: UsbMuxTcpConnection,
) : DuplexStream {
    override fun read(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        while (true) {
            val bytes =
                try {
                    connection.receive(buffer.size, READ_TIMEOUT_MILLIS)
                } catch (_: IOException) {
                    return -1
                }
            if (bytes == null) continue
            if (bytes.isEmpty()) return -1
            bytes.copyInto(buffer)
            return bytes.size
        }
    }

    override fun write(bytes: ByteArray) {
        connection.send(bytes)
    }

    override fun close() {
        connection.close()
    }

    private companion object {
        const val READ_TIMEOUT_MILLIS = 1_000L
    }
}
