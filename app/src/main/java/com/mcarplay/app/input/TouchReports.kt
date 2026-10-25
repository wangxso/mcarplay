package com.mcarplay.app.input

/**
 * 触摸 HID 报文解析。
 *
 * 两种报文格式，**由设备 HID 描述符区分**（不按车机型号或 uuid 写死）：
 *  - **多槽格式**（DiPlay 测试台）：12 字节 = 2 槽 × 6 字节
 *    [槽号][按下 0/1][X u16LE][Y u16LE]；描述符是 Digitizer(0x0D) 页、每指含
 *    Tip Switch + X/Y 两个 16bit 字段。
 *  - **单指绝对坐标**（奥迪 MIB3 eso_touchScreen）：5 字节
 *    [tip 1bit + 7bit 填充][X u16LE][Y u16LE]；描述符同为 Digitizer 页但字段布局不同。
 *
 * 判定顺序：先看描述符里 X/Y 的 Report Size（16bit）与是否只有一个手指 Collection；
 * 描述符不可用时回退到「报文长度」判据（5B → 单指，12B+ → 多槽）。
 */
object TouchReports {
    const val TOUCH_UUID_HEX = "2a2a2a2a"
    const val KNOB_UUID_HEX = "2a2a2a2b"
    private const val SLOTS = 2
    private const val BYTES_PER_FINGER = 6

    data class Contact(
        val slot: Int,
        val xPixels: Int,
        val yPixels: Int,
        val down: Boolean,
    )

    /** 触摸报文格式（由描述符或报文长度判定）。 */
    enum class Format { MULTI_SLOT, SINGLE_ABSOLUTE }

    /**
     * 按描述符判定触摸格式；非 Digitizer 页（触摸设备）返回 null。
     *
     * 识别依据：Digitizer 页（0x0D）出现且含 X/Y 的 16bit 输入 → 触摸设备；
     * 单指 Collection（描述符里只有一个 0x22=Contact Identifier 的 Application）
     * 且报文为 5 字节布局 → SINGLE_ABSOLUTE。
     */
    fun formatFromDescriptor(descriptor: ByteArray): Format? {
        var page = 0
        var sawDigitizer = false
        var has16BitXy = false
        // 单指/多槽都含 Contact ID(0x22) 的 Finger Collection；以「X 的 ReportSize=16」
        // 作为两种格式共有的识别点，再用描述符长度与 bytes-per-finger 差异区分。
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size = prefix and 0x03
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            val dataBytes =
                when (size) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            if (index + 1 + dataBytes > descriptor.size) break
            var value = 0
            for (offset in 0 until dataBytes) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            index += 1 + dataBytes
            when {
                type == 1 && tag == 0x0 -> if (value == 0x0D) sawDigitizer = true
                // Report Size（全局项 tag=7）＝16 → X/Y 为 16bit（两种格式都是）
                type == 1 && tag == 0x7 -> if (value == 16) has16BitXy = true
            }
        }
        if (!sawDigitizer) return null
        if (!has16BitXy) return null
        // 奥迪单指描述符 62B、DiPlay 双指描述符更长（含两个 Finger Collection）。
        // 单一手指 Collection 的判定：Contact Identifier(0x09 0x22) 出现次数。
        val fingerCollections = countOccurrences(descriptor, byteArrayOf(0x09, 0x22))
        return if (fingerCollections <= 1) Format.SINGLE_ABSOLUTE else Format.MULTI_SLOT
    }

    private fun countOccurrences(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        var count = 0
        var index = 0
        while (index <= haystack.size - needle.size) {
            var match = true
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    match = false
                    break
                }
            }
            if (match) {
                count++
                index += needle.size
            } else {
                index++
            }
        }
        return count
    }

    /**
     * 解析触摸报文。
     * @param format 描述符判定的格式；null 时按报文长度回退（5B=单指，≥12B=多槽）
     */
    fun parse(
        report: ByteArray,
        format: Format? = null,
    ): List<Contact>? {
        val resolved =
            format ?: when {
                report.size == 5 -> Format.SINGLE_ABSOLUTE
                report.size >= SLOTS * BYTES_PER_FINGER -> Format.MULTI_SLOT
                else -> return null
            }
        return when (resolved) {
            Format.SINGLE_ABSOLUTE -> parseSingleTouch(report)
            Format.MULTI_SLOT -> parseMultiSlot(report)
        }
    }

    /** 兼容旧调用点：按 uuid 选择解析器（描述符不可得时的回退路径）。 */
    fun parseByUuid(
        uuid: String?,
        report: ByteArray,
    ): List<Contact>? =
        when (uuid) {
            TOUCH_UUID_HEX -> parseMultiSlot(report)
            // 单指设备（各车机 uuid 不同）：用报文长度自证
            else -> parse(report, format = null)
        }

    /** 多槽格式解析（DiPlay 测试台：12 字节 2 槽）。 */
    fun parseMultiSlot(report: ByteArray): List<Contact> {
        if (report.size < SLOTS * BYTES_PER_FINGER) return emptyList()
        return (0 until SLOTS).mapNotNull { slot ->
            val offset = slot * BYTES_PER_FINGER
            Contact(
                slot = slot,
                down = report[offset + 1].toInt() and 0xFF == 1,
                xPixels = readU16Le(report, offset + 2),
                yPixels = readU16Le(report, offset + 4),
            )
        }
    }

    /** 单指绝对坐标：5 字节 [tip 1bit][X u16LE][Y u16LE]。 */
    fun parseSingleTouch(report: ByteArray): List<Contact> {
        if (report.size < 5) return emptyList()
        return listOf(
            Contact(
                slot = 0,
                down = report[0].toInt() and 0x01 != 0,
                xPixels = readU16Le(report, 1),
                yPixels = readU16Le(report, 3),
            ),
        )
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}
