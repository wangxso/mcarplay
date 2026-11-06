package com.mcarplay.app.airplay

import com.mcarplay.app.airplay.AirPlayPairingTest.MockReceiver
import com.mcarplay.app.airplay.AirPlayRecordLifecycleTest.Peer
import com.mcarplay.app.airplay.AirPlayRecordLifecycleTest.Request
import com.mcarplay.app.audio.AudioCodec
import com.mcarplay.app.diagnostics.ConnectionMilestone
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CHERY / Tiggo / 320.17.7 报告启发的合成协议回归，复用现有接收端与 TCP 回环。
 * 没有实车 pcap、HTTP、plist 或 TLV 原始字节；响应均为测试构造，不代表实车回放或兼容性证明。
 */
class Arrizo8ProtocolRegressionTest {
    @Test
    fun hkpZeroPairingAndEncryptedAuthDoNotImplySessionSetupSuccess() {
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

                    // 仅模拟 HTTP 成功及 SAP 应答外形，不提供真实 MFi 证书或签名。
                    "/auth-setup" -> {
                        val response = AirPlayCrypto.x25519Generate().publicKey + be32(0) + be32(64) + ByteArray(64)
                        request.respond(200, response)
                    }

                    "/setup" -> request.respond(403, BinaryPlist.encode(mapOf("eventPort" to 0L)))
                    else -> request.respond(500)
                }
            }
            val client = client(peer.port)
            val milestones = CopyOnWriteArrayList<ConnectionMilestone>()
            client.onMilestone = { reached, _ -> milestones += reached }
            try {
                assertNotNull(client.runPairing(windowMillis = 10_000))
                assertTrue(client.controlEncrypted)
                assertFalse(client.sessionActive)
                assertFalse(milestones.contains(ConnectionMilestone.AUTH_SETUP))
                val pairing = List(5) { peer.next()!! }
                assertEquals(
                    listOf("/pair-setup", "/pair-setup", "/pair-setup", "/pair-verify", "/pair-verify"),
                    pairing.map { it.path },
                )
                assertEquals(
                    listOf(1, 3, 5, 1, 3),
                    pairing.map {
                        Tlv8
                            .decode(it.body)
                            .getValue(Tlv8.TYPE_STATE)
                            .single()
                            .toInt()
                    },
                )
                pairing.forEach { request ->
                    assertEquals("POST", request.method)
                    assertEquals("0", request.headers["x-apple-hkp"])
                    assertEquals("application/pairing+tlv8", request.headers["content-type"])
                    assertEquals(if (request.path == "/pair-verify") "1" else null, request.headers["x-apple-pd"])
                    assertEquals("iPhone", request.headers["x-apple-client-name"])
                    assertEquals("1", request.headers["x-apple-protocolversion"])
                }

                assertNull(client.runSetup())
                val auth = peer.next()!!
                val setup = peer.next()!!
                assertEquals("POST", auth.method)
                assertEquals("/auth-setup", auth.path)
                assertEquals(33, auth.body.size)
                assertEquals(1, auth.body.first().toInt())
                assertNull(auth.headers["x-apple-hkp"])
                assertEquals("SETUP", setup.method)
                assertEquals("/setup", setup.path)
                assertNull(setup.plist()["et"])
                assertTrue(milestones.contains(ConnectionMilestone.PAIR_SETUP))
                assertTrue(milestones.contains(ConnectionMilestone.PAIR_VERIFY))
                assertTrue(milestones.contains(ConnectionMilestone.AUTH_SETUP))
                assertFalse(milestones.contains(ConnectionMilestone.SESSION_SETUP))
                assertFalse(milestones.contains(ConnectionMilestone.RECORD))
                assertFalse(client.sessionActive)
                assertTrue(client.teardownSession())
                assertNull(peer.next(300))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun parsedAac44100MediaCapabilityDrivesSetupWithoutType130OrAnotherRecord() {
        Peer().use { peer ->
            peer.reply = { request ->
                when {
                    request.path == "/setup" -> request.respond(200, BinaryPlist.encode(mapOf("eventPort" to 0L)))
                    request.path == "/info" -> request.respond(200, BinaryPlist.encode(syntheticInfo()))
                    request.method == "SETUP" && request.path == "/" ->
                        request.respond(200, streamReply(mapOf("type" to 110L, "dataPort" to 40_002L)))
                    request.method == "SETUP" ->
                        request.respond(
                            200,
                            streamReply(
                                mapOf("type" to 100L, "dataPort" to 12_345L),
                                mapOf("type" to 102L, "dataPort" to 40_000L, "controlPort" to 40_001L),
                            ),
                        )
                    request.method == "GET_PARAMETER" -> request.respond(200, "volume: -12.000000\r\n".toByteArray())
                    request.method == "RECORD" -> request.respond(200)
                    else -> request.respond(500)
                }
            }
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                val setup = client.runSetup()
                assertNotNull(setup)
                val profile = client.audioCandidates().first()
                assertEquals(AudioCodec.AAC_LC, profile.codec)
                assertEquals(44_100, profile.sampleRate)
                assertEquals(350L, profile.latencyMs)
                assertNull(client.runIapTunnel(ByteArray(32)))
                assertNotNull(client.runScreenStream(null, setup!!.displayUuid))
                assertTrue(client.record())
                val audio = client.runAudioStream(ByteArray(32), profile)
                assertNotNull(audio)
                assertEquals(44_100, audio!!.sampleRate)
                assertEquals(40_000, audio.dataPort)
                assertEquals(40_001, audio.controlPort)
                assertTrue(client.record())
                assertTrue(client.sessionActive)

                val requests = List(6) { peer.next()!! }
                assertEquals(listOf("SETUP", "GET", "SETUP", "RECORD", "SETUP", "GET_PARAMETER"), requests.map { it.method })
                val stream = (requests[4].plist()["streams"] as List<*>).single() as Map<*, *>
                assertEquals(102L, stream["type"])
                assertEquals("media", stream["audioType"])
                assertEquals(1L shl 22, stream["audioFormat"]) // AAC-LC 44100 双声道格式位
                assertEquals(1024L, stream["spf"])
                assertEquals(350L, stream["audioLatencyMs"])
                assertEquals(false, stream["input"])
                val streamTypes =
                    requests.filter { it.method == "SETUP" }.flatMap { request ->
                        (request.plist()["streams"] as? List<*>).orEmpty().map { (it as Map<*, *>)["type"] }
                    }
                assertEquals(listOf(110L, 102L), streamTypes)
                assertNull(peer.next(300))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun audioSetupRequiresSuccessAndAMatchingStreamWithADataPort() {
        Peer().use { peer ->
            val replies =
                ArrayDeque(
                    listOf(
                        500 to streamReply(mapOf("type" to 102L, "dataPort" to 40_000L)),
                        200 to streamReply(mapOf("type" to 100L, "dataPort" to 40_000L)),
                        200 to streamReply(mapOf("type" to 102L, "controlPort" to 40_001L)),
                    ),
                )
            peer.reply = { request ->
                if (request.path == "/info") {
                    request.respond(200, BinaryPlist.encode(syntheticInfo()))
                } else if (request.method == "SETUP") {
                    val (status, body) = replies.removeFirst()
                    request.respond(status, body)
                } else {
                    request.respond(500)
                }
            }
            val client = client(peer.port)
            try {
                assertTrue(client.reconnect())
                assertNotNull(client.loadInfoWithDiagnostics())
                val profile = client.audioCandidates().first()
                repeat(3) { assertNull(client.runAudioStream(ByteArray(32), profile)) }
                assertFalse(client.sessionActive)
                assertEquals(listOf("GET", "SETUP", "SETUP", "SETUP"), List(4) { peer.next()!!.method })
                assertNull(peer.next(300)) // 未协商出流时不查询音量，也不发送 RECORD。
            } finally {
                client.close()
            }
        }
    }

    private fun client(port: Int) =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "synthetic-lan",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = port,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = "320.17.7",
                ipAddresses = listOf("127.0.0.1"),
                receiverSignals = ReceiverProfile.Signals(manufacturer = "CHERY", model = "Tiggo"),
            ),
            log = {},
        )

    /** 能力与延迟是合成反例组合：语音档、PCM 档不能覆盖主媒体的 AAC-LC 与对应延迟。 */
    private fun syntheticInfo(): Map<String, Any> =
        mapOf(
            "displays" to listOf(mapOf("uuid" to "synthetic-display", "widthPixels" to 1920L, "heightPixels" to 720L)),
            "audioFormats" to
                listOf(
                    mapOf("type" to 102L, "audioType" to "telephony", "audioOutputFormats" to (1L shl 23)),
                    mapOf("type" to 100L, "audioType" to "media", "audioOutputFormats" to (1L shl 15)),
                    mapOf("type" to 102L, "audioType" to "media", "audioOutputFormats" to (1L shl 22)),
                ),
            "audioLatencies" to
                listOf(
                    mapOf("type" to 100L, "audioType" to "media", "outputLatencyMicros" to 900_000L),
                    mapOf("type" to 102L, "audioType" to "telephony", "outputLatencyMicros" to 700_000L),
                    mapOf("type" to 102L, "audioType" to "media", "outputLatencyMicros" to 350_000L),
                ),
        )

    private fun Request.plist(): Map<*, *> = BinaryPlist.parse(body) as Map<*, *>

    private fun streamReply(vararg streams: Map<String, Any>): ByteArray = BinaryPlist.encode(mapOf("streams" to streams.toList()))

    private fun be32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
