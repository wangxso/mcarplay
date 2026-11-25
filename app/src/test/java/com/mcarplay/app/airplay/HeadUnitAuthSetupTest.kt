package com.mcarplay.app.airplay

import com.mcarplay.app.airplay.AirPlayPairingTest.MockReceiver
import com.mcarplay.app.airplay.AirPlayRecordLifecycleTest.Peer
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.net.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitAuthSetupTest {
    @Test
    fun observedHeadUnitFeaturesEnableAuthSetupRegardlessOfStackVersion() {
        val features = requireNotNull(AirPlayFeatures.parse("0x4040280,0x61"))
        verifySetup("450.14.1", expectAuthSetup = true, features = features)
        verifySetup("450.14.2", expectAuthSetup = true, features = features)
    }

    @Test
    fun headUnitIdentityAloneDoesNotEnableAuthSetup() {
        verifySetup("450.14.1", expectAuthSetup = false)
    }

    @Test
    fun unifiedPairingDoesNotSendStandaloneAuthSetup() {
        val standaloneMfi = AirPlayFeatures.HK_PAIRING_AND_ENCRYPT or AirPlayFeatures.MFI_SAP
        verifySetup("450.14.1", expectAuthSetup = false, features = standaloneMfi or AirPlayFeatures.UNIFIED_PAIRING_AND_MFI)
    }

    private fun verifySetup(
        sourceVersion: String,
        expectAuthSetup: Boolean,
        features: Long? = null,
    ) {
        Peer().use { peer ->
            val receiver = MockReceiver()
            peer.reply = { request ->
                when (request.path) {
                    "/pair-setup", "/pair-verify" -> {
                        request.respond(200, receiver.handle(request.path, request.body))
                        if (request.path == "/pair-verify" && receiver.lastReadKey != null) {
                            request.connection.cipher = ControlCipher(receiver.lastReadKey!!, receiver.lastWriteKey!!)
                        }
                    }
                    "/auth-setup" -> request.respond(404)
                    "/setup", "/info", "/" -> request.respond(200, BinaryPlist.encode(emptyMap<String, Any>()))
                    else -> error("Unexpected request: ${request.method} ${request.path}")
                }
            }
            val client =
                AirPlayClient(
                    Iap2SenderSession.CarPlayEndpoint(
                        ssid = "test",
                        passphrase = "",
                        channel = 0,
                        securityType = 0,
                        airPlayPort = peer.port,
                        deviceIdentifier = null,
                        publicKey = null,
                        sourceVersion = sourceVersion,
                        ipAddresses = listOf("127.0.0.1"),
                        advertisedFeatures = features,
                        receiverSignals =
                            ReceiverProfile.Signals(manufacturer = "HeadUnit", model = "HeadUnit-1", firmwareVersion = "1.0.0"),
                    ),
                    log = {},
                )
            try {
                assertNotNull(client.runPairing(windowMillis = 10_000))
                List(5) { peer.next()!! }.forEach { request ->
                    assertEquals("2", request.headers["x-apple-hkp"])
                    assertEquals(if (request.path == "/pair-verify") "1" else null, request.headers["x-apple-pd"])
                }
                assertNotNull(client.runSetup())
                assertTrue(client.record())
                if (expectAuthSetup) {
                    val auth = peer.next()!!
                    assertEquals("POST", auth.method)
                    assertEquals("/auth-setup", auth.path)
                    assertEquals("application/octet-stream", auth.headers["content-type"])
                    assertEquals(33, auth.body.size)
                    assertEquals(1, auth.body.first().toInt())
                }
                assertEquals(
                    listOf("SETUP /setup", "GET /info", "RECORD /"),
                    List(3) { peer.next()!! }.map { "${it.method} ${it.path}" },
                )
                assertNull(peer.next(150))
            } finally {
                client.close()
            }
        }
    }
}
