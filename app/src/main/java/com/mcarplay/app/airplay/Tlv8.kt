package com.mcarplay.app.airplay

/** HomeKit/AirPlay 风格 TLV8 编解码（独立实现，语义与参考接收端一致）。 */
object Tlv8 {
    private const val SEPARATOR_TYPE = 0xFF
    private const val MAX_FRAGMENT = 255

    data class Item(
        val type: Int,
        val value: ByteArray,
    )

    fun encode(items: List<Item>): ByteArray {
        val out = ArrayList<ByteArray>()
        var previousType: Int? = null
        for (item in items) {
            if (previousType == item.type) {
                out += byteArrayOf(SEPARATOR_TYPE.toByte(), 0)
            }
            var offset = 0
            do {
                val length = minOf(MAX_FRAGMENT, item.value.size - offset)
                out += byteArrayOf(item.type.toByte(), length.toByte())
                out += item.value.copyOfRange(offset, offset + length)
                offset += length
            } while (offset < item.value.size)
            previousType = item.type
        }
        return out.reduce { acc, bytes -> acc + bytes }
    }

    fun decode(buffer: ByteArray): Map<Int, ByteArray> {
        val output = HashMap<Int, ByteArray>()
        var position = 0
        var lastType: Int? = null
        var lastLength = 0
        while (position + 2 <= buffer.size) {
            val type = buffer[position].toInt() and 0xFF
            val length = buffer[position + 1].toInt() and 0xFF
            position += 2
            if (position + length > buffer.size) break
            val value = buffer.copyOfRange(position, position + length)
            position += length
            if (type == lastType && lastLength == MAX_FRAGMENT) {
                output[type] = (output[type] ?: ByteArray(0)) + value
            } else {
                output[type] = value
            }
            lastType = type
            lastLength = length
        }
        return output
    }

    // 常用类型
    const val TYPE_METHOD = 0x00
    const val TYPE_IDENTIFIER = 0x01
    const val TYPE_SALT = 0x02
    const val TYPE_PUBLIC_KEY = 0x03
    const val TYPE_PROOF = 0x04
    const val TYPE_ENCRYPTED_DATA = 0x05
    const val TYPE_STATE = 0x06
    const val TYPE_ERROR = 0x07
    const val TYPE_SIGNATURE = 0x0A
}
