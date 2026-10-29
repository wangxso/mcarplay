package com.mcarplay.app.usb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Linux FunctionFS（`include/uapi/linux/usb/functionfs.h`）的用户态编码：写入 ep0 的
 * v2 描述符块与字符串块，以及从 ep0 读出的 12 字节事件。
 *
 * 全部为纯函数，字节序为小端；由 root 进程（[com.mcarplay.app.usb.root.WiredGadgetMain]）
 * 写入 `/dev/usb-ffs/<name>/ep0`。
 */
object FunctionFs {
    const val DESCRIPTORS_MAGIC_V2 = 3
    const val STRINGS_MAGIC = 2

    const val FLAG_HAS_FS_DESC = 1
    const val FLAG_HAS_HS_DESC = 2

    /** 把发给「设备」等非本接口接收者的控制请求也转给用户态：Apple 角色切换请求发给设备本身。 */
    const val FLAG_ALL_CTRL_RECIP = 64

    /** 主机尚未 SET_CONFIGURATION 时也转发控制请求（车机可能在选配置前就发厂商请求）。 */
    const val FLAG_CONFIG0_SETUP = 128

    const val EVENT_SIZE = 12

    private const val FULL_SPEED_BULK_PACKET = 64
    private const val HIGH_SPEED_BULK_PACKET = 512
    private const val LANG_EN_US = 0x0409

    /** iAP 接口：一个厂商自定义接口 + bulk IN/OUT 各一。 */
    fun iapDescriptors(identity: AppleUsbIdentity): ByteArray {
        val fullSpeed = iapInterface(identity, FULL_SPEED_BULK_PACKET)
        val highSpeed = iapInterface(identity, HIGH_SPEED_BULK_PACKET)
        val flags = FLAG_HAS_FS_DESC or FLAG_HAS_HS_DESC or FLAG_ALL_CTRL_RECIP or FLAG_CONFIG0_SETUP
        val body = ByteArrayOutputStream()
        body.write(le32(flags))
        body.write(le32(DESCRIPTOR_COUNT))
        body.write(le32(DESCRIPTOR_COUNT))
        body.write(fullSpeed)
        body.write(highSpeed)
        return withHeader(DESCRIPTORS_MAGIC_V2, body.toByteArray())
    }

    /** 函数内字符串表（索引从 1 开始，对应描述符里的 iInterface）。 */
    fun strings(vararg values: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(le32(values.size))
        body.write(le32(1))
        body.write(le16(LANG_EN_US))
        for (value in values) {
            body.write(value.toByteArray(Charsets.UTF_8))
            body.write(0)
        }
        return withHeader(STRINGS_MAGIC, body.toByteArray())
    }

    /** 解析一次 read(ep0) 读到的若干事件；不足 12 字节的尾巴丢弃。 */
    fun parseEvents(
        bytes: ByteArray,
        length: Int = bytes.size,
    ): List<FfsEvent> {
        val events = ArrayList<FfsEvent>(length / EVENT_SIZE)
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= EVENT_SIZE) {
            val start = buffer.position()
            val setup =
                UsbSetupPacket(
                    requestType = bytes[start].toInt() and 0xFF,
                    request = bytes[start + 1].toInt() and 0xFF,
                    value = buffer.getShort(start + 2).toInt() and 0xFFFF,
                    index = buffer.getShort(start + 4).toInt() and 0xFFFF,
                    length = buffer.getShort(start + 6).toInt() and 0xFFFF,
                )
            val type = FfsEventType.fromCode(bytes[start + 8].toInt() and 0xFF)
            events += FfsEvent(type, if (type == FfsEventType.SETUP) setup else null)
            buffer.position(start + EVENT_SIZE)
        }
        return events
    }

    private const val DESCRIPTOR_COUNT = 3

    private fun iapInterface(
        identity: AppleUsbIdentity,
        maxPacket: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // 接口描述符（9B）：bInterfaceNumber 由内核重映射，写 0 即可；iInterface = 字符串 1
        out.write(
            byteArrayOf(
                9,
                4,
                0,
                0,
                2,
                identity.iapInterfaceClass.toByte(),
                identity.iapInterfaceSubclass.toByte(),
                identity.iapInterfaceProtocol.toByte(),
                1,
            ),
        )
        out.write(bulkEndpoint(0x81, maxPacket))
        out.write(bulkEndpoint(0x02, maxPacket))
        return out.toByteArray()
    }

    private fun bulkEndpoint(
        address: Int,
        maxPacket: Int,
    ): ByteArray = byteArrayOf(7, 5, address.toByte(), 2, (maxPacket and 0xFF).toByte(), (maxPacket shr 8).toByte(), 0)

    private fun withHeader(
        magic: Int,
        body: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(body.size + 8)
        out.write(le32(magic))
        out.write(le32(body.size + 8))
        out.write(body)
        return out.toByteArray()
    }

    private fun le32(value: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun le16(value: Int): ByteArray =
        ByteBuffer
            .allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value.toShort())
            .array()
}

enum class FfsEventType(
    val code: Int,
) {
    BIND(0),
    UNBIND(1),
    ENABLE(2),
    DISABLE(3),
    SETUP(4),
    SUSPEND(5),
    RESUME(6),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): FfsEventType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class FfsEvent(
    val type: FfsEventType,
    /** 仅 [FfsEventType.SETUP] 有值。 */
    val setup: UsbSetupPacket?,
)

/** USB 控制请求的 8 字节 SETUP 包。 */
data class UsbSetupPacket(
    val requestType: Int,
    val request: Int,
    val value: Int,
    val index: Int,
    val length: Int,
) {
    /** 设备 → 主机（IN）方向。 */
    val isDeviceToHost: Boolean get() = requestType and 0x80 != 0

    /** 0 = 标准，1 = 类，2 = 厂商。 */
    val kind: Int get() = (requestType shr 5) and 0x03

    /** 0 = 设备，1 = 接口，2 = 端点，3 = 其他。 */
    val recipient: Int get() = requestType and 0x1F

    override fun toString(): String =
        "bmRequestType=0x%02x bRequest=0x%02x wValue=0x%04x wIndex=0x%04x wLength=%d".format(requestType, request, value, index, length)
}
