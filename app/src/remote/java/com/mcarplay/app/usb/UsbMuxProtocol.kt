package com.mcarplay.app.usb

/**
 * USBMUX v2 的最小 framing 常量与握手包。
 *
 * 主机在 bulk OUT 上先发送 proto=0、length=20、version=2 的握手，设备在 bulk IN 上回相同内容。
 * 之后协议帧统一使用 16 字节大端头；TCP 帧的 payload 是 20 字节简化 TCP 头加字节流。
 */
object UsbMuxProtocol {
    const val PROTOCOL_VERSION = 0
    const val PROTOCOL_SETUP = 2
    const val PROTOCOL_TCP = 6

    const val VERSION = 2
    const val VERSION_MESSAGE_BYTES = 20
    const val HEADER_BYTES = 16
    const val TCP_HEADER_BYTES = 20
    const val MAX_FRAME_BYTES = 65_536

    const val MUX_MAGIC = 0xFEEDFACE.toInt()
    const val SETUP_VALUE = 0x07

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_ACK = 0x10

    /** 初始 iPhone 模式：`3:3:3:0`，与 usbmuxd 的公开实现一致。 */
    val INITIAL_MODE = byteArrayOf(3, 3, 3, 0)

    fun versionReply(): ByteArray =
        ByteArray(VERSION_MESSAGE_BYTES).also {
            putU32(it, 0, PROTOCOL_VERSION)
            putU32(it, 4, VERSION_MESSAGE_BYTES)
            putU32(it, 8, VERSION)
        }

    fun isVersionMessage(bytes: ByteArray): Boolean =
        bytes.size >= VERSION_MESSAGE_BYTES &&
            readU32(bytes, 0) == PROTOCOL_VERSION &&
            readU32(bytes, 4) == VERSION_MESSAGE_BYTES &&
            readU32(bytes, 8) == VERSION

    fun frame(
        protocol: Int,
        payload: ByteArray,
        sequence: Int = 0,
        acknowledgement: Int = 0,
    ): ByteArray {
        require(protocol >= 0)
        require(payload.size <= MAX_FRAME_BYTES - HEADER_BYTES)
        return ByteArray(HEADER_BYTES + payload.size).also {
            putU32(it, 0, protocol)
            putU32(it, 4, it.size)
            putU32(it, 8, MUX_MAGIC)
            putU16(it, 12, sequence)
            putU16(it, 14, acknowledgement)
            payload.copyInto(it, HEADER_BYTES)
        }
    }

    fun tcpFrame(
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgement: Int,
        flags: Int,
        window: Int = 512,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(sourcePort in 1..0xFFFF)
        require(destinationPort in 1..0xFFFF)
        require(flags in 0..0xFF)
        require(window in 0..0xFFFF)
        val tcp =
            ByteArray(TCP_HEADER_BYTES + payload.size).also {
                putU16(it, 0, sourcePort)
                putU16(it, 2, destinationPort)
                putU32(it, 4, sequence)
                putU32(it, 8, acknowledgement)
                it[12] = ((TCP_HEADER_BYTES / 4) shl 4).toByte()
                it[13] = flags.toByte()
                putU16(it, 14, window)
                payload.copyInto(it, TCP_HEADER_BYTES)
            }
        return frame(PROTOCOL_TCP, tcp)
    }

    fun parsePayload(frame: ByteArray): ByteArray {
        require(frame.size >= HEADER_BYTES) { "USBMUX frame is shorter than its header" }
        val length = readU32(frame, 4)
        require(length == frame.size) { "USBMUX frame length $length does not match ${frame.size} bytes" }
        return frame.copyOfRange(HEADER_BYTES, frame.size)
    }

    fun readU16(
        source: ByteArray,
        offset: Int,
    ): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or
            (source[offset + 1].toInt() and 0xFF)

