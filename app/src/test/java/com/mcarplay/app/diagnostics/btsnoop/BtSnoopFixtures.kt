package com.mcarplay.app.diagnostics.btsnoop

import java.io.ByteArrayOutputStream
import java.util.UUID

/** 合成 btsnoop（H4）抓包：HCI 命令/事件、ACL 分片、L2CAP 信令与 SDP PDU。 */
internal class BtSnoopFixtures(
    private val datalink: Int = BtSnoopFile.DATALINK_H4,
) {
    private val out = ByteArrayOutputStream()
    private var clockMicros = 1_793_600_000_000_000L

    init {
        out.write(BtSnoopFile.MAGIC)
        out.write(be32(1))
        out.write(be32(datalink.toLong()))
    }

    fun bytes(): ByteArray = out.toByteArray()

    fun advance(millis: Long) {
        clockMicros += millis * 1000
    }

    fun record(
        type: Int,
        incoming: Boolean,
        payload: ByteArray,
        includedLimit: Int? = null,
    ) {
        val data = if (datalink == BtSnoopFile.DATALINK_H4) byteArrayOf(type.toByte()) + payload else payload
        val included = includedLimit?.let { minOf(it, data.size) } ?: data.size
        val flags = (if (incoming) 1 else 0) or (if (type == BtSnoopFile.TYPE_ACL) 0 else 2)
        out.write(be32(data.size.toLong()))
        out.write(be32(included.toLong()))
        out.write(be32(flags.toLong()))
        out.write(be32(0))
        out.write(be64(clockMicros + BtSnoopFile.EPOCH_DELTA_MICROS))
        out.write(data, 0, included)
        clockMicros += 1000
    }

    fun command(
        opcode: Int,
        params: ByteArray,
    ) = record(BtSnoopFile.TYPE_COMMAND, false, le16(opcode) + byteArrayOf(params.size.toByte()) + params)

    fun event(
        code: Int,
        params: ByteArray,
    ) = record(BtSnoopFile.TYPE_EVENT, true, byteArrayOf(code.toByte(), params.size.toByte()) + params)

    fun connectionRequest(
        address: ByteArray,
        classOfDevice: Int,
    ) = event(0x04, address + le24(classOfDevice) + byteArrayOf(0x01))

    fun connectionComplete(
        handle: Int,
        address: ByteArray,
    ) = event(0x03, byteArrayOf(0) + le16(handle) + address + byteArrayOf(0x01, 0x00))

    fun disconnectionComplete(handle: Int) = event(0x05, byteArrayOf(0) + le16(handle) + byteArrayOf(0x13))

    fun pairingComplete(address: ByteArray) = event(0x36, byteArrayOf(0) + address)

    /** 一个 L2CAP PDU，按 [fragment] 字节切成 ACL 首片 + 续片。 */
    fun l2cap(
        handle: Int,
        incoming: Boolean,
        cid: Int,
        data: ByteArray,
        fragment: Int = 1021,
    ) {
        val pdu = le16(data.size) + le16(cid) + data
        var offset = 0
        var first = true
        while (offset < pdu.size) {
            val chunk = pdu.copyOfRange(offset, minOf(pdu.size, offset + fragment))
            val boundary = if (first) 0x2 else 0x1
            record(BtSnoopFile.TYPE_ACL, incoming, le16(handle or (boundary shl 12)) + le16(chunk.size) + chunk)
            offset += chunk.size
            first = false
        }
    }

    fun signaling(
        handle: Int,
        incoming: Boolean,
        code: Int,
        id: Int,
        body: ByteArray,
    ) = l2cap(handle, incoming, 0x0001, byteArrayOf(code.toByte(), id.toByte()) + le16(body.size) + body)

    fun connectionRequestL2cap(
        handle: Int,
        incoming: Boolean,
        id: Int,
        psm: Int,
        sourceCid: Int,
    ) = signaling(handle, incoming, 0x02, id, le16(psm) + le16(sourceCid))

    fun connectionResponseL2cap(
        handle: Int,
        incoming: Boolean,
        id: Int,
        destinationCid: Int,
        sourceCid: Int,
        result: Int = 0,
    ) = signaling(handle, incoming, 0x03, id, le16(destinationCid) + le16(sourceCid) + le16(result) + le16(0))

    fun sdp(
        handle: Int,
        incoming: Boolean,
        cid: Int,
        pduId: Int,
        params: ByteArray,
        fragment: Int = 1021,
    ) = l2cap(handle, incoming, cid, byteArrayOf(pduId.toByte(), 0, 1) + be16(params.size) + params, fragment)

    companion object {
        val CAR = byteArrayOf(0x66, 0x55, 0x44, 0x33, 0x22, 0x11)

        fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

        fun le24(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())

        fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        fun be32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        fun be64(value: Long) = be32(value ushr 32) + be32(value and 0xffffffffL)

        fun uuidBytes(uuid: UUID): ByteArray =
            be32(uuid.mostSignificantBits ushr 32) + be32(uuid.mostSignificantBits) +
                be32(uuid.leastSignificantBits ushr 32) + be32(uuid.leastSignificantBits)

        // ---- SDP 数据元素
        fun des(vararg items: ByteArray): ByteArray {
            val body = items.fold(ByteArray(0)) { acc, item -> acc + item }
            return if (body.size < 256) byteArrayOf(0x35, body.size.toByte()) + body else byteArrayOf(0x36) + be16(body.size) + body
        }

        fun uint8(value: Int) = byteArrayOf(0x08, value.toByte())

        fun uint16(value: Int) = byteArrayOf(0x09) + be16(value)

        fun uint32(value: Long) = byteArrayOf(0x0a) + be32(value)

        fun uuid16(value: Int) = byteArrayOf(0x19) + be16(value)

        fun uuid128(uuid: UUID) = byteArrayOf(0x1c) + uuidBytes(uuid)

        fun text(value: String): ByteArray {
            val bytes = value.toByteArray()
            return byteArrayOf(0x25, bytes.size.toByte()) + bytes
        }

        /** 一条 RFCOMM 服务记录（属性 0x0000/0x0001/0x0004/0x0100）。 */
        fun serviceRecord(
            handle: Long,
            serviceClass: ByteArray,
            channel: Int,
            name: String,
        ) = des(
            uint16(0x0000),
            uint32(handle),
            uint16(0x0001),
            des(serviceClass),
            uint16(0x0004),
            des(des(uuid16(0x0100)), des(uuid16(0x0003), uint8(channel))),
            uint16(0x0100),
            text(name),
        )

        /** EIR：完整名称 + 16 位列表 + 128 位列表（小端）。 */
        fun eir(
            name: String,
            uuid16: List<Int>,
            uuid128: List<UUID>,
            uuid128Complete: Boolean = true,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val nameBytes = name.toByteArray()
            out.write(byteArrayOf((nameBytes.size + 1).toByte(), 0x09))
            out.write(nameBytes)
            out.write(byteArrayOf((uuid16.size * 2 + 1).toByte(), 0x03))
            uuid16.forEach { out.write(le16(it)) }
            out.write(byteArrayOf((uuid128.size * 16 + 1).toByte(), if (uuid128Complete) 0x07 else 0x06))
            uuid128.forEach { out.write(uuidBytes(it).reversedArray()) }
            return out.toByteArray().copyOf(240)
        }
    }
}
