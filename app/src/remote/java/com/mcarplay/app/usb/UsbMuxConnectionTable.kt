package com.mcarplay.app.usb

/** Serializes transport teardown and incoming TCP dispatch; callbacks only enqueue outgoing frames. */
class UsbMuxConnectionTable(
    private val acceptsPort: (Int) -> Boolean,
    private val writeFrame: (ByteArray) -> Unit,
    private val onConnected: (UsbMuxTcpConnection) -> Unit,
) {
    private val connections = mutableMapOf<Pair<Int, Int>, UsbMuxTcpConnection>()
    private var enabled = false

    @Synchronized
    fun reset(enabled: Boolean) {
        this.enabled = enabled
        connections.values.forEach(UsbMuxTcpConnection::close)
        connections.clear()
    }

    @Synchronized
    fun remove(connection: UsbMuxTcpConnection) {
        val key = connection.sourcePort to connection.destinationPort
        if (connections[key] === connection) connections.remove(key)
    }

    @Synchronized
    fun accept(segment: UsbMuxTcpSegment) {
        if (!enabled) return
        val key = segment.sourcePort to segment.destinationPort
        val existing = connections[key]
        if (segment.flags and UsbMuxProtocol.TCP_RST != 0) {
            existing?.accept(segment)
            connections.remove(key)
            return
        }
        if (existing == null && (segment.flags and UsbMuxProtocol.TCP_SYN == 0 || !acceptsPort(segment.destinationPort))) {
            val hasAck = segment.flags and UsbMuxProtocol.TCP_ACK != 0
            val consumed =
                segment.payload.size +
                    (if (segment.flags and UsbMuxProtocol.TCP_SYN != 0) 1 else 0) +
                    (if (segment.flags and UsbMuxProtocol.TCP_FIN != 0) 1 else 0)
            writeFrame(
                UsbMuxProtocol.tcpFrame(
                    sourcePort = segment.destinationPort,
                    destinationPort = segment.sourcePort,
                    sequence = if (hasAck) segment.acknowledgement else 0,
                    acknowledgement = if (hasAck) 0 else segment.sequence + consumed,
                    flags = UsbMuxProtocol.TCP_RST or if (hasAck) 0 else UsbMuxProtocol.TCP_ACK,
                ),
            )
            return
        }
        val connection = existing ?: UsbMuxTcpConnection(segment.sourcePort, segment.destinationPort, writeFrame)
        connections[key] = connection
        try {
            connection.accept(segment)
            if (existing == null) onConnected(connection)
        } catch (error: Throwable) {
            connection.close()
            connections.remove(key)
            throw error
        }
    }
}
