package com.mcarplay.app.diagnostics.btsnoop

import java.util.UUID

/** SDP 数据元素（Core Spec Vol 3 Part B §3）：只区分分析需要的几类。 */
internal sealed interface SdpElement {
    /** 已解析的字节数（含头部）。 */
    val size: Int
}

internal data class SdpUuid(
    val uuid: UUID,
    override val size: Int,
) : SdpElement

internal data class SdpUint(
    val value: Long,
    override val size: Int,
) : SdpElement

internal data class SdpSequence(
    val items: List<SdpElement>,
    override val size: Int,
    /** false = 字节在序列声明的长度之前就用完了（续传被截断）。 */
    val complete: Boolean,
) : SdpElement

internal data class SdpOther(
    override val size: Int,
) : SdpElement

internal object SdpElements {
    private val BASE_UUID_LOW = 0x800000805f9b34fbUL.toLong()
    private const val MAX_DEPTH = 8

    /**
     * 从 [offset] 解析一个元素，[limit] 为可用字节上界。字节不够时返回 null；
     * [lenient] 时序列可在子元素不完整处停下并标记 `complete=false`。
     */
    fun parse(
        bytes: ByteArray,
        offset: Int,
        limit: Int = bytes.size,
        lenient: Boolean = false,
    ): SdpElement? = parse(bytes, offset, limit, lenient, 0)

    private fun parse(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
        lenient: Boolean,
        depth: Int,
    ): SdpElement? {
        if (offset >= limit || depth > MAX_DEPTH) return null
        val descriptor = bytes[offset].toInt() and 0xff
        val type = descriptor ushr 3
        val sizeIndex = descriptor and 7
        val lengthBytes =
            when (sizeIndex) {
                5 -> 1
                6 -> 2
                7 -> 4
                else -> 0
            }
        if (offset + 1 + lengthBytes > limit) return null
        val dataLength: Long =
            when (sizeIndex) {
                5 -> (bytes[offset + 1].toLong() and 0xff)
                6 -> u16be(bytes, offset + 1).toLong()
                7 -> u32be(bytes, offset + 1)
                else -> if (type == 0) 0L else (1L shl sizeIndex)
            }
        val start = offset + 1 + lengthBytes
        val declaredEnd = start + dataLength
        if (type == 6 || type == 7) {
            val available = minOf(declaredEnd, limit.toLong()).toInt()
            if (declaredEnd > limit && !lenient) return null
            val items = ArrayList<SdpElement>()
            var cursor = start
            var complete = declaredEnd <= limit
            while (cursor < available) {
                val child = parse(bytes, cursor, available, lenient, depth + 1)
                if (child == null) {
                    complete = false
                    break
                }
                items += child
                cursor += child.size
                if (child is SdpSequence && !child.complete) {
                    complete = false
                    break
                }
            }
            return SdpSequence(items, cursor - offset, complete)
        }
        if (declaredEnd > limit) return null
        val size = (declaredEnd - offset).toInt()
        return when {
            type == 3 && dataLength == 2L -> SdpUuid(shortUuid(u16be(bytes, start).toLong()), size)
            type == 3 && dataLength == 4L -> SdpUuid(shortUuid(u32be(bytes, start)), size)
            type == 3 && dataLength == 16L -> SdpUuid(uuidBe(bytes, start), size)
            type == 1 && dataLength <= 8L -> SdpUint(readUnsigned(bytes, start, dataLength.toInt()), size)
            else -> SdpOther(size)
        }
    }

    /** 16/32 位 UUID 展开为蓝牙基础 UUID。 */
    fun shortUuid(value: Long): UUID = UUID((value shl 32) or 0x1000L, BASE_UUID_LOW)

    fun uuidBe(
        bytes: ByteArray,
        offset: Int,
    ): UUID {
        var high = 0L
        var low = 0L
        for (i in 0 until 8) high = (high shl 8) or (bytes[offset + i].toLong() and 0xff)
        for (i in 8 until 16) low = (low shl 8) or (bytes[offset + i].toLong() and 0xff)
        return UUID(high, low)
    }

