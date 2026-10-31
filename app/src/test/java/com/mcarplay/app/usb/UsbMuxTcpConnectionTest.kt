package com.mcarplay.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class UsbMuxTcpConnectionTest {
    private val frames = mutableListOf<ByteArray>()

    @Test
    fun acknowledgesRequestAndSendsSequencedPayload() {
        val connection =
            UsbMuxTcpConnection(
                sourcePort = 1,
                destinationPort = 62_078,
                writeFrame = frames::add,
            )

        connection.accept(segment(flags = UsbMuxProtocol.TCP_SYN, sequence = 0))
        connection.accept(segment(flags = UsbMuxProtocol.TCP_ACK, sequence = 1, payload = byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), connection.receive(16, 100))

        connection.send(byteArrayOf(4, 5))

        val synAck = tcp(frames[0])
        assertEquals(UsbMuxProtocol.TCP_SYN or UsbMuxProtocol.TCP_ACK, synAck.flags)
        assertEquals(1, synAck.acknowledgement)

        val ack = tcp(frames[1])
        assertEquals(UsbMuxProtocol.TCP_ACK, ack.flags)
        assertEquals(4, ack.acknowledgement)

        val reply = tcp(frames[2])
        assertEquals(1, reply.sequence)
        assertEquals(4, reply.acknowledgement)
        assertArrayEquals(byteArrayOf(4, 5), reply.payload)
    }

    @Test
    fun completesPureAckBeforeWakingProtocolReader() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val ackWriteStarted = CountDownLatch(1)
        val releaseAckWrite = CountDownLatch(1)
        val connection =
            UsbMuxTcpConnection(
                sourcePort = 1,
                destinationPort = 62_078,
                writeFrame = { frame ->
                    val tcp = tcp(frame)
                    if (tcp.flags == UsbMuxProtocol.TCP_ACK && tcp.payload.isEmpty()) {
                        ackWriteStarted.countDown()
                        assertTrue(releaseAckWrite.await(1, TimeUnit.SECONDS))
                        events += "ack"
                    }
                    frames += frame
                },
            )
        connection.accept(segment(flags = UsbMuxProtocol.TCP_SYN, sequence = 0))

        val received = AtomicReference<ByteArray?>()
        val reader =
            thread(name = "usbmux-test-reader") {
                received.set(connection.receive(16, 1_000))
                events += "reader"
            }
        val acceptFailure = AtomicReference<Throwable?>()
        val acceptor =
            thread(name = "usbmux-test-acceptor") {
                try {
                    connection.accept(
                        segment(
                            flags = UsbMuxProtocol.TCP_ACK,
                            sequence = 1,
                            payload = byteArrayOf(1, 2, 3),
                        ),
                    )
                } catch (error: Throwable) {
                    acceptFailure.set(error)
                }
            }

        assertTrue(ackWriteStarted.await(1, TimeUnit.SECONDS))
        assertNull(received.get())
        assertTrue(reader.isAlive)
        releaseAckWrite.countDown()
        acceptor.join(1_000)
        reader.join(1_000)

        assertNull(acceptFailure.get())
        assertArrayEquals(byteArrayOf(1, 2, 3), received.get())
        assertEquals(listOf("ack", "reader"), events)
    }

    @Test
    fun dataIsEnqueuedBeforeAckUsingItsAdvancedSequence() {
        val dataStarted = CountDownLatch(1)
        val releaseData = CountDownLatch(1)
        val ackStarted = CountDownLatch(1)
        val ackQueued = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val connection =
            UsbMuxTcpConnection(1, 62078, { frame ->
                val packet = tcp(frame)
                if (packet.payload.isNotEmpty()) {
                    dataStarted.countDown()
                    check(releaseData.await(2, TimeUnit.SECONDS))
                    events += "data"
                } else if (packet.flags == UsbMuxProtocol.TCP_ACK) {
                    events += "ack:${packet.sequence}"
                    ackQueued.countDown()
                }
            })
        connection.accept(segment(UsbMuxProtocol.TCP_SYN, 0))
        val sender =
            thread {
                try {
                    connection.send(byteArrayOf(42))
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        assertTrue(dataStarted.await(1, TimeUnit.SECONDS))
        val acceptor =
            thread {
                try {
                    ackStarted.countDown()
                    connection.accept(segment(UsbMuxProtocol.TCP_ACK, 1, byteArrayOf(7)))
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(ackStarted.await(1, TimeUnit.SECONDS))
            assertFalse("ACK must not overtake data", ackQueued.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseData.countDown()
            sender.join(2000)
            acceptor.join(2000)
            connection.close()
        }
        assertNull(failure.get())
        assertFalse(sender.isAlive)
        assertFalse(acceptor.isAlive)
        assertEquals(listOf("data", "ack:2"), events)
    }

    @Test
    fun peerResetWakesBlockedReceiverAndRejectsFurtherSends() {
        val connection = UsbMuxTcpConnection(1, 62078, frames::add)
        connection.accept(segment(UsbMuxProtocol.TCP_SYN, 0))
        val reading = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader =
            thread {
                reading.countDown()
                try {
                    connection.receive(16, 10000)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        assertTrue(reading.await(1, TimeUnit.SECONDS))
        connection.accept(segment(UsbMuxProtocol.TCP_RST, 1, byteArrayOf(99)))
        reader.join(1000)
        assertFalse(reader.isAlive)
        assertTrue(failure.get() is java.io.IOException)
        assertTrue(runCatching { connection.send(byteArrayOf(1)) }.isFailure)
        assertEquals(1, frames.size)
    }

    private fun segment(
        flags: Int,
        sequence: Int,
        payload: ByteArray = byteArrayOf(),
    ): UsbMuxTcpSegment {
        val frame =
            UsbMuxProtocol.tcpFrame(
                sourcePort = 1,
                destinationPort = 62_078,
                sequence = sequence,
                acknowledgement = 0,
                flags = flags,
                payload = payload,
            )
        return UsbMuxTcpSegment.parse(UsbMuxProtocol.parsePayload(frame))
    }

    private fun tcp(frame: ByteArray): UsbMuxTcpSegment {
        assertEquals(UsbMuxProtocol.PROTOCOL_TCP, UsbMuxProtocol.readU32(frame, 0))
        return UsbMuxTcpSegment.parse(UsbMuxProtocol.parsePayload(frame))
    }
}
