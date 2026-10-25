package com.mcarplay.app.carlife

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

class CarLifeCommandRouterTest {
    @Test
    fun routesForegroundAndInfoWithoutSendingChallengeEarly() {
        val pair = keyPair()
        val (router, stateMachine) = router(pair)

        stateMachine.onConnected()
        assertEquals(
            listOf(CarLifeProtocol.HU_STATISTICS),
            router.route(command(CarLifeProtocol.MD_PROTOCOL_MATCH)).actions.map { it.kind },
        )
        val foreground = router.route(command(CarLifeProtocol.MD_FOREGROUND))
        assertEquals(listOf(CarLifeProtocol.HU_GO_TO_FOREGROUND), foreground.actions.map { it.kind })
        assertFalse(foreground.failed)

        val info = router.route(command(CarLifeProtocol.MD_INFO, deviceInfoPayload()))
        assertEquals(listOf(CarLifeProtocol.HU_INFO, CarLifeProtocol.HU_AUTH_REQUEST), info.actions.map { it.kind })
        assertTrue(info.log.orEmpty().contains("brand=Xiaomi model=2509FPN0BC sdk=29"))
    }

    @Test
    fun routesFullHandshakeThroughEmptyEncryptReadyToVideoStart() {
        val pair = keyPair()
        val (router, stateMachine) = router(pair)
        stateMachine.onConnected()
        connectToInfo(router)

        assertEquals(
            listOf(
                CarLifeProtocol.HU_AUTH_RESULT,
            ),
            router.route(command(CarLifeProtocol.MD_AUTH_RESPONSE, authPayload(pair))).actions.map { it.kind },
        )
        val admitted = router.route(command(CarLifeProtocol.MD_AUTH_RESULT, CarLifeProtocol.pbInt(1, 1)))
        assertEquals(listOf(CarLifeProtocol.HU_MD_AUTH_RESULT_ACK), admitted.actions.map { it.kind })
        assertFalse(admitted.failed)
        assertEquals(
            listOf(CarLifeProtocol.HU_FEATURE_RESPONSE),
            router.route(command(CarLifeProtocol.MD_FEATURE_REQUEST)).actions.map { it.kind },
        )
        val rsaPayload =
            router
                .route(command(CarLifeProtocol.MD_RSA_KEY_REQUEST))
                .actions
                .single()
                .payload
        val encryptedSeed =
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, publicKey(rsaPayload))
                doFinal(ByteArray(16) { 0x5A })
            }
        assertEquals(
            listOf(CarLifeProtocol.HU_AES_ACK),
            router
                .route(
                    command(
                        CarLifeProtocol.MD_AES_SEED,
                        CarLifeProtocol.pbString(1, Base64.getEncoder().encodeToString(encryptedSeed)),
                    ),
                ).actions
                .map { it.kind },
        )
        assertTrue(router.route(command(CarLifeProtocol.MD_ENCRYPT_READY)).encryptionReady)
        assertEquals(
            listOf(CarLifeProtocol.VIDEO_ENCODER_INIT),
            stateMachine.requestVideoInit().map { it.kind },
        )
        assertEquals(
            listOf(CarLifeProtocol.VIDEO_ENCODER_START),
            router
                .route(command(CarLifeProtocol.VIDEO_ENCODER_INIT_DONE, stateMachine.payloadCipher!!.encrypt(videoFormatPayload())))
                .actions
                .map { it.kind },
        )
        assertEquals(CarLifeProtocol.VideoFormat(1280, 720, 30), stateMachine.videoFormat)
        assertEquals(
            listOf(CarLifeProtocol.HU_GO_TO_FOREGROUND),
            router.route(command(CarLifeProtocol.MD_FOREGROUND)).actions.map { it.kind },
        )
        assertEquals(CarLifeStateMachine.State.VIDEO_RUNNING, stateMachine.state)
        assertTrue(router.route(command(CarLifeProtocol.VIDEO_ENCODER_INIT_DONE, byteArrayOf(1, 2))).failed)
    }

    @Test
    fun duplicateInfoFailsWithoutAnotherChallenge() {
        val (router, stateMachine) = router(keyPair())
        stateMachine.onConnected()
        connectToInfo(router)

        val duplicate = router.route(command(CarLifeProtocol.MD_INFO, deviceInfoPayload()))
        assertTrue(duplicate.actions.isEmpty())
        assertTrue(duplicate.failed)
    }

    @Test
    fun authResultBeforeProtocolMatchFails() {
        val (router, stateMachine) = router(keyPair())
        stateMachine.onConnected()

        val result = router.route(command(CarLifeProtocol.MD_AUTH_RESULT, CarLifeProtocol.pbInt(1, 1)))
        assertTrue(result.actions.isEmpty())
        assertTrue(result.failed)
        assertEquals(CarLifeStateMachine.State.FAILED, stateMachine.state)
    }

    private fun connectToInfo(router: CarLifeCommandRouter) {
        router.route(command(CarLifeProtocol.MD_PROTOCOL_MATCH))
        assertEquals(
            listOf(CarLifeProtocol.HU_INFO, CarLifeProtocol.HU_AUTH_REQUEST),
            router.route(command(CarLifeProtocol.MD_INFO, deviceInfoPayload())).actions.map { it.kind },
        )
    }

    private fun router(pair: KeyPair): Pair<CarLifeCommandRouter, CarLifeStateMachine> {
        val stateMachine =
            CarLifeStateMachine(
                CarLifeAuthVerifier(Base64.getEncoder().encodeToString(pair.public.encoded)),
                CarLifeSeedExchange(),
                challengeFactory = { CarLifeProtocol.LEGACY_CHALLENGE },
            )
        return CarLifeCommandRouter(stateMachine) to stateMachine
    }

    private fun command(
        kind: Int,
        payload: ByteArray = ByteArray(0),
    ): CarLifeFrame =
        CarLifeFrame(
            channel = CarLifeProtocol.PORT_COMMAND,
            kind = kind,
            timestamp = 0,
            reserved = 0,
            payload = payload,
        )

    private fun deviceInfoPayload(): ByteArray =
        CarLifeProtocol.pbString(4, "Xiaomi") +
            CarLifeProtocol.pbString(14, "2509FPN0BC") +
            CarLifeProtocol.pbString(20, "29")

    private fun videoFormatPayload(): ByteArray =
        CarLifeProtocol.pbInt(1, 1280) +
            CarLifeProtocol.pbInt(2, 720) +
            CarLifeProtocol.pbInt(3, 30)

    private fun publicKey(payload: ByteArray): RSAPublicKey {
        val encoded = CarLifeProtocol.firstStringField(payload, 1) ?: error("RSA public key missing")
        return java.security.KeyFactory
            .getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(encoded))) as RSAPublicKey
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    private fun authPayload(pair: KeyPair): ByteArray {
        val key = pair.private as RSAPrivateKey
        val message = CarLifeProtocol.LEGACY_CHALLENGE.toByteArray()
        val size = (key.modulus.bitLength() + 7) / 8
        val encoded = ByteArray(size)
        encoded[1] = 1
        encoded.fill(0xFF.toByte(), 2, size - message.size - 1)
        encoded[size - message.size - 1] = 0
        message.copyInto(encoded, size - message.size)
        val signature =
            BigInteger(1, encoded)
                .modPow(key.privateExponent, key.modulus)
                .toByteArray()
                .let { value ->
                    when {
                        value.size > size -> value.copyOfRange(value.size - size, value.size)
                        value.size < size -> ByteArray(size - value.size) + value
                        else -> value
                    }
                }
        return CarLifeProtocol.pbString(1, Base64.getEncoder().encodeToString(signature))
    }
}
