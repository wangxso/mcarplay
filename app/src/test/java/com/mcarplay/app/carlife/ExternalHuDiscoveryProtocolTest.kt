package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExternalHuDiscoveryProtocolTest {
    private fun identity(
        name: String = "HU-TEST",
        mac: String = "02:11:22:33:44:55",
        address: String = "192.168.49.1",
    ): ExternalHuDiscoveryProtocol.Identity =
        ExternalHuDiscoveryProtocol.Identity(
            deviceName = name,
            macAddress = mac,
            interfaceName = "p2p-wlan0-0",
            ipv4Address = InetAddress.getByName(address) as Inet4Address,
        )

    @Test
    fun instanceNameMustStartWithEasyConnIgnoringCase() {
        assertTrue(ExternalHuDiscoveryProtocol.isMatchingInstanceName("EasyConn-mcarplay"))
        assertTrue(ExternalHuDiscoveryProtocol.isMatchingInstanceName("easyconn"))
        assertFalse(ExternalHuDiscoveryProtocol.isMatchingInstanceName("Other-EasyConn"))
        assertFalse(ExternalHuDiscoveryProtocol.isMatchingInstanceName("easy-connect"))
    }

    @Test
    fun serviceIdentityUsesActualP2pValuesAndRequiredTxtKeys() {
        val identity = identity()
        val service =
            ExternalHuDiscoveryProtocol.serviceIdentity(
                identity,
                instanceName = ExternalHuDiscoveryProtocol.buildInstanceName(identity.deviceName, "fallback"),
            )

        assertEquals("EasyConn-HU-TEST", service.instanceName)
        assertEquals("192.168.49.1", service.ipv4Address?.hostAddress)
        assertEquals(
            setOf("packagename", "ec_name", "huid", "channel", "flavor", "huname"),
            ExternalHuDiscoveryProtocol.txtRecord(service).keys,
        )
        assertEquals("HU-TEST", ExternalHuDiscoveryProtocol.txtRecord(service)["ec_name"])
        assertEquals(
            "02:11:22:33:44:55",
            ExternalHuDiscoveryProtocol.txtRecord(service)["huid"],
        )
    }

    @Test
    fun placeholderOrMissingP2pIpCannotBePublished() {
        assertNull(
            ExternalHuDiscoveryProtocol
                .serviceIdentity(identity(mac = "02:00:00:00:00:00"))
                .ipv4Address,
        )
        assertNull(
            ExternalHuDiscoveryProtocol
                .serviceIdentity(identity(name = ""))
                .ipv4Address,
        )
    }

    @Test
    fun identityDiagnosticsDistinguishPrivacyRedactionFromOtherMissingFields() {
        assertEquals("mac-redacted", identity(mac = "02:00:00:00:00:00").unavailableReason())
        assertEquals("device-name-pending", identity(name = "").unavailableReason())
        assertEquals("local-interface-pending", identity().copy(interfaceName = "").unavailableReason())
        assertNull(identity().unavailableReason())
    }

    @Test
    fun groupMustBeFormedOwnedAndHaveRealIdentity() {
        assertEquals(
            ExternalHuDiscoveryProtocol.GroupState.FORMING,
            ExternalHuDiscoveryProtocol.evaluateGroup(false, false, true).state,
        )
        assertEquals(
            ExternalHuDiscoveryProtocol.GroupState.NOT_OWNER,
            ExternalHuDiscoveryProtocol.evaluateGroup(true, false, true).state,
        )
        assertEquals(
            ExternalHuDiscoveryProtocol.GroupState.FORMING,
            ExternalHuDiscoveryProtocol.evaluateGroup(true, true, false).state,
        )
        assertEquals(
            ExternalHuDiscoveryProtocol.GroupState.READY,
            ExternalHuDiscoveryProtocol.evaluateGroup(true, true, true, "DIRECT-test").state,
        )
        assertEquals(
            ExternalHuDiscoveryProtocol.GroupState.FORMING,
            ExternalHuDiscoveryProtocol.evaluateGroup(true, true, true, " ").state,
        )
    }

    @Test
    fun requestHeaderUsesLittleEndianLengthAndXorChecksum() {
        val request = ExternalHuDiscoveryProtocol.encodeRequest()
        val buffer = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(ExternalHuDiscoveryProtocol.REQUEST_COMMAND, buffer.int)
        assertEquals(16, buffer.int)
        assertEquals(16 xor ExternalHuDiscoveryProtocol.REQUEST_COMMAND, buffer.int)
        assertEquals(0, buffer.int)
    }

    @Test
    fun incompleteOrWrongCommandIsNotAcceptedAsRequest() {
        val request = ExternalHuDiscoveryProtocol.encodeRequest()
        assertNull(ExternalHuDiscoveryProtocol.decodeRequest(request.copyOf(15)))
        assertNull(ExternalHuDiscoveryProtocol.decodeRequest(ExternalHuDiscoveryProtocol.encodeFrame(0x70000011)))
        assertNotNull(ExternalHuDiscoveryProtocol.decodeRequest(request))
    }

    @Test
    fun responseUsesCommandAndStringTrue() {
        val frame = ExternalHuDiscoveryProtocol.decodeFrame(ExternalHuDiscoveryProtocol.encodeResponse())

        assertEquals(0x70000011, frame?.kind)
        assertEquals("true", org.json.JSONObject(String(frame?.body ?: ByteArray(0))).optString("status"))
    }

    @Test
    fun corruptChecksumIsRejected() {
        val response = ExternalHuDiscoveryProtocol.encodeResponse()
        response[7] = 0x7f

        assertTrue(
            runCatching { ExternalHuDiscoveryProtocol.decodeFrame(response) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
