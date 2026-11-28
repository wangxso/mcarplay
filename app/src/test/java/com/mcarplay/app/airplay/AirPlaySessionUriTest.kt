package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlaySessionUriTest {
    @Test
    fun buildsIpv4AndIpv6AuthoritiesWithRfc6874ZoneEscaping() {
        assertEquals("rtsp://192.0.2.1/42", AirPlaySessionUri.build("192.0.2.1", 42))
        assertEquals(
            "rtsp://[fe80::1%25wlan0]/42",
            AirPlaySessionUri.build("fe80::1%wlan0", 42),
        )
        assertEquals(
            "rtsp://[fe80::1%25wlan0]/42",
            AirPlaySessionUri.build("[fe80::1%wlan0]", 42),
        )
    }

    @Test
    fun rejectsMissingOrUnsafeHosts() {
        assertNull(AirPlaySessionUri.build(null, 42))
        assertNull(AirPlaySessionUri.build("   ", 42))
        assertNull(AirPlaySessionUri.build("host/path", 42))
        assertNull(AirPlaySessionUri.build("host\r\nInjected: yes", 42))
        assertNull(AirPlaySessionUri.build("user@host", 42))
    }

    @Test
    fun resolvesOnlyTheSessionScopedRequests() {
        val uri = "rtsp://[fe80::1%25wlan0]/42"
        assertEquals(uri, AirPlaySessionUri.resolve(uri, "SETUP", "/setup"))
        assertEquals(uri, AirPlaySessionUri.resolve(uri, "SETUP", "/"))
        assertEquals(uri, AirPlaySessionUri.resolve(uri, "RECORD", "/"))
        assertEquals(uri, AirPlaySessionUri.resolve(uri, "TEARDOWN", "/"))
        assertEquals("/info", AirPlaySessionUri.resolve(uri, "GET", "/info"))
        assertEquals("/command", AirPlaySessionUri.resolve(uri, "POST", "/command"))
        assertEquals("/feedback", AirPlaySessionUri.resolve(uri, "POST", "/feedback"))
        assertEquals("rtsp://192.0.2.1:7000/", AirPlaySessionUri.resolve(uri, "SETUP", "rtsp://192.0.2.1:7000/"))
        assertEquals("/other", AirPlaySessionUri.resolve(uri, "TEARDOWN", "/other"))
    }

    @Test
    fun withoutSessionUriEveryPathStaysRelative() {
        assertEquals("/setup", AirPlaySessionUri.resolve(null, "SETUP", "/setup"))
        assertEquals("/", AirPlaySessionUri.resolve(null, "RECORD", "/"))
    }
}
