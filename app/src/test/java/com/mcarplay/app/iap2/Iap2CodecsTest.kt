package com.mcarplay.app.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Iap2CodecsTest {
    // ---- 链路层 ----

    @Test
    fun linkPacketChecksumSumsToZero() {
        val packet =
            Iap2LinkPacket.encode(
                Iap2LinkPacket.LinkPacket(
                    control = Iap2LinkPacket.CTRL_ACK,
                    seq = 3,
                    ack = 7,
                    sessionId = Iap2LinkPacket.SESSION_CONTROL,
                    payload = byteArrayOf(0x40, 0x40, 0x00, 0x08, 0x1D, 0x00),
                ),
            )
        assertTrue(Iap2LinkPacket.verify(packet))
    }

    @Test
    fun linkPacketRoundTrip() {
        val original =
            Iap2LinkPacket.LinkPacket(
                control = 0xC0,
                seq = 11,
                ack = 22,
                sessionId = 1,
                payload = "hello iap2".encodeToByteArray(),
            )
        val decoded = Iap2LinkPacket.decode(Iap2LinkPacket.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun linkPacketRejectsCorruptedChecksum() {
        val bytes =
            Iap2LinkPacket.encode(
                Iap2LinkPacket.LinkPacket(0x40, 1, 1, 0, byteArrayOf(1, 2, 3)),
            )
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        assertNull(Iap2LinkPacket.decode(bytes))
    }

    // ---- CSM 分帧 ----

    @Test
    fun csmFrameHeaderIs4040LengthAndId() {
        val frame = Iap2Csm.encode(Iap2MessageIds.START_IDENTIFICATION)
        assertEquals(0x40, frame[0].toInt() and 0xFF)
        assertEquals(0x40, frame[1].toInt() and 0xFF)
        assertEquals(frame.size, ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF))
        assertEquals(
            Iap2MessageIds.START_IDENTIFICATION,
            ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF),
        )
    }

    @Test
    fun csmParserReassemblesFramesAcrossChunks() {
        val frameA = Iap2Csm.encode(0x1D00)
        val frameB = Iap2Csm.encode(0x1D02, Iap2Csm.u8(1, 2))
        val stream = frameA + frameB
        val parser = Iap2Csm.Parser()

        // 按字节喂入，模拟最极端的分片
        val frames = stream.flatMap { byte -> parser.offer(byteArrayOf(byte)) }

        assertEquals(2, frames.size)
        assertEquals(0x1D00, frames[0].messageId)
        assertEquals(0x1D02, frames[1].messageId)
        assertArrayEquals(frameA, Iap2Csm.encode(frames[0].messageId, frames[0].body))
    }

    @Test
    fun csmParserSkipsGarbageBeforeFrame() {
        val parser = Iap2Csm.Parser()
        val frames = parser.offer(byteArrayOf(0x12, 0x34, 0x56) + Iap2Csm.encode(0x4300))
        assertEquals(1, frames.size)
        assertEquals(0x4300, frames[0].messageId)
    }

    // ---- TLV 参数体 ----

    @Test
    fun tlvParamsRoundTrip() {
        val body =
            Iap2Csm.group(
                17,
                Iap2Csm.u16(0, 0),
                Iap2Csm.string(1, "blue"),
                Iap2Csm.param(3, byteArrayOf(1, 2, 3, 4, 5, 6)),
            ) + Iap2Csm.string(0, "Sonata") + Iap2Csm.u16List(6, intArrayOf(0x5000, 0x4301))

        val params = Iap2Csm.parseParams(body)
        assertEquals(3, params.size)
        assertEquals(17, params[0].id)
        assertEquals("Sonata", params[1].asString())
        assertEquals(listOf(0x5000, 0x4301), params[2].asU16List())

        val children = Iap2Csm.parseParams(params[0].value)
        assertEquals(3, children.size)
        assertEquals(0, children[0].id)
        assertEquals("blue", children[1].asString())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), children[2].value)
    }

    @Test
    fun stringParamRejectsEmbeddedNul() {
        try {
            Iap2Csm.string(1, "bad" + 0.toChar())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
