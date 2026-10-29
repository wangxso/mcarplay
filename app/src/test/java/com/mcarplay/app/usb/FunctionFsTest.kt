package com.mcarplay.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FunctionFsTest {
    private val identity = AppleUsbIdentity(serialNumber = "abc123")

    private fun le32(
        bytes: ByteArray,
        offset: Int,
    ) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)

    @Test
    fun descriptorsUseV2HeaderAndForwardAllControlRecipients() {
        val blob = FunctionFs.iapDescriptors(identity)

        assertEquals(FunctionFs.DESCRIPTORS_MAGIC_V2, le32(blob, 0))
        assertEquals(blob.size, le32(blob, 4))
        val flags = le32(blob, 8)
        assertTrue(flags and FunctionFs.FLAG_ALL_CTRL_RECIP != 0)
        assertTrue(flags and FunctionFs.FLAG_CONFIG0_SETUP != 0)
        assertEquals(FunctionFs.FLAG_HAS_FS_DESC or FunctionFs.FLAG_HAS_HS_DESC, flags and 0x0F)
        assertEquals(3, le32(blob, 12))
        assertEquals(3, le32(blob, 16))
        // 头 20B + 两组 (9 + 7 + 7)
        assertEquals(20 + 2 * 23, blob.size)
    }

    @Test
    fun descriptorsDescribeVendorInterfaceWithBulkPairPerSpeed() {
        val blob = FunctionFs.iapDescriptors(identity)
        val fullSpeedInterface = blob.copyOfRange(20, 29)
        assertArrayEquals(byteArrayOf(9, 4, 0, 0, 2, 0xFF.toByte(), 0xF0.toByte(), 0, 1), fullSpeedInterface)
        // 全速 bulk 包长 64，高速 512
        assertArrayEquals(byteArrayOf(7, 5, 0x81.toByte(), 2, 64, 0, 0), blob.copyOfRange(29, 36))
        assertArrayEquals(byteArrayOf(7, 5, 0x02, 2, 0, 2, 0), blob.copyOfRange(66 - 7, 66))
    }

    @Test
    fun stringsBlockIsNulTerminatedEnglishTable() {
        val blob = FunctionFs.strings("iAP Interface")

        assertEquals(FunctionFs.STRINGS_MAGIC, le32(blob, 0))
        assertEquals(blob.size, le32(blob, 4))
        assertEquals(1, le32(blob, 8))
        assertEquals(1, le32(blob, 12))
        assertEquals(0x09, blob[16].toInt())
        assertEquals(0x04, blob[17].toInt())
        assertEquals("iAP Interface", String(blob, 18, blob.size - 19, Charsets.UTF_8))
        assertEquals(0, blob.last().toInt())
    }

    @Test
    fun parsesSetupAndPlainEventsAndDropsPartialTail() {
        val bytes = ByteArray(FunctionFs.EVENT_SIZE * 2 + 5)
        // 事件 1：BIND
        bytes[8] = 0
        // 事件 2：SETUP 40 51 0100 0000 0000
        val base = FunctionFs.EVENT_SIZE
        bytes[base] = 0x40
        bytes[base + 1] = 0x51
        bytes[base + 2] = 0x01
        bytes[base + 8] = 4

        val events = FunctionFs.parseEvents(bytes)

        assertEquals(2, events.size)
        assertEquals(FfsEventType.BIND, events[0].type)
        assertNull(events[0].setup)
        val setup = events[1].setup!!
        assertEquals(FfsEventType.SETUP, events[1].type)
        assertEquals(0x40, setup.requestType)
        assertEquals(0x51, setup.request)
        assertEquals(1, setup.value)
        assertEquals(2, setup.kind)
        assertEquals(0, setup.recipient)
    }

    @Test
    fun unknownEventCodeIsKept() {
        val bytes = ByteArray(FunctionFs.EVENT_SIZE).also { it[8] = 42 }
        assertEquals(FfsEventType.UNKNOWN, FunctionFs.parseEvents(bytes).single().type)
    }
}
