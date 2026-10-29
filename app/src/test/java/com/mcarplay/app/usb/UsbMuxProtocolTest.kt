package com.mcarplay.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMuxProtocolTest {
    @Test
    fun versionReplyMatchesIphoneHandshake() {
        assertArrayEquals(
            byteArrayOf(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                20,
                0,
                0,
                0,
                2,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            UsbMuxProtocol.versionReply(),
        )
        assertTrue(UsbMuxProtocol.isVersionMessage(UsbMuxProtocol.versionReply()))
        assertFalse(UsbMuxProtocol.isVersionMessage(UsbMuxProtocol.versionReply().copyOf(19)))
    }

    @Test
    fun decoderRetainsFragmentedAndCoalescedFrames() {
        val decoder = UsbMuxFrameDecoder()
        val setup = UsbMuxProtocol.frame(UsbMuxProtocol.PROTOCOL_SETUP, byteArrayOf(0x07), sequence = 7)
        val tcp =
            UsbMuxProtocol.tcpFrame(
                sourcePort = 12_345,
                destinationPort = 62_078,
                sequence = 99,
                acknowledgement = 0,
                flags = UsbMuxProtocol.TCP_SYN,
            )

        decoder.append(setup.copyOfRange(0, 9))
        assertNull(decoder.nextFrame())
        decoder.append(setup.copyOfRange(9, setup.size) + tcp)

        val first = decoder.nextFrame()!!
        assertEquals(UsbMuxProtocol.PROTOCOL_SETUP, first.protocol)
        assertEquals(7, first.sequence)
        assertArrayEquals(byteArrayOf(0x07), first.payload)

        val second = decoder.nextFrame()!!
        assertEquals(UsbMuxProtocol.PROTOCOL_TCP, second.protocol)
        val segment = UsbMuxTcpSegment.parse(second.payload)
        assertEquals(12_345, segment.sourcePort)
        assertEquals(62_078, segment.destinationPort)
        assertEquals(99, segment.sequence)
        assertEquals(UsbMuxProtocol.TCP_SYN, segment.flags)
        assertNull(decoder.nextFrame())
    }

    @Test
    fun decoderRejectsOversizedFrameBeforeBufferingIt() {
        val decoder = UsbMuxFrameDecoder(maximumFrameBytes = 64)
        decoder.append(ByteArray(16).also { UsbMuxProtocol.putU32(it, 4, 65) })
        assertTrue(runCatching { decoder.nextFrame() }.isFailure)
    }

    @Test
    fun frameSequenceNumbersOutgoingPacketsAndEchoesHostSequence() {
        val sequence = UsbMuxFrameSequence()
        val frame =
            UsbMuxProtocol.tcpFrame(
                sourcePort = 62_078,
                destinationPort = 4_864,
                sequence = 1,
                acknowledgement = 1,
                flags = UsbMuxProtocol.TCP_SYN or UsbMuxProtocol.TCP_ACK,
            )

        sequence.acceptInbound(1)
        val first = sequence.stampOutbound(frame)
        sequence.acceptInbound(2)
        val second = sequence.stampOutbound(frame)

        assertEquals(0, UsbMuxProtocol.readU16(first, 12))
        assertEquals(1, UsbMuxProtocol.readU16(first, 14))
        assertEquals(1, UsbMuxProtocol.readU16(second, 12))
        assertEquals(2, UsbMuxProtocol.readU16(second, 14))
        assertEquals(0, UsbMuxProtocol.readU16(frame, 12))
    }

    @Test
    fun frameSequenceResetsForANewHandshakeAndWrapsAtUint16() {
        val sequence = UsbMuxFrameSequence()
        val frame =
            UsbMuxProtocol.frame(
                protocol = UsbMuxProtocol.PROTOCOL_TCP,
                payload = ByteArray(UsbMuxProtocol.TCP_HEADER_BYTES),
            )

        repeat(65_536) {
            sequence.acceptInbound(it and 0xFFFF)
            sequence.stampOutbound(frame)
        }
        assertEquals(0, sequence.transmitSequence)

        sequence.reset()
        sequence.acceptInbound(7)
        val afterReset = sequence.stampOutbound(frame)
        assertEquals(0, UsbMuxProtocol.readU16(afterReset, 12))
        assertEquals(7, UsbMuxProtocol.readU16(afterReset, 14))
    }
}