    /** EIR/AD 里的 128 位 UUID 是小端字节序。 */
    fun uuidLe(
        bytes: ByteArray,
        offset: Int,
    ): UUID = uuidBe(ByteArray(16) { bytes[offset + 15 - it] }, 0)

    /** 一个元素里出现的全部 UUID（深度优先）。 */
    fun uuids(element: SdpElement?): List<UUID> =
        when (element) {
            is SdpUuid -> listOf(element.uuid)
            is SdpSequence -> element.items.flatMap { uuids(it) }
            else -> emptyList()
        }

    private fun readUnsigned(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        var value = 0L
        for (i in 0 until length) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        return value
    }

    fun u16be(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    fun u32be(
        bytes: ByteArray,
        offset: Int,
    ): Long = (u16be(bytes, offset).toLong() shl 16) or u16be(bytes, offset + 2).toLong()
}

/** SDP 服务记录摘要：只保留服务类 UUID、RFCOMM 信道和编码长度，不保留服务名。 */
internal class SdpRecordSummary(
    val handle: Long?,
    val serviceClasses: List<UUID>,
    val allUuids: Set<UUID>,
    val rfcommChannel: Int?,
    val bytes: Int,
    val complete: Boolean,
)

internal object SdpRecords {
    private const val ATTR_HANDLE = 0x0000L
    private const val ATTR_SERVICE_CLASS_IDS = 0x0001L
    private const val ATTR_PROTOCOL_DESCRIPTORS = 0x0004L

    /** 一条属性列表（`[id, value, id, value…]` 序列）→ 记录摘要。 */
    fun summarize(record: SdpSequence): SdpRecordSummary {
        var handle: Long? = null
        var classes = emptyList<UUID>()
        var channel: Int? = null
        val items = record.items
        var index = 0
        while (index + 1 < items.size) {
            val id = (items[index] as? SdpUint)?.value
            val value = items[index + 1]
            when (id) {
                ATTR_HANDLE -> handle = (value as? SdpUint)?.value
                ATTR_SERVICE_CLASS_IDS -> classes = SdpElements.uuids(value)
                ATTR_PROTOCOL_DESCRIPTORS -> channel = rfcommChannel(value)
            }
            index += 2
        }
        return SdpRecordSummary(handle, classes, SdpElements.uuids(record).toSet(), channel, record.size, record.complete)
    }

    /** ServiceSearchAttribute 回复：属性列表的序列。不完整时返回能解析出的部分。 */
    fun summarizeLists(
        bytes: ByteArray,
        length: Int,
    ): List<SdpRecordSummary> {
        val outer = SdpElements.parse(bytes, 0, length, lenient = true) as? SdpSequence ?: return emptyList()
        return outer.items.filterIsInstance<SdpSequence>().map { summarize(it) }
    }

