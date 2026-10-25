package com.mcarplay.app.replay

import com.mcarplay.app.transport.DuplexStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 脚本化「车机」字节流：read() 依序吐出录制的 rx 字节，耗尽后保持静默
 * （等会话侧超时逻辑自然走完）；write() 捕获我们的全部输出（golden 比对用）。
 */
class ScriptedCarStream(
    rxBytes: ByteArray,
) : DuplexStream {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var leftover: ByteArray = ByteArray(0)
    private var leftoverOffset = 0

    @Volatile
    private var closed = false
    val written = ArrayList<ByteArray>()

    init {
        if (rxBytes.isNotEmpty()) chunks.add(rxBytes)
    }

    override fun read(buffer: ByteArray): Int {
        while (true) {
            if (closed) return -1
            if (leftoverOffset < leftover.size) {
                val count = minOf(buffer.size, leftover.size - leftoverOffset)
                System.arraycopy(leftover, leftoverOffset, buffer, 0, count)
                leftoverOffset += count
                return count
            }
            val next =
                try {
                    chunks.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            leftover = next
            leftoverOffset = 0
        }
    }

    override fun write(bytes: ByteArray) {
        written.add(bytes.copyOf())
    }

    override fun close() {
        closed = true
    }
}

/** 从原始输出字节里按链路帧头（FF5A + u16 整帧长度）拆出 CSM 载荷。 */
internal fun extractCsmPayloads(bytes: List<ByteArray>): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    val all = bytes.fold(ByteArray(0)) { acc, cur -> acc + cur }
    var off = 0
    while (off + 9 <= all.size) {
        if (all[off] != 0xFF.toByte() || all[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        val frameLen =
            ((all[off + 2].toInt() and 0xFF) shl 8) or (all[off + 3].toInt() and 0xFF)
        if (frameLen < 9 || off + frameLen > all.size) break
        if (frameLen > 9) out.add(all.copyOfRange(off + 9, off + frameLen - 1)) // 尾字节=载荷校验
        off += frameLen
    }
    return out
}
