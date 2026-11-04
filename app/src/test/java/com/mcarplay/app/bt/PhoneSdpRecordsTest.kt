package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.UUID

class PhoneSdpRecordsTest {
    private class Record(
        private val failClose: Boolean = false,
    ) : Closeable {
        var closed = false

        override fun close() {
            closed = true
            if (failClose) throw IOException("close failed")
        }
    }

    @Test
    fun advertisesCarPlayCapabilityAlongsideExistingIap2Transport() {
        val advertised = mutableListOf<Pair<String, UUID>>()
        val records =
            PhoneSdpRecords.open { name, uuid ->
                advertised += name to uuid
                Record()
            }
        assertEquals(
            listOf(
                "iAP2" to UUID.fromString("00000000-deca-fade-deca-deafdecacafe"),
                "CarPlay" to UUID.fromString("2d8d2466-e14d-451c-88bc-7301abea291a"),
            ),
            advertised,
        )
        assertFalse(records.iap2.closed)
        assertFalse(records.carPlay.closed)
        records.close()
        assertTrue(records.iap2.closed)
        assertTrue(records.carPlay.closed)
    }

    @Test
    fun rollsBackTransportWhenCapabilityRegistrationFails() {
        val transport = Record()
        val failure = IOException("no SDP slots")
        try {
            PhoneSdpRecords.open { _, uuid ->
                if (uuid == PhoneSdpRecords.IPHONE_CARPLAY_UUID) throw failure
                transport
            }
            fail("registration should fail")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        assertTrue(transport.closed)
    }

    @Test
    fun releasesTransportEvenWhenCapabilityCloseFails() {
        val records = PhoneSdpRecords.open { _, uuid -> Record(failClose = uuid == PhoneSdpRecords.IPHONE_CARPLAY_UUID) }
        records.close()
        assertTrue(records.iap2.closed)
        assertTrue(records.carPlay.closed)
    }
}
