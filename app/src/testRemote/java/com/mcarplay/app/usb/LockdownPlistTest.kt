package com.mcarplay.app.usb

import com.mcarplay.app.airplay.BinaryPlist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class LockdownPlistTest {
    @Test
    fun roundTripsNestedLockdownDictionaryAndData() {
        val message =
            LockdownPlist.Value.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlist.Value.Text("mcarplay"),
                    "Request" to LockdownPlist.Value.Text("Pair"),
                    "ProtocolVersion" to LockdownPlist.Value.Integer(2),
                    "PairingOptions" to
                        LockdownPlist.Value.Dictionary(
                            linkedMapOf("ExtendedPairingErrors" to LockdownPlist.Value.Boolean(true)),
                        ),
                    "PairRecord" to
                        LockdownPlist.Value.Dictionary(
                            linkedMapOf("RootCertificate" to LockdownPlist.Value.Data(byteArrayOf(1, 2, 3, 4))),
                        ),
                ),
            )

        val frame = LockdownPlist.encodeFrame(message)
        val parsed = LockdownPlist.parseFrame(frame)
        assertEquals(message, parsed)
        assertEquals(parsed, LockdownPlist.readFrame(ByteArrayInputStream(frame)))
    }

    @Test
    fun rejectsLengthPrefixThatDoesNotMatchFrame() {
        val frame = LockdownPlist.encodeFrame(LockdownPlist.Value.Dictionary(emptyMap()))
        assertTrue(runCatching { LockdownPlist.parseFrame(frame.copyOf(frame.size - 1)) }.isFailure)
    }

    @Test
    fun decodesIphoneStyleWhitespaceInData() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
              <key>DevicePublicKey</key><data>AQID
              BAQ=</data>
            </dict></plist>
            """.trimIndent().toByteArray()
        val frame =
            ByteArray(4 + xml.size).also {
                UsbMuxProtocol.putU32(it, 0, xml.size)
                xml.copyInto(it, 4)
            }
        val value =
            (LockdownPlist.parseFrame(frame).entries["DevicePublicKey"] as LockdownPlist.Value.Data)
                .bytes
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 4), value)
    }

    @Test
    fun decodesBinaryPlistLockdownRequests() {
        val payload =
            BinaryPlist.encode(
                linkedMapOf(
                    "Request" to "StartSession",
                    "HostID" to "host-id",
                    "ProtocolVersion" to 2L,
                    "Options" to listOf("one", "two"),
                    "Enabled" to true,
                    "Certificate" to byteArrayOf(1, 2, 3),
                ),
            )
        val frame =
            ByteArray(4 + payload.size).also {
                UsbMuxProtocol.putU32(it, 0, payload.size)
                payload.copyInto(it, 4)
            }

        val parsed = LockdownPlist.parseFrame(frame)

        assertEquals("StartSession", (parsed.entries["Request"] as LockdownPlist.Value.Text).value)
        assertEquals("host-id", (parsed.entries["HostID"] as LockdownPlist.Value.Text).value)
        assertEquals(2L, (parsed.entries["ProtocolVersion"] as LockdownPlist.Value.Integer).value)
        assertEquals(
            listOf(
                LockdownPlist.Value.Text("one"),
                LockdownPlist.Value.Text("two"),
            ),
            (parsed.entries["Options"] as LockdownPlist.Value.Array).values,
        )
        assertEquals(true, (parsed.entries["Enabled"] as LockdownPlist.Value.Boolean).value)
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            (parsed.entries["Certificate"] as LockdownPlist.Value.Data).bytes,
        )
    }
}
