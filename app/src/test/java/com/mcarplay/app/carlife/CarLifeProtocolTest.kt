package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

class CarLifeProtocolTest {
    @Test
    fun statisticsContainsAllSevenRequiredFieldsWithoutClaimingConnectionSuccess() {
        val payload = CarLifeProtocol.statisticsPayload()
        assertEquals("mcarplay-local", CarLifeProtocol.firstStringField(payload, 1))
        assertEquals("0.1.0", CarLifeProtocol.firstStringField(payload, 2))
        assertEquals(1, CarLifeProtocol.firstIntField(payload, 3))
        assertEquals("29999999", CarLifeProtocol.firstStringField(payload, 4))
        assertEquals(1, CarLifeProtocol.firstIntField(payload, 5))
        assertEquals(0, CarLifeProtocol.firstIntField(payload, 6))
        assertEquals(0, CarLifeProtocol.firstIntField(payload, 7))
    }

    @Test
    fun commandFrameUsesBigEndianHeader() {
        val frame = CarLifeProtocol.encodeCommand(CarLifeProtocol.HU_PROTOCOL_VERSION, byteArrayOf(8, 4))
        assertArrayEquals(
            byteArrayOf(0, 2, 0, 0, 0, 1, 0x80.toByte(), 1, 8, 4),
            frame,
        )
    }

