package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class UsbMuxConnectionTableTest {
    private val frames = mutableListOf<ByteArray>()
    private val opened = mutableListOf<UsbMuxTcpConnection>()
    private val table = UsbMuxConnectionTable({ it == 62078 }, frames::add, opened::add)

    @Test
    fun unsupportedPortGetsResetInsteadOfSynAck() {
        table.reset(true)
        table.accept(segment(UsbMuxProtocol.TCP_SYN, destination = 123))
        assertTrue(opened.isEmpty())
        val reset = UsbMuxTcpSegment.parse(UsbMuxProtocol.parsePayload(frames.single()))
        assertEquals(UsbMuxProtocol.TCP_RST or UsbMuxProtocol.TCP_ACK, reset.flags)
        assertEquals(1, reset.acknowledgement)
    }

    @Test
    fun resetReleasesConnectionAndAllowsFreshSynOnSamePorts() {
        table.reset(true)
        table.accept(segment(UsbMuxProtocol.TCP_SYN))
        val first = opened.single()
        table.accept(segment(UsbMuxProtocol.TCP_RST))
        assertEquals(1, frames.size) // Never answer an RST with another RST.
        assertTrue(runCatching { first.receive(1, 100) }.isFailure)
        table.accept(segment(UsbMuxProtocol.TCP_SYN))
        table.remove(first) // A delayed old worker must not remove the replacement.
        table.accept(segment(UsbMuxProtocol.TCP_ACK, sequence = 1, payload = byteArrayOf(7)))
        assertEquals(
            7,
            opened
                .last()
                .receive(1, 100)!!
                .single()
                .toInt(),
        )
    }

    @Test
    fun teardownAndAcceptAreSerializedAndStopRejectsNewConnections() {
        table.reset(true)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val workers =
            listOf(
                thread {
                    try {
                        start.await()
                        repeat(500) { table.accept(segment(UsbMuxProtocol.TCP_SYN)) }
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                },
                thread {
                    try {
                        start.await()
                        repeat(500) { table.reset(it % 2 == 0) }
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                },
            )
        start.countDown()
        workers.forEach { it.join(3000) }
        workers.forEach { assertFalse(it.isAlive) }
        assertNull(failure.get())
        table.reset(false)
        val count = opened.size
        table.accept(segment(UsbMuxProtocol.TCP_SYN))
        assertEquals(count, opened.size)
        opened.forEach { assertTrue(it.receive(1, 100)!!.isEmpty()) }
    }

    private fun segment(
        flags: Int,
        destination: Int = 62078,
        sequence: Int = 0,
        payload: ByteArray = byteArrayOf(),
    ) = UsbMuxTcpSegment.parse(
        UsbMuxProtocol.parsePayload(
            UsbMuxProtocol.tcpFrame(1, destination, sequence, 0, flags, payload = payload),
        ),
    )
}
