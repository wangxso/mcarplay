package com.mcarplay.app.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.security.SecureRandom

/**
 * 配对栈端到端回环：MockReceiver 严格按参考接收端（xcertplay PairSetup/PairVerify/Srp6a）
 * 的算法实现服务端，验证我们的客户端能完整走通 pair-setup + pair-verify，
 * 且双方派生的控制密钥方向一致。
 */
class AirPlayPairingTest {
    private val random = SecureRandom()

    @Test
    fun pairSetupThenPairVerifyAgainstMockReceiver() {
        val receiver = MockReceiver()
        val transport = TlvTransport { path, body -> receiver.handle(path, body) }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("test-controller-id", keyPair.privateKey, keyPair.publicKey)

        val receiverIdentity = PairSetupClient(identity, transport) { }.run()
        assertNotNull(receiverIdentity)
        assertEquals(receiver.pairingId, receiverIdentity!!.pairingId)
        assertArrayEquals(receiver.staticKey.publicKey, receiverIdentity.publicKey)
        assertTrue("接收端应保存控制器公钥", receiver.pairings.containsKey(identity.pairingId))

        val keys = PairVerifyClient(identity, transport) { }.run(receiver.staticKey.publicKey)
        assertEquals(32, keys.phoneWriteKey.size)
        assertEquals(32, keys.phoneReadKey.size)

        // 方向交叉验证：接收端 readKey 解我们发出的方向，writeKey 是它发给我们的方向
        assertNotNull("接收端应完成 pair-verify", receiver.lastReadKey)
        assertArrayEquals(receiver.lastReadKey, keys.phoneWriteKey)
        assertArrayEquals(receiver.lastWriteKey, keys.phoneReadKey)
    }

    /**
     * 接收端对 A/B/S 用去前导零编码时（2026-11-02 艾瑞泽 8 偶发 `bad setup code`），
     * 客户端也必须每次都通过：修复前约 3/256 的轮次会失败，40 轮几乎必现。
     */
    @Test
    fun pairSetupSucceedsAgainstUnpaddedReceiverEveryTime() {
        repeat(40) { round ->
            val receiver = MockReceiver(padded = false)
            val keyPair = AirPlayCrypto.ed25519Generate()
            val identity = ControllerIdentity("controller-$round", keyPair.privateKey, keyPair.publicKey)
            PairSetupClient(identity, { path, body -> receiver.handle(path, body) }) { }.run()
            assertTrue("第 $round 轮应完成 pair-setup", receiver.pairings.containsKey(identity.pairingId))
        }
    }

    @Test
    fun shortServerKeyRestartsM1BeforeSubmittingProof() {
        val receiver = MockReceiver(padded = false, shortKeyRounds = 1)
        val keyPair = AirPlayCrypto.ed25519Generate()
        val identity = ControllerIdentity("controller-short-b", keyPair.privateKey, keyPair.publicKey)
        val logs = mutableListOf<String>()
        PairSetupClient(identity, { path, body -> receiver.handle(path, body) }) { logs += it }.run()
        assertTrue("At least one short B must trigger an M1 retry", receiver.m1Count >= 2)
        assertEquals(1, receiver.proofCount)
        assertTrue(receiver.pairings.containsKey(identity.pairingId))
        assertTrue(logs.any { it.contains("重发 M1") })
    }

    @Test
    fun clientKeysAreAlwaysFullLength() {
        val salt = ByteArray(16).also(random::nextBytes)
        repeat(64) {
            val b = Srp6aClient.generator().modPow(BigInteger(256, random), Srp6aClient.modulus())
            val session = Srp6aClient.start(salt, Srp6aClient.pad(b))
            assertTrue(Srp6aClient.isFullLength(session.publicKeyA))
            assertEquals(Srp6aClient.KEY_BYTES, session.sharedSecretSize)
        }
    }

