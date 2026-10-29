package com.mcarplay.app.usb

import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque

/**
 * 一条承载在 USBMUX TCP frame 上的阻塞字节流。
 *
 * USB bulk 读取线程只调用 [accept]，把连续 payload 放入队列；Lockdown/TLS 工作线程用
 * [receive] 阻塞读取，用 [send] 写回。序列号和确认号按每条连接独立维护。
 */
class UsbMuxTcpConnection(
    val sourcePort: Int,
    val destinationPort: Int,
    private val writeFrame: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val lock = Object()
    private val received = ArrayDeque<ByteArray>()
    private var receivedBytes = 0
    private var nextSequence = 1L
    private var expectedReceiveSequence = 1L
    private var inputClosed = false
    private var closed = false
    private var failure: IOException? = null
    private var established = false

    fun accept(segment: UsbMuxTcpSegment) {
        require(segment.sourcePort == sourcePort)
        require(segment.destinationPort == destinationPort)
        if (segment.flags and UsbMuxProtocol.TCP_SYN != 0) {
            acceptSyn(segment)
            return
        }
        if (segment.payload.isNotEmpty()) {
            acceptPayload(segment)
        }
        if (segment.flags and UsbMuxProtocol.TCP_FIN != 0) {
            acceptFin(segment)
        }
    }

    fun send(payload: ByteArray) {
        if (payload.isEmpty()) return
        var offset = 0
        while (offset < payload.size) {
            val count = minOf(MAX_PAYLOAD_BYTES, payload.size - offset)
            sendChunk(payload.copyOfRange(offset, offset + count))
            offset += count
        }
    }

    fun sendControl(flags: Int) {
        val frame =
            synchronized(lock) {
                checkOpenLocked()
                UsbMuxProtocol.tcpFrame(
                    sourcePort = destinationPort,
                    destinationPort = sourcePort,
                    sequence = nextSequence.toInt(),
                    acknowledgement = expectedReceiveSequence.toInt(),
                    flags = flags,
                )
            }
        writeFrame(frame)
    }

    fun receive(
        maximumBytes: Int,
        timeoutMillis: Long,
    ): ByteArray? {
        require(maximumBytes > 0)
        require(timeoutMillis > 0)
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(lock) {
            while (true) {
                failure?.let { throw it }
                if (receivedBytes > 0) return takeLocked(maximumBytes)
                if (inputClosed || closed) return ByteArray(0)
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val remainingMillis = maxOf(1L, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                lock.wait(remainingMillis)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            received.clear()
            receivedBytes = 0
            lock.notifyAll()
        }
    }

    private fun acceptSyn(segment: UsbMuxTcpSegment) {
        synchronized(lock) {
            checkOpenLocked()
            if (established) {
                // Retransmitted SYN still gets the same acknowledgment; the frame sequence can be retried.
            } else {
                established = true
                expectedReceiveSequence = add(segment.sequence.toLong() and UINT32_MASK, 1)
            }
            val frame =
                UsbMuxProtocol.tcpFrame(
                    sourcePort = destinationPort,
                    destinationPort = sourcePort,
                    sequence = (nextSequence - 1).toInt(),
                    acknowledgement = expectedReceiveSequence.toInt(),
                    flags = UsbMuxProtocol.TCP_SYN or UsbMuxProtocol.TCP_ACK,
                )
            log("TCP SYN-ACK $destinationPort->$sourcePort ack=${expectedReceiveSequence and UINT32_MASK}")
            writeFrame(frame)
        }
    }

    private fun acceptPayload(segment: UsbMuxTcpSegment) {
        val payload = segment.payload
        val sequence = segment.sequence.toLong() and UINT32_MASK
        synchronized(lock) {
            checkOpenLocked()
            when {
                sequence == expectedReceiveSequence -> {
                    received += payload.copyOf()
                    receivedBytes += payload.size
                    expectedReceiveSequence = add(sequence, payload.size)
                }
                sequence < expectedReceiveSequence -> {
                    val duplicate = (expectedReceiveSequence - sequence).toInt()
                    if (duplicate < payload.size) {
                        val fresh = payload.copyOfRange(duplicate, payload.size)
                        received += fresh
                        receivedBytes += fresh.size
                        expectedReceiveSequence = add(expectedReceiveSequence, fresh.size)
                    }
                }
                else -> {
                    failure =
                        IOException(
                            "USBMUX TCP gap source=$sourcePort expected=$expectedReceiveSequence received=$sequence",
                        )
                    lock.notifyAll()
                    throw failure!!
                }
            }
            val frame =
                UsbMuxProtocol.tcpFrame(
                    sourcePort = destinationPort,
                    destinationPort = sourcePort,
                    sequence = nextSequence.toInt(),
                    acknowledgement = expectedReceiveSequence.toInt(),
                    flags = UsbMuxProtocol.TCP_ACK,
                )
            try {
                // Wake protocol workers only after the pure ACK is on the wire. Otherwise a response
                // can precede the ACK and make the host treat the response as a duplicate packet.
                writeFrame(frame)
            } catch (error: Throwable) {
                failure = IOException("USBMUX TCP ACK write failed source=$sourcePort", error)
                lock.notifyAll()
                throw failure!!
            }
            lock.notifyAll()
        }
    }

    private fun acceptFin(segment: UsbMuxTcpSegment) {
        val sequence = segment.sequence.toLong() and UINT32_MASK
        val ack =
            synchronized(lock) {
                checkOpenLocked()
                if (sequence == expectedReceiveSequence) {
                    expectedReceiveSequence = add(sequence, 1)
                }
                inputClosed = true
                lock.notifyAll()
                expectedReceiveSequence
            }
        val frame =
            UsbMuxProtocol.tcpFrame(
                sourcePort = destinationPort,
                destinationPort = sourcePort,
                sequence = synchronized(lock) { nextSequence.toInt() },
                acknowledgement = ack.toInt(),
                flags = UsbMuxProtocol.TCP_FIN or UsbMuxProtocol.TCP_ACK,
            )
        writeFrame(frame)
    }

    private fun sendChunk(payload: ByteArray) {
        val sequence: Long
        val acknowledgement: Long
        val frame =
            synchronized(lock) {
                checkOpenLocked()
                sequence = nextSequence
                acknowledgement = expectedReceiveSequence
                nextSequence = add(nextSequence, payload.size)
                UsbMuxProtocol.tcpFrame(
                    sourcePort = destinationPort,
                    destinationPort = sourcePort,
                    sequence = sequence.toInt(),
                    acknowledgement = acknowledgement.toInt(),
                    flags = UsbMuxProtocol.TCP_ACK,
                    payload = payload,
                )
            }
        writeFrame(frame)
        log("TCP TX $destinationPort->$sourcePort seq=$sequence ack=$acknowledgement payload=${payload.size}B")
    }

    private fun takeLocked(maximumBytes: Int): ByteArray {
        val count = minOf(maximumBytes, receivedBytes)
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val head = received.first()
            val take = minOf(head.size, count - offset)
            head.copyInto(result, offset, 0, take)
            offset += take
            if (take == head.size) {
                received.removeFirst()
            } else {
                received.removeFirst()
                received.addFirst(head.copyOfRange(take, head.size))
            }
        }
        receivedBytes -= count
        return result
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IOException("USBMUX TCP connection $sourcePort is closed")
    }

    private fun add(
        value: Long,
        count: Int,
    ): Long = (value + count) and UINT32_MASK

    private companion object {
        const val MAX_PAYLOAD_BYTES = 16 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UINT32_MASK = 0xFFFF_FFFFL
    }
}
