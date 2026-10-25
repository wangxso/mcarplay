package com.mcarplay.app.carlife

import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A typed CarLife frame with its numbered loopback channel. */
data class CarLifeFrame(
    val channel: Int,
    val kind: Int,
    val timestamp: Int,
    val reserved: Int,
    val payload: ByteArray,
)

internal fun readCarLifeSocket(
    input: InputStream,
    port: Int,
): CarLifeFrame {
    val headerSize = CarLifeProtocol.videoHeaderSize(port)
    val header = readExactBytes(input, headerSize)
    val length: Int
    val timestamp: Int
    val kind: Int
    val reserved: Int
    if (headerSize == 12) {
        val decoded = CarLifeProtocol.decodeVideoHeader(header)
        length = decoded.first
        timestamp = decoded.second
        kind = decoded.third
        reserved = 0
    } else {
        val decoded = CarLifeProtocol.decodeCommandHeader(header)
        length = decoded.first
        kind = decoded.second
        timestamp = 0
        reserved = header[2].toInt() and 0xFF shl 8 or (header[3].toInt() and 0xFF)
    }
    return CarLifeFrame(
        channel = port,
        kind = kind,
        timestamp = timestamp,
        reserved = reserved,
        payload = readExactBytes(input, length),
    )
}

internal fun readExactBytes(
    input: InputStream,
    count: Int,
): ByteArray {
    val output = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = input.read(output, offset, count - offset)
        if (read < 0) throw EOFException("CarLife socket closed")
        offset += read
    }
    return output
}

/** Injectable single-attempt connect hook, kept independent for reconnect tests. */
internal fun connectCarLifeSocket(
    host: String,
    port: Int,
    timeoutMs: Int,
): Socket =
    Socket().apply {
        connect(InetSocketAddress(host, port), timeoutMs)
        tcpNoDelay = true
        soTimeout = 0
    }
