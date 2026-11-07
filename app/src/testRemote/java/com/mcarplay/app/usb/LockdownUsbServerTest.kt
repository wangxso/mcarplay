package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class LockdownUsbServerTest {
    @Test
    fun queryTypeAdvertisesTheLockdownService() {
        val response = LockdownUsbServer.queryTypeResponse()
        assertEquals("QueryType", (response.entries["Request"] as LockdownPlist.Value.Text).value)
        assertEquals(
            "com.apple.mobile.lockdown",
            (response.entries["Type"] as LockdownPlist.Value.Text).value,
        )
    }

    @Test
    fun preflightIdentityValuesDescribeAnIphone() {
        val server = LockdownUsbServer()

        assertEquals("iPhone", (server.identityValue("DeviceClass") as LockdownPlist.Value.Text).value)
        assertEquals("iPhone OS", (server.identityValue("ProductName") as LockdownPlist.Value.Text).value)
        assertEquals("iPhone17,1", (server.identityValue("ProductType") as LockdownPlist.Value.Text).value)
        assertEquals("D93AP", (server.identityValue("HardwareModel") as LockdownPlist.Value.Text).value)
        assertEquals("t8140", (server.identityValue("HardwarePlatform") as LockdownPlist.Value.Text).value)
        assertEquals(0x0C, (server.identityValue("BoardId") as LockdownPlist.Value.Integer).value)
        assertEquals(0x8140, (server.identityValue("ChipID") as LockdownPlist.Value.Integer).value)
        assertEquals(1, (server.identityValue("SecurityDomain") as LockdownPlist.Value.Integer).value)
        assertEquals(
            "1600",
            (server.identityValue("MobileDeviceMinimumVersion") as LockdownPlist.Value.Text).value,
        )
        assertEquals("mcarplay-usb", (server.identityValue("UniqueDeviceID") as LockdownPlist.Value.Text).value)
        assertEquals("mcarplay-usb", (server.identityValue("SerialNumber") as LockdownPlist.Value.Text).value)
        assertTrue((server.identityValue("UniqueChipID") as LockdownPlist.Value.Integer).value > 0)
    }

    @Test
    fun insecureNotificationProxyHasAUsablePort() {
        val server = LockdownUsbServer()

        assertEquals(
            "com.apple.mobile.insecure_notification_proxy",
            server.serviceNameForPort(LockdownUsbServer.NOTIFICATION_PROXY_PORT),
        )
    }

    @Test
    fun startSessionReturnsTheSessionIdentifierRequiredByMobileDevice() {
        val response = LockdownUsbServer.startSessionResponse("9A96C411-82D6-4D21-9749-A99B390E8EA8")

        assertEquals(
            "9A96C411-82D6-4D21-9749-A99B390E8EA8",
            (response.entries["SessionID"] as LockdownPlist.Value.Text).value,
        )
        assertEquals(true, (response.entries["EnableSessionSSL"] as LockdownPlist.Value.Boolean).value)
    }

    @Test
    fun secureStartSessionDoesNotRequestNestedTls() {
        val response =
            LockdownUsbServer.startSessionResponse(
                "BF17F6EA-A55A-42F5-8BB0-C51309F17C0D",
                enableSessionSsl = false,
            )

        assertEquals(false, (response.entries["EnableSessionSSL"] as LockdownPlist.Value.Boolean).value)
    }

    @Test
    fun sessionLifecycleResponsesUseTheLockdownRequestShape() {
        assertEquals(
            "StopSession",
            (LockdownUsbServer.stopSessionResponse().entries["Request"] as LockdownPlist.Value.Text).value,
        )
        assertEquals(
            "Goodbye",
            (LockdownUsbServer.goodbyeResponse().entries["Request"] as LockdownPlist.Value.Text).value,
        )
    }

    @Test
    fun pairCertificatesAcceptThePemEncodingUsedByLockdown() {
        val server = LockdownUsbServer()
        val certificate = RsaLockdownIdentity.generate().certificate
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII)).encodeToString(certificate.encoded)
        val pem = "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"

        val parsed =
            server.parseCertificate(
                LockdownPlist.Value.Data(pem.toByteArray(StandardCharsets.US_ASCII)),
            )

        assertTrue(certificate.encoded.contentEquals(parsed!!.encoded))
    }
}