    @Test
    fun videoFrameUsesLengthTimestampAndKind() {
        val frame = CarLifeProtocol.encodeVideo(CarLifeProtocol.VIDEO_FRAME, 1234, byteArrayOf(1, 2, 3))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, 0, 0, 4, 0xD2.toByte(), 0, 2, 0, 1, 1, 2, 3),
            frame,
        )
    }

    @Test
    fun videoHeartbeatHasNoPayloadAndUsesUnixTimestamp() {
        val frame = CarLifeProtocol.encodeVideo(CarLifeProtocol.VIDEO_HEARTBEAT, 1_792_857_945)
        assertEquals(12, frame.size)
        assertEquals(
            Triple(0, 1_792_857_945, CarLifeProtocol.VIDEO_HEARTBEAT),
            CarLifeProtocol.decodeVideoHeader(frame),
        )
    }

    @Test
    fun videoInitDoneParsesActualDimensions() {
        val format =
            CarLifeProtocol.parseVideoFormat(
                CarLifeProtocol.pbInt(1, 1280) +
                    CarLifeProtocol.pbInt(2, 720) +
                    CarLifeProtocol.pbInt(3, 30),
            )
        assertEquals(CarLifeProtocol.VideoFormat(1280, 720, 30), format)
        assertEquals(null, CarLifeProtocol.parseVideoFormat(CarLifeProtocol.pbInt(1, 0)))
    }

    @Test
    fun featureResponseAdvertisesContentEncryption() {
        val payload = CarLifeProtocol.featureResponsePayload()
        assertEquals(1, CarLifeProtocol.firstIntField(payload, 1))
        assertEquals(mapOf("CONTENT_ENCRYPTION" to 1), CarLifeProtocol.parseFeatures(payload))
    }

    @Test
    fun payloadCipherRejectsWrongKeyAndRoundTrips() {
        val key = ByteArray(16) { it.toByte() }
        val cipher = CarLifePayloadCipher(key)
        val plaintext = "video-frame".toByteArray()
        val encrypted = cipher.encrypt(plaintext)
        assertFalse(plaintext.contentEquals(encrypted))
        assertArrayEquals(plaintext, cipher.decrypt(encrypted))
        assertArrayEquals(ByteArray(0), cipher.encrypt(ByteArray(0)))
    }

    @Test
    fun authVerifierAcceptsRawRsaPkcs1Signature() {
        val pair = testKeyPair()
        val payload = authPayload(pair)
        val verifier = testVerifier(pair)

        assertTrue(verifier.verify(CarLifeProtocol.LEGACY_CHALLENGE, payload))
        assertFalse(verifier.verify("CarLife_9.9.9", payload))
    }

    @Test
    fun authVerifierRejectsUnsignedAndMalformedProofs() {
        val pair = testKeyPair()
        val verifier = testVerifier(pair)
        val publicKey = pair.public as RSAPublicKey
        val privateKey = pair.private as RSAPrivateKey
        val signature = rawSignature(privateKey, CarLifeProtocol.LEGACY_CHALLENGE.toByteArray())
        val emptyChallenge = rawSignature(privateKey, ByteArray(0))
        val encoded =
            BigInteger(1, signature)
                .modPow(publicKey.publicExponent, publicKey.modulus)
                .toByteArray()
        val badDelimiter = encoded.copyOf().apply { this[40] = 1 }
        val malformedSignature =
            BigInteger(1, badDelimiter)
                .modPow(privateKey.privateExponent, privateKey.modulus)
                .toByteArray()
        val normalizedMalformedSignature =
            when {
                malformedSignature.size > 128 ->
                    malformedSignature.copyOfRange(malformedSignature.size - 128, malformedSignature.size)
                malformedSignature.size < 128 -> ByteArray(128 - malformedSignature.size) + malformedSignature
                else -> malformedSignature
            }
        val shortPadding = rawSignature(privateKey, ByteArray(100) { 0x42 })
        val wrongKey = testKeyPair()

        assertFalse(verifier.verify("", authPayload(signature)))
        assertFalse(verifier.verify(CarLifeProtocol.LEGACY_CHALLENGE, authPayload(emptyChallenge)))
        assertFalse(
            verifier.verify(
                CarLifeProtocol.LEGACY_CHALLENGE,
                CarLifeProtocol.pbString(
                    1,
                    Base64.getEncoder().encodeToString(normalizedMalformedSignature),
                ),
            ),
        )
        assertFalse(verifier.verify(CarLifeProtocol.LEGACY_CHALLENGE, authPayload(shortPadding)))
        assertFalse(verifier.verify(CarLifeProtocol.LEGACY_CHALLENGE, authPayload(signature.copyOf(40))))
        assertFalse(testVerifier(wrongKey).verify(CarLifeProtocol.LEGACY_CHALLENGE, authPayload(signature)))
        assertTrue(publicKey.modulus.bitLength() >= 1024)
    }

    @Test
    fun stateMachineWaitsForInfoBeforeChallengeAndBindsDeviceFields() {
        val pair = testKeyPair()
        val machine = testMachine(pair)

        assertEquals(listOf(CarLifeProtocol.HU_PROTOCOL_VERSION), machine.onConnected().map { it.kind })
        assertEquals(CarLifeStateMachine.State.VERSION_SENT, machine.state)
        assertEquals(listOf(CarLifeProtocol.HU_STATISTICS), machine.onProtocolMatched().map { it.kind })
        assertEquals(CarLifeStateMachine.State.PROTOCOL_MATCHED, machine.state)
        assertEquals(emptyList<CarLifeStateMachine.Action>(), machine.onAuthResponse(authPayload(pair)))
        assertEquals(CarLifeStateMachine.State.FAILED, machine.state)
    }

    @Test
    fun stateMachineRejectsRepeatedProtocolMatch() {
        val machine = testMachine(testKeyPair())
        machine.onConnected()
        machine.onProtocolMatched()

        assertEquals(emptyList<CarLifeStateMachine.Action>(), machine.onProtocolMatched())
        assertEquals(CarLifeStateMachine.State.FAILED, machine.state)
    }

    @Test
    fun stateMachineSendsChallengeOnlyAfterValidInfo() {
        val pair = testKeyPair()
        val machine = testMachine(pair)
        machine.onConnected()
        machine.onProtocolMatched()

        assertEquals(
            listOf(CarLifeProtocol.HU_INFO, CarLifeProtocol.HU_AUTH_REQUEST),
            machine.onInfo(deviceInfoPayload()).map { it.kind },
        )
        assertEquals(CarLifeStateMachine.State.AUTH_REQUESTED, machine.state)
        assertEquals(
            CarLifeProtocol.DeviceInfo("Xiaomi", "2509FPN0BC", "29"),
            machine.deviceInfo,
        )
        assertEquals(emptyList<CarLifeStateMachine.Action>(), machine.onInfo(deviceInfoPayload()))
        assertEquals(CarLifeStateMachine.State.FAILED, machine.state)
    }

    @Test
    fun stateMachineRejectsInfoBeforeProtocolMatchAndMalformedFields() {
        val machine = testMachine(testKeyPair())
        machine.onConnected()
        assertEquals(emptyList<CarLifeStateMachine.Action>(), machine.onInfo(deviceInfoPayload()))
        assertEquals(CarLifeStateMachine.State.FAILED, machine.state)

        val missingField = testMachine(testKeyPair())
        missingField.onConnected()
        missingField.onProtocolMatched()
        assertEquals(
            emptyList<CarLifeStateMachine.Action>(),
            missingField.onInfo(CarLifeProtocol.pbString(4, "Xiaomi")),
        )
        assertEquals(CarLifeStateMachine.State.FAILED, missingField.state)
    }

    @Test
    fun foregroundRequestsCanRepeatWithoutAdvancingAuthentication() {
        val machine = testMachine(testKeyPair())
        machine.onConnected()
        machine.onProtocolMatched()

        val foreground = machine.onForeground()
        assertTrue(foreground.changed)
        assertEquals(listOf(CarLifeProtocol.HU_GO_TO_FOREGROUND), foreground.actions.map { it.kind })
        assertTrue(machine.onForeground().changed)
        assertEquals(CarLifeStateMachine.State.PROTOCOL_MATCHED, machine.state)
    }

    @Test
    fun foregroundBeforeProtocolMatchFails() {
        val machine = testMachine(testKeyPair())
        machine.onConnected()

        assertFalse(machine.onForeground().changed)
        assertEquals(CarLifeStateMachine.State.FAILED, machine.state)
    }

    @Test
    fun fullXiaomiHandshakeUsesEmptyEncryptReadyAndStartsVideoOnce() {
        val pair = testKeyPair()
        val machine = testMachine(pair, CarLifeSeedExchange())
        machine.onConnected()
        machine.onProtocolMatched()
        assertTrue(machine.onForeground().changed)
        assertEquals(
            listOf(CarLifeProtocol.HU_INFO, CarLifeProtocol.HU_AUTH_REQUEST),
            machine.onInfo(deviceInfoPayload()).map { it.kind },
        )
        assertEquals(
            listOf(
                CarLifeProtocol.HU_AUTH_RESULT,
            ),
            machine.onAuthResponse(authPayload(pair)).map { it.kind },
        )
        assertEquals(CarLifeStateMachine.State.AUTH_VERIFIED, machine.state)
        machine.onPeerAuthResult(CarLifeProtocol.pbInt(1, 1))
        assertEquals(
            listOf(CarLifeProtocol.HU_FEATURE_RESPONSE),
            machine.onFeatureRequest().map { it.kind },
        )
        val publicKeyPayload = machine.onRsaKeyRequest().single().payload
        val publicKey = publicKey(publicKeyPayload)
        val seed = ByteArray(16) { (it * 7).toByte() }
        val encryptedSeed =
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, publicKey)
                doFinal(seed)
            }
        assertEquals(
            listOf(CarLifeProtocol.HU_AES_ACK),
            machine
                .onEncryptedSeed(CarLifeProtocol.pbString(1, Base64.getEncoder().encodeToString(encryptedSeed)))
                .map { it.kind },
        )
        assertEquals(emptyList<CarLifeStateMachine.Action>(), machine.requestVideoInit())
        assertEquals(emptyList<CarLifeStateMachine.Action>(), machine.onEncryptReady(byteArrayOf(1)))
        assertEquals(CarLifeStateMachine.State.FAILED, machine.state)

        val validMachine = testMachine(pair, CarLifeSeedExchange())
        validMachine.onConnected()
        validMachine.onProtocolMatched()
        validMachine.onInfo(deviceInfoPayload())
        validMachine.onAuthResponse(authPayload(pair))
        validMachine.onPeerAuthResult(CarLifeProtocol.pbInt(1, 1))
        validMachine.onFeatureRequest()
        val validPublicKey = publicKey(validMachine.onRsaKeyRequest().single().payload)
        val validSeed =
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, validPublicKey)
                doFinal(ByteArray(16) { (it + 1).toByte() })
            }
        validMachine.onEncryptedSeed(CarLifeProtocol.pbString(1, Base64.getEncoder().encodeToString(validSeed)))
        assertEquals(emptyList<CarLifeStateMachine.Action>(), validMachine.onEncryptReady(ByteArray(0)))
        assertEquals(CarLifeStateMachine.State.ENCRYPTED, validMachine.state)
        assertEquals(
            listOf(CarLifeProtocol.VIDEO_ENCODER_INIT),
            validMachine.requestVideoInit().map { it.kind },
        )
        assertEquals(emptyList<CarLifeStateMachine.Action>(), validMachine.requestVideoInit())
        assertEquals(CarLifeStateMachine.State.FAILED, validMachine.state)

        val videoMachine = testMachine(pair, CarLifeSeedExchange())
        videoMachine.onConnected()
        videoMachine.onProtocolMatched()
        videoMachine.onInfo(deviceInfoPayload())
        videoMachine.onAuthResponse(authPayload(pair))
        videoMachine.onPeerAuthResult(CarLifeProtocol.pbInt(1, 1))
        videoMachine.onFeatureRequest()
        val videoPublicKey = publicKey(videoMachine.onRsaKeyRequest().single().payload)
        val videoSeed =
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, videoPublicKey)
                doFinal(ByteArray(16) { 0x55 })
            }
        videoMachine.onEncryptedSeed(CarLifeProtocol.pbString(1, Base64.getEncoder().encodeToString(videoSeed)))
        videoMachine.onEncryptReady(ByteArray(0))
        videoMachine.requestVideoInit()
        assertEquals(
            listOf(CarLifeProtocol.VIDEO_ENCODER_START),
            videoMachine.onVideoInitDone(videoInitPayload()).map { it.kind },
        )
        assertEquals(CarLifeStateMachine.State.VIDEO_RUNNING, videoMachine.state)
        assertEquals(emptyList<CarLifeStateMachine.Action>(), videoMachine.onVideoInitDone(videoInitPayload()))
        assertEquals(CarLifeStateMachine.State.FAILED, videoMachine.state)
    }

    private fun testMachine(
        pair: KeyPair,
        exchange: CarLifeSeedExchange? = null,
    ): CarLifeStateMachine =
        CarLifeStateMachine(
            CarLifeAuthVerifier(Base64.getEncoder().encodeToString(pair.public.encoded)),
            exchange,
            challengeFactory = { CarLifeProtocol.LEGACY_CHALLENGE },
        )

    private fun testVerifier(pair: KeyPair): CarLifeAuthVerifier =
        CarLifeAuthVerifier(Base64.getEncoder().encodeToString(pair.public.encoded))

    private fun testKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    private fun deviceInfoPayload(): ByteArray =
        CarLifeProtocol.pbString(4, "Xiaomi") +
            CarLifeProtocol.pbString(14, "2509FPN0BC") +
            CarLifeProtocol.pbString(20, "29")

    private fun videoInitPayload(): ByteArray =
        CarLifeProtocol.pbInt(1, 1280) +
            CarLifeProtocol.pbInt(2, 720) +
            CarLifeProtocol.pbInt(3, 30)

    private fun authPayload(pair: KeyPair): ByteArray =
        authPayload(rawSignature(pair.private as RSAPrivateKey, CarLifeProtocol.LEGACY_CHALLENGE.toByteArray()))

    private fun authPayload(signature: ByteArray): ByteArray = CarLifeProtocol.pbString(1, Base64.getEncoder().encodeToString(signature))

    private fun publicKey(payload: ByteArray): RSAPublicKey {
        val encoded = CarLifeProtocol.firstStringField(payload, 1) ?: error("RSA public key missing")
        return java.security.KeyFactory
            .getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(encoded))) as RSAPublicKey
    }

    private fun rawSignature(
        key: RSAPrivateKey,
        message: ByteArray,
    ): ByteArray {
        val size = (key.modulus.bitLength() + 7) / 8
        require(message.size <= size - 11)
        val encoded = ByteArray(size)
        encoded[1] = 1
        encoded.fill(0xFF.toByte(), 2, size - message.size - 1)
        encoded[size - message.size - 1] = 0
        message.copyInto(encoded, size - message.size)
        return BigInteger(1, encoded)
            .modPow(key.privateExponent, key.modulus)
            .toByteArray()
            .let { value ->
                when {
                    value.size > size -> value.copyOfRange(value.size - size, value.size)
                    value.size < size -> ByteArray(size - value.size) + value
                    else -> value
                }
            }
    }
}
