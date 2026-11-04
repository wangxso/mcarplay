package com.mcarplay.app.carlife

import java.io.ByteArrayOutputStream

/** 测试用：按位写 H.264 RBSP，[nal] 补 rbsp_stop_one_bit 与 emulation prevention。 */
internal class H264BitWriter {
    private val bits = ArrayList<Int>()

    fun bit(value: Int) = apply { bits += value and 1 }

    fun bits(
        value: Long,
        count: Int,
    ) = apply { for (shift in count - 1 downTo 0) bits += ((value ushr shift) and 1L).toInt() }

    fun ue(value: Int) =
        apply {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            repeat(length - 1) { bits += 0 }
            bits(code.toLong(), length)
        }

    fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

    fun nal(header: Int): ByteArray {
        val payload = ArrayList(bits).apply { add(1) }
        while (payload.size % 8 != 0) payload += 0
        val raw = payload.chunked(8).map { byte -> byte.fold(0) { acc, b -> (acc shl 1) or b } }
        val out = ByteArrayOutputStream()
        out.write(header)
        var zeros = 0
        for (value in raw) {
            if (zeros >= 2 && value <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
}
