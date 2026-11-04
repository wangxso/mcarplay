package com.mcarplay.app.diagnostics.btsnoop

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** 一条 HCI 包：H4 包类型、方向和去掉类型字节后的负载。 */
internal class HciRecord(
    /** Unix 纪元起的微秒数（btsnoop 时间戳减去 [BtSnoopFile.EPOCH_DELTA_MICROS]）。 */
    val unixMicros: Long,
    /** true = 控制器 → 主机（事件、收到的 ACL）。 */
    val incoming: Boolean,
    val packetType: Int,
    val payload: ByteArray,
    /** 抓包只保存了部分字节（included < original）。 */
    val truncated: Boolean,
)

/**
 * btsnoop 文件读取（Android `persist.bluetooth.btsnooplogmode=full` 写出的 `btsnoop_hci.log`）。
 *
 * 文件头 16 字节：`btsnoop\0`、版本 1、数据链路类型（1002 = H4，1001 = 无封装 HCI）；
 * 每条记录 24 字节头：原长、保存长度、标志、丢包数、时间戳（均为大端）。
 * 流式读取，不把整个文件读进内存——A2DP 播放时文件可达上百 MB。
 */
internal object BtSnoopFile {
    val MAGIC = "btsnoop".toByteArray(Charsets.US_ASCII) + 0.toByte()
    const val DATALINK_HCI = 1001
    const val DATALINK_H4 = 1002

    /** AOSP `kBtSnoopEpochDelta`：0000-01-01 到 1970-01-01 的微秒数。 */
    const val EPOCH_DELTA_MICROS = 0x00dcddb30f2f8000L

    const val TYPE_COMMAND = 1
    const val TYPE_ACL = 2
    const val TYPE_EVENT = 4

    /** 单条记录上限：HCI ACL 最大负载远小于此值，超过即视为文件损坏。 */
    private const val MAX_RECORD_BYTES = 64 * 1024

    class Stats(
        val datalink: Int,
    ) {
        var records = 0L
        var truncated = 0L
        var firstMicros: Long? = null
        var lastMicros: Long? = null

        /** false = 文件在记录中间结束或遇到损坏长度（通常是抓包仍在写入）。 */
        var complete = true
    }

    fun isBtSnoop(head: ByteArray): Boolean = head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /** 读取整个文件；[cancelled] 每条记录检查一次。非 btsnoop 或不支持的链路类型抛 [IOException]。 */
    fun read(
        input: InputStream,
        cancelled: () -> Boolean = { false },
        onRecord: (HciRecord) -> Unit,
    ): Stats {
        val header = ByteArray(16)
        if (readFully(input, header) != header.size || !isBtSnoop(header)) throw IOException("not a btsnoop file")
        val version = u32(header, 8)
        val datalink = u32(header, 12).toInt()
        if (version != 1L) throw IOException("btsnoop version $version")
        if (datalink != DATALINK_H4 && datalink != DATALINK_HCI) throw IOException("btsnoop datalink $datalink")
        val stats = Stats(datalink)
        val recordHeader = ByteArray(24)
        while (!cancelled()) {
            val got = readFully(input, recordHeader)
            if (got == 0) break
            if (got < recordHeader.size) {
                stats.complete = false
                break
            }
            val original = u32(recordHeader, 0)
            val included = u32(recordHeader, 4)
            val flags = u32(recordHeader, 8).toInt()
            val timestamp = u64(recordHeader, 16)
            if (included > MAX_RECORD_BYTES) {
                stats.complete = false
                break
            }
            val bytes = ByteArray(included.toInt())
            if (readFully(input, bytes) < bytes.size) {
                stats.complete = false
                break
            }
            val unixMicros = timestamp - EPOCH_DELTA_MICROS
            stats.records++
            if (included < original) stats.truncated++
            if (stats.firstMicros == null) stats.firstMicros = unixMicros
            stats.lastMicros = unixMicros
            val incoming = flags and 1 == 1
            val record =
                if (datalink == DATALINK_H4) {
                    if (bytes.isEmpty()) continue
                    HciRecord(unixMicros, incoming, bytes[0].toInt() and 0xff, bytes.copyOfRange(1, bytes.size), included < original)
                } else {
                    val type =
                        when {
                            flags and 2 == 0 -> TYPE_ACL
                            incoming -> TYPE_EVENT
                            else -> TYPE_COMMAND
                        }
                    HciRecord(unixMicros, incoming, type, bytes, included < original)
                }
            onRecord(record)
        }
        return stats
    }

    /** 读满 [buffer] 或读到流末尾，返回实际读到的字节数。 */
    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
    ): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count =
                try {
                    input.read(buffer, offset, buffer.size - offset)
                } catch (_: EOFException) {
                    -1
                }
            if (count < 0) break
            offset += count
        }
        return offset
    }

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or (bytes[offset + 3].toLong() and 0xff)

    private fun u64(
        bytes: ByteArray,
        offset: Int,
    ): Long = (u32(bytes, offset) shl 32) or u32(bytes, offset + 4)
}