    private fun rfcommChannel(descriptors: SdpElement): Int? {
        val layers = (descriptors as? SdpSequence)?.items ?: return null
        for (layer in layers) {
            val parts = (layer as? SdpSequence)?.items ?: continue
            if ((parts.firstOrNull() as? SdpUuid)?.uuid == BtUuids.RFCOMM) {
                return (parts.getOrNull(1) as? SdpUint)?.value?.toInt()
            }
        }
        return null
    }
}

/** EIR（Write Extended Inquiry Response 的 240 字节）摘要；名称只记长度。 */
internal class EirSummary(
    val usedBytes: Int,
    val nameBytes: Int?,
    val nameComplete: Boolean,
    val uuid16Count: Int,
    val uuid16Complete: Boolean,
    val uuid128: List<UUID>,
    val uuid128Complete: Boolean,
) {
    companion object {
        fun parse(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): EirSummary {
            val end = minOf(bytes.size, offset + length)
            var cursor = offset
            var nameBytes: Int? = null
            var nameComplete = false
            var uuid16 = 0
            var uuid16Complete = true
            val uuid128 = ArrayList<UUID>()
            var uuid128Complete = true
            while (cursor < end) {
                val fieldLength = bytes[cursor].toInt() and 0xff
                if (fieldLength == 0 || cursor + 1 + fieldLength > end) break
                val type = bytes[cursor + 1].toInt() and 0xff
                val dataStart = cursor + 2
                val dataLength = fieldLength - 1
                when (type) {
                    0x02, 0x03 -> {
                        uuid16 += dataLength / 2
                        if (type == 0x02) uuid16Complete = false
                    }
                    0x06, 0x07 -> {
                        for (i in 0 until dataLength / 16) uuid128 += SdpElements.uuidLe(bytes, dataStart + i * 16)
                        if (type == 0x06) uuid128Complete = false
                    }
                    0x08, 0x09 -> {
                        nameBytes = dataLength
                        nameComplete = type == 0x09
                    }
                }
                cursor += 1 + fieldLength
            }
            return EirSummary(cursor - offset, nameBytes, nameComplete, uuid16, uuid16Complete, uuid128, uuid128Complete)
        }
    }
}

/** 已知 UUID 的可读名称（只用于报告；未知 UUID 原样输出，它们是服务标识而非个人信息）。 */
internal object BtUuids {
    val RFCOMM: UUID = SdpElements.shortUuid(0x0003)
    val L2CAP: UUID = SdpElements.shortUuid(0x0100)
    val CARPLAY: UUID = UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a")
    val CARPLAY_REVERSED: UUID = UUID.fromString("1a29eaab-0173-bc88-1c45-4de166248d2d")
    val IAP2_PHONE: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    val IAP2_ACCESSORY: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
    val IAP2_VARIANT: UUID = UUID.fromString("00000000-cade-defa-deca-deafdecacafe")

    private val names =
        mapOf(
            CARPLAY to "CarPlay",
            CARPLAY_REVERSED to "CarPlay(反序)",
            IAP2_PHONE to "iAP2(手机)",
            IAP2_ACCESSORY to "iAP2(车机)",
            IAP2_VARIANT to "iAP2(cade-defa)",
            L2CAP to "L2CAP",
            RFCOMM to "RFCOMM",
            SdpElements.shortUuid(0x1002) to "公共浏览组",
            SdpElements.shortUuid(0x1101) to "SPP",
            SdpElements.shortUuid(0x1105) to "OPP",
            SdpElements.shortUuid(0x110a) to "A2DP源",
            SdpElements.shortUuid(0x110b) to "A2DP接收",
            SdpElements.shortUuid(0x110c) to "AVRCP目标",
            SdpElements.shortUuid(0x110e) to "AVRCP",
            SdpElements.shortUuid(0x110f) to "AVRCP控制",
            SdpElements.shortUuid(0x1112) to "HSP网关",
            SdpElements.shortUuid(0x1115) to "PANU",
            SdpElements.shortUuid(0x1116) to "NAP",
            SdpElements.shortUuid(0x111e) to "HFP",
            SdpElements.shortUuid(0x111f) to "HFP网关",
            SdpElements.shortUuid(0x112f) to "PBAP服务端",
            SdpElements.shortUuid(0x1132) to "MAP服务端",
            SdpElements.shortUuid(0x1133) to "MAP通知",
            SdpElements.shortUuid(0x1134) to "MAP",
            SdpElements.shortUuid(0x1200) to "PnP",
            SdpElements.shortUuid(0x1203) to "通用音频",
        )

    fun label(uuid: UUID): String {
        names[uuid]?.let { return it }
        if (uuid.leastSignificantBits == SdpElements.shortUuid(0).leastSignificantBits &&
            uuid.mostSignificantBits and 0xffffffffL == 0x1000L
        ) {
            return "0x%04X".format(uuid.mostSignificantBits ushr 32)
        }
        return uuid.toString()
    }

    fun isCarPlay(uuid: UUID): Boolean = uuid == CARPLAY || uuid == CARPLAY_REVERSED
}