    @Test
    fun tlv8FragmentationRoundTrip() {
        val big = ByteArray(600) { (it % 251).toByte() }
        val encoded =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, big),
                ),
            )
        val decoded = Tlv8.decode(encoded)
        assertEquals(1, decoded[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt())
        assertArrayEquals(big, decoded[Tlv8.TYPE_PUBLIC_KEY])
    }

    @Test
    fun fourConnectionsRegisterOnceAndVerifyEveryTime() {
        val receiver = MockReceiver()
        val requests = ArrayList<String>()
        val transport =
            TlvTransport { path, body ->
                requests.add(path)
                receiver.handle(path, body)
            }
        val handshake = PairingHandshake(newIdentity(), { transport }, { error("unexpected reconnect") }, {})
        var remembered: PairSetupClient.ReceiverIdentity? = null
        repeat(4) {
            val result = handshake.run(remembered, null)
            remembered = result.receiver
            assertNotNull(remembered)
            assertArrayEquals(receiver.lastReadKey, result.keys.phoneWriteKey)
            assertArrayEquals(receiver.lastWriteKey, result.keys.phoneReadKey)
        }
        assertEquals(3, requests.count { it == "/pair-setup" })
        assertEquals(8, requests.count { it == "/pair-verify" })
    }

    @Test
    fun setupCallbackFiresOnlyWhenPairSetupCompletes() {
        val receiver = MockReceiver()
        val transport = TlvTransport { path, body -> receiver.handle(path, body) }
        var setups = 0
        val handshake =
            PairingHandshake(newIdentity(), { transport }, { error("unexpected reconnect") }, {}, onSetupComplete = { setups++ })
        val first = handshake.run(null, null)
        assertEquals(1, setups)
        handshake.run(first.receiver, null)
        handshake.run(first.receiver, null)
        assertEquals(1, setups)
    }

    @Test
    fun repairedTrustReportsTheFreshSetupOnce() {
        val receiver = MockReceiver()
        val transport = TlvTransport { path, body -> receiver.handle(path, body) }
        var setups = 0
        val handshake = PairingHandshake(newIdentity(), { transport }, {}, {}, onSetupComplete = { setups++ })
        handshake.run(PairSetupClient.ReceiverIdentity(receiver.pairingId, receiver.staticKey.publicKey), null)
        assertEquals(1, setups)
    }

    @Test
    fun forgottenControllerRepairsTrustOnceOnFreshConnection() {
        val receiver = MockReceiver()
        val requests = ArrayList<String>()
        var reconnects = 0
        var invalidations = 0
        val transport =
            TlvTransport { path, body ->
                requests.add(path)
                receiver.handle(path, body)
            }
        val handshake = PairingHandshake(newIdentity(), { transport }, { reconnects++ }, {}, onTrustInvalidated = { invalidations++ })
        val remembered = PairSetupClient.ReceiverIdentity(receiver.pairingId, receiver.staticKey.publicKey)
        val result = handshake.run(remembered, null)
        assertEquals(
            listOf(
                "/pair-verify",
                "/pair-verify",
                "/pair-setup",
                "/pair-setup",
                "/pair-setup",
                "/pair-verify",
                "/pair-verify",
            ),
            requests,
        )
        assertEquals(1, reconnects)
        assertEquals(1, invalidations)
        assertArrayEquals(receiver.lastReadKey, result.keys.phoneWriteKey)
    }

    @Test
    fun savedVerifyTransportAndBusyFailuresNeverRegisterOrRemoveTrust() {
        val receiver = MockReceiver()
        val remembered = PairSetupClient.ReceiverIdentity(receiver.pairingId, receiver.staticKey.publicKey)
        val failures =
            listOf(EOFException("closed"), IOException("HTTP/1.1 403 Forbidden")) +
                listOf(1, 3, 4, 5, 6, 7).map { PairingRejectedException("/pair-verify", 2, it) }
        for (failure in failures) {
            val transport =
                TlvTransport { path, _ ->
                    assertEquals("/pair-verify", path)
                    throw failure
                }
            val handshake =
                PairingHandshake(
                    newIdentity(),
                    { transport },
                    { error("unexpected reconnect") },
                    {},
                    onTrustInvalidated = { error("trust must survive ${failure.message}") },
                )
            assertThrows(IOException::class.java) { handshake.run(remembered, null) }
        }
    }

    @Test
    fun badSetupSignatureIsRejected() {
        val receiver = MockReceiver(badSetupSignature = true)
        val transport = TlvTransport { path, body -> receiver.handle(path, body) }
        assertThrows(IOException::class.java) { PairSetupClient(newIdentity(), transport) {}.run() }
    }

    @Test
    fun savedVerifyRejectsChangedReceiverIdentityAndSignature() {
        val receiver = MockReceiver()
        val transport = TlvTransport { path, body -> receiver.handle(path, body) }
        val identity = newIdentity()
        assertThrows(IOException::class.java) { PairVerifyClient(identity, transport) {}.run(receiver.staticKey.publicKey, "other-id") }
        assertThrows(IOException::class.java) { PairVerifyClient(identity, transport) {}.run(newIdentity().publicKey, receiver.pairingId) }
    }

    @Test
    fun pairingResponsesRejectErrorsAndUnexpectedStates() {
        for ((path, state) in listOf(
            "/pair-setup" to 2,
            "/pair-setup" to 4,
            "/pair-setup" to 6,
            "/pair-verify" to 2,
            "/pair-verify" to 4,
        )) {
            val error =
                Tlv8.encode(
                    listOf(
                        Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(state.toByte())),
                        Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(7)),
                    ),
                )
            val rejected = assertThrows(PairingRejectedException::class.java) { decodePairingResponse(path, state, error) }
            assertEquals(7, rejected.errorCode)
            val unexpected = Tlv8.encode(listOf(Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1))))
            assertThrows(IOException::class.java) { decodePairingResponse(path, state, unexpected) }
        }
    }

    private fun newIdentity(): ControllerIdentity {
        val keyPair = AirPlayCrypto.ed25519Generate()
        return ControllerIdentity("test-controller-id", keyPair.privateKey, keyPair.publicKey)
    }

    /**
     * 按参考接收端算法实现的服务端。[padded] = false 时每项编码取反：A/B 去前导零参与哈希、B 不补零下发，
     * S 反而补零后求 K；
     * [shortKeyRounds] 指定前几轮 M2 故意给首字节为 0 的 B，其余轮次 B 恒满长（否则约 1/256 触发重发 M1，请求计数不稳）；
     * [badSetupSignature] 让 M6 签名失效。
     */
    internal class MockReceiver(
        private val padded: Boolean = true,
        private var shortKeyRounds: Int = 0,
        private val badSetupSignature: Boolean = false,
    ) {
        val staticKey = AirPlayCrypto.ed25519Generate()
        val pairingId = "3e6f5739-b2bf-4ebf-bb87-5b6b57cc42e5"
        val pairings = HashMap<String, ByteArray>()
        var lastReadKey: ByteArray? = null
            private set
        var lastWriteKey: ByteArray? = null
            private set

        private val random = SecureRandom()

        // SRP 服务端状态
        private var salt = ByteArray(0)
        private var publicKeyB = BigInteger.ZERO
        private var verifier = BigInteger.ZERO
        private var privateB = BigInteger.ZERO
        private var sessionKey = ByteArray(0)

        // pair-verify 状态
        private var pvEphemeralPublic = ByteArray(0)
        private var pvShared = ByteArray(0)
        private var pvEncryptionKey = ByteArray(0)

        fun handle(
            path: String,
            body: ByteArray,
        ): ByteArray {
            val tlv = Tlv8.decode(body)
            val state = tlv[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            return when (path) {
                "/pair-setup" ->
                    when (state) {
                        1 -> setupM2()
                        3 -> setupM4(tlv)
                        5 -> setupM6(tlv)
                        else -> error(state)
                    }

                "/pair-verify" ->
                    when (state) {
                        1 -> verifyM2(tlv)
                        3 -> verifyM4(tlv)
                        else -> error(state)
                    }

                else -> error(state)
            }
        }

        private fun setupM2(): ByteArray {
            pvShared = ByteArray(0)
            salt = ByteArray(16).also(random::nextBytes)
            val identifier = Srp6aClient.USERNAME.toByteArray()
            val x =
                Srp6aClient.toBigInt(
                    AirPlayCrypto.sha512(
                        salt,
                        AirPlayCrypto.sha512(identifier, byteArrayOf(':'.code.toByte()), Srp6aClient.SETUP_CODE.toByteArray()),
                    ),
                )
            verifier = Srp6aClient.generator().modPow(x, Srp6aClient.modulus())
            m1Count++
            val wantShort = shortKeyRounds > 0
            if (wantShort) shortKeyRounds--
            do {
                privateB = Srp6aClient.toBigInt(ByteArray(32).also(random::nextBytes))
                publicKeyB =
                    (
                        Srp6aClient.multiplier() * verifier +
                            Srp6aClient.generator().modPow(
                                privateB,
                                Srp6aClient.modulus(),
                            )
                    ).mod(Srp6aClient.modulus())
            } while (Srp6aClient.isFullLength(Srp6aClient.pad(publicKeyB)) == wantShort)
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                    Tlv8.Item(Tlv8.TYPE_SALT, salt),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, enc(publicKeyB)),
                ),
            )
        }

        private fun setupM4(tlv: Map<Int, ByteArray>): ByteArray {
            val publicKeyA = tlv[Tlv8.TYPE_PUBLIC_KEY] ?: return error(4)
            val clientProof = tlv[Tlv8.TYPE_PROOF] ?: return error(4)
            proofCount++
            val a = Srp6aClient.toBigInt(publicKeyA)
            val u = Srp6aClient.toBigInt(AirPlayCrypto.sha512(enc(a), enc(publicKeyB)))
            val s = (a * verifier.modPow(u, Srp6aClient.modulus())).mod(Srp6aClient.modulus()).modPow(privateB, Srp6aClient.modulus())
            // 参考接收端 K = H(去零 S)；反向约定下改为 H(补零 S)，与 A/B 一起逐项取反
            sessionKey = AirPlayCrypto.sha512(if (padded) Srp6aClient.toBytes(s) else Srp6aClient.pad(s))

            val hashN = AirPlayCrypto.sha512(Srp6aClient.toBytes(Srp6aClient.modulus()))
            val hashG = AirPlayCrypto.sha512(Srp6aClient.toBytes(Srp6aClient.generator()))
            val hashXor = ByteArray(hashN.size)
            for (index in hashN.indices) {
                hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
            }
            val identifier = Srp6aClient.USERNAME.toByteArray()
            val expected =
                AirPlayCrypto.sha512(
                    hashXor,
                    AirPlayCrypto.sha512(identifier),
                    salt,
                    enc(a),
                    enc(publicKeyB),
                    sessionKey,
                )
            if (!expected.contentEquals(clientProof)) return error(4)
            val serverProof = AirPlayCrypto.sha512(enc(a), clientProof, sessionKey)
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4)),
                    Tlv8.Item(Tlv8.TYPE_PROOF, serverProof),
                ),
            )
        }

        private fun setupM6(tlv: Map<Int, ByteArray>): ByteArray {
            val encrypted = tlv[Tlv8.TYPE_ENCRYPTED_DATA] ?: return error(6)
            val encryptionKey = hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
            val sub = Tlv8.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg05"), encrypted))
            val controllerId = sub[Tlv8.TYPE_IDENTIFIER] ?: return error(6)
            val controllerLtpk = sub[Tlv8.TYPE_PUBLIC_KEY] ?: return error(6)
            val controllerSignature = sub[Tlv8.TYPE_SIGNATURE] ?: return error(6)

            val controllerSignKey = hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
            if (!AirPlayCrypto.ed25519Verify(controllerLtpk, controllerSignKey + controllerId + controllerLtpk, controllerSignature)) {
                return error(6)
            }
            pairings[controllerId.toString(Charsets.UTF_8)] = controllerLtpk

            val accessoryId = pairingId.toByteArray(Charsets.UTF_8)
            val accessorySignKey = hkdf("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
            val signature = AirPlayCrypto.ed25519Sign(staticKey.privateKey, accessorySignKey + accessoryId + staticKey.publicKey)
            if (badSetupSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
            val subResponse =
                Tlv8.encode(
                    listOf(
                        Tlv8.Item(Tlv8.TYPE_IDENTIFIER, accessoryId),
                        Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, staticKey.publicKey),
                        Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                    ),
                )
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(6)),
                    Tlv8.Item(
                        Tlv8.TYPE_ENCRYPTED_DATA,
                        AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg06"), subResponse),
                    ),
                ),
            )
        }

        private fun verifyM2(tlv: Map<Int, ByteArray>): ByteArray {
            val clientEphemeral = tlv[Tlv8.TYPE_PUBLIC_KEY] ?: return error(2)
            pvClientEphemeral = clientEphemeral
            val pair = AirPlayCrypto.x25519Generate()
            pvEphemeralPublic = pair.publicKey
            pvShared = AirPlayCrypto.x25519Shared(pair.privateKey, clientEphemeral)
            pvEncryptionKey = hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")

            val accessoryId = pairingId.toByteArray(Charsets.UTF_8)
            val signature =
                AirPlayCrypto.ed25519Sign(
                    staticKey.privateKey,
                    pair.publicKey + accessoryId + clientEphemeral,
                )
            val sub =
                Tlv8.encode(
                    listOf(
                        Tlv8.Item(Tlv8.TYPE_IDENTIFIER, accessoryId),
                        Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                    ),
                )
            return Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, pair.publicKey),
                    Tlv8.Item(
                        Tlv8.TYPE_ENCRYPTED_DATA,
                        AirPlayCrypto.chachaSeal(pvEncryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), sub),
                    ),
                ),
            )
        }

        private fun verifyM4(tlv: Map<Int, ByteArray>): ByteArray {
            val encrypted = tlv[Tlv8.TYPE_ENCRYPTED_DATA] ?: return error(4)
            val sub = Tlv8.decode(AirPlayCrypto.chachaOpen(pvEncryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), encrypted))
            val controllerId = sub[Tlv8.TYPE_IDENTIFIER] ?: return error(4)
            val controllerSignature = sub[Tlv8.TYPE_SIGNATURE] ?: return error(4)
            val controllerLtpk = pairings[controllerId.toString(Charsets.UTF_8)] ?: return error(4)

            // 客户端临时公钥需从 M1 找回：简化测试路径里客户端把它放在 identifier 前——
            // 实际按参考算法应为 clientEphemeral || controllerId || serverEphemeral。
            // 这里从 pair-verify 会话中重建（测试中我们保存了它）。
            val clientEphemeral = pvClientEphemeral ?: return error(4)
            if (!AirPlayCrypto.ed25519Verify(controllerLtpk, clientEphemeral + controllerId + pvEphemeralPublic, controllerSignature)) {
                return error(4)
            }
            lastReadKey = hkdf("Control-Salt", "Control-Write-Encryption-Key")
            lastWriteKey = hkdf("Control-Salt", "Control-Read-Encryption-Key")
            return Tlv8.encode(listOf(Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(4))))
        }

        private var pvClientEphemeral: ByteArray? = null

        var m1Count = 0
            private set
        var proofCount = 0
            private set

        private fun enc(value: BigInteger): ByteArray = if (padded) Srp6aClient.pad(value) else Srp6aClient.toBytes(value)

        private fun hkdf(
            salt: String,
            info: String,
        ): ByteArray {
            val material = if (pvShared.isNotEmpty()) pvShared else sessionKey
            return AirPlayCrypto.hkdfSha512(material, salt.toByteArray(), info.toByteArray())
        }

        private fun error(state: Int): ByteArray =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(state.toByte())),
                    Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(2)),
                ),
            )
    }
}
