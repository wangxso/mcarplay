package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 扩展长度（0x…F + int 对象）必须按无符号解读。
 *
 * 实测来源（2026-10-20）：DiPlay 以 iAPSendMessage 信封下发 0x5000，data 长 182B
 * （0xB6）——旧实现 readSigned 读成 -74，copyOfRange 抛 `IllegalArgumentException:
 * 51 > -23`，整条命令被丢；≥128 的长度全部中招。
 */
class BplistExtendedLengthTest {
    @Test
    fun extendedDataLengthAbove127Parses() {
        val data = ByteArray(200) { (it and 0xFF).toByte() }
        // 手工编码：{"data": <200B>}（规范布局：扩展长度 0x4F 0x10 0xC8）
        val objects = ArrayList<ByteArray>()
        objects.add(byteArrayOf(0xD1.toByte(), 1, 2)) // root dict(1)
        objects.add(byteArrayOf(0x54.toByte()) + "data".toByteArray()) // 1
        objects.add(byteArrayOf(0x4F.toByte(), 0x10, 200.toByte()) + data) // 2
        var body = "bplist00".toByteArray()
        val offsets = ArrayList<Int>()
        for (o in objects) {
            offsets.add(body.size)
            body += o
        }
        val table = body.size
        for (off in offsets) body += byteArrayOf(off.toByte())
        val trailer = ByteArray(32)
        trailer[6] = 1
        trailer[7] = 1

        fun put(
            at: Int,
            v: Long,
        ) = repeat(8) { trailer[at + it] = ((v shr ((7 - it) * 8)) and 0xFF).toByte() }
        put(8, objects.size.toLong())
        put(16, 0L)
        put(24, table.toLong())
        body += trailer

        val parsed = BinaryPlist.parse(body) as Map<*, *>
        val blob = parsed["data"] as ByteArray
        assertEquals(200, blob.size)
        assertEquals(0.toByte(), blob[0])
        assertEquals(199.toByte(), blob[199])
    }

    @Test
    fun extendedAsciiStringLengthAbove127Parses() {
        val text = "x".repeat(150)
        val objects = ArrayList<ByteArray>()
        objects.add(byteArrayOf(0x5F.toByte(), 0x10, 150.toByte()) + text.toByteArray()) // 0 root
        var body = "bplist00".toByteArray()
        body += objects[0]
        val table = body.size
        body += byteArrayOf(8) // 偏移表：1 对象
        val trailer = ByteArray(32)
        trailer[6] = 1
        trailer[7] = 1

        fun put(
            at: Int,
            v: Long,
        ) = repeat(8) { trailer[at + it] = ((v shr ((7 - it) * 8)) and 0xFF).toByte() }
        put(8, 1L)
        put(16, 0L)
        put(24, table.toLong())
        body += trailer

        assertEquals(text, BinaryPlist.parse(body))
    }
}