    fun readU32(
        source: ByteArray,
        offset: Int,
    ): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    fun putU16(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        require(value in 0..0xFFFF)
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    fun putU32(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}

/**
 * USBMUX v2 device-side packet numbering.
 *
 * The device numbers each outgoing frame independently, while `rx_seq` echoes the
 * sequence number from the most recently received host frame. macOS rejects a
 * session whose response frames keep reusing sequence zero.
 */
class UsbMuxFrameSequence {
    private var nextTransmitSequence = 0
    private var lastReceivedSequence = INITIAL_RECEIVE_SEQUENCE

    @Synchronized
    fun reset() {
        nextTransmitSequence = 0
        lastReceivedSequence = INITIAL_RECEIVE_SEQUENCE
    }

    @Synchronized
    fun acceptInbound(sequence: Int) {
        require(sequence in 0..UINT16_MAX)
        lastReceivedSequence = sequence
    }

    /**
     * Returns a copy of [frame] stamped with the next device sequence. Callers must
     * serialize this call with the USB write so packets reach the host in order.
     */
    @Synchronized
    fun stampOutbound(frame: ByteArray): ByteArray {
        require(frame.size >= UsbMuxProtocol.HEADER_BYTES) { "USBMUX frame is shorter than its header" }
        require(UsbMuxProtocol.readU32(frame, 8) == UsbMuxProtocol.MUX_MAGIC) {
            "cannot stamp a USBMUX v1 frame"
        }
        return frame.copyOf().also {
            UsbMuxProtocol.putU16(it, 12, nextTransmitSequence)
            UsbMuxProtocol.putU16(it, 14, lastReceivedSequence)
            nextTransmitSequence = (nextTransmitSequence + 1) and UINT16_MAX
        }
    }

    val transmitSequence: Int
        @Synchronized get() = nextTransmitSequence

    val receiveSequence: Int
        @Synchronized get() = lastReceivedSequence

    private companion object {
        const val INITIAL_RECEIVE_SEQUENCE = 0xFFFF
        const val UINT16_MAX = 0xFFFF
    }
}

/** 可跨多次 bulk read 累积并取出完整 USBMUX 帧。 */
class UsbMuxFrameDecoder(
    private val maximumFrameBytes: Int = UsbMuxProtocol.MAX_FRAME_BYTES,
) {
    private var pending = ByteArray(0)

    init {
        require(maximumFrameBytes >= UsbMuxProtocol.HEADER_BYTES)
    }

    fun append(bytes: ByteArray) {
        // A new version handshake starts a fresh transport even if the old stream ended mid-frame.
        if (UsbMuxProtocol.isVersionMessage(bytes)) reset()
        if (bytes.isNotEmpty()) pending += bytes
    }

    /** 丢弃未成帧的残留字节：新的 bulk 读取线程从流边界重新开始。 */
    fun reset() {
        pending = ByteArray(0)
    }

    fun nextFrame(): UsbMuxFrame? {
        if (pending.size < UsbMuxProtocol.HEADER_BYTES) return null
        val length = UsbMuxProtocol.readU32(pending, 4)
        require(length in UsbMuxProtocol.HEADER_BYTES..maximumFrameBytes) {
            "invalid USBMUX frame length $length"
        }
        if (pending.size < length) return null
        val bytes = pending.copyOf(length)
        pending = pending.copyOfRange(length, pending.size)
        return UsbMuxFrame(
            protocol = UsbMuxProtocol.readU32(bytes, 0),
            length = length,
            magic = UsbMuxProtocol.readU32(bytes, 8),
            sequence = UsbMuxProtocol.readU16(bytes, 12),
            acknowledgement = UsbMuxProtocol.readU16(bytes, 14),
            payload = bytes.copyOfRange(UsbMuxProtocol.HEADER_BYTES, bytes.size),
        )
    }

    val bufferedBytes: Int get() = pending.size
}

data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val magic: Int,
    val sequence: Int,
    val acknowledgement: Int,
    val payload: ByteArray,
)

data class UsbMuxTcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequence: Int,
    val acknowledgement: Int,
    val flags: Int,
    val window: Int,
    val payload: ByteArray,
) {
    companion object {
        fun parse(bytes: ByteArray): UsbMuxTcpSegment {
            require(bytes.size >= UsbMuxProtocol.TCP_HEADER_BYTES) { "USBMUX TCP frame is shorter than its header" }
            val headerBytes = ((bytes[12].toInt() ushr 4) and 0x0F) * 4
            require(headerBytes in UsbMuxProtocol.TCP_HEADER_BYTES..bytes.size) { "invalid USBMUX TCP header length" }
            return UsbMuxTcpSegment(
                sourcePort = UsbMuxProtocol.readU16(bytes, 0),
                destinationPort = UsbMuxProtocol.readU16(bytes, 2),
                sequence = UsbMuxProtocol.readU32(bytes, 4),
                acknowledgement = UsbMuxProtocol.readU32(bytes, 8),
                flags = bytes[13].toInt() and 0xFF,
                window = UsbMuxProtocol.readU16(bytes, 14),
                payload = bytes.copyOfRange(headerBytes, bytes.size),
            )
        }
    }
}
