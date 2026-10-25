package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

class CarLifeAuthenticationTest {
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val challenge = "2.0;$nonce"
    private val info = CarLifeProtocol.DeviceInfo("Xiaomi", "2509FPN0BC", "29")
    private val proof = "a16c98b37110fe95360bb686025d6fec"

    @Test
    fun version2AcceptsIndependentDigestVectorAndRejectsOtherSessionOrDevice() {
        val verifier = CarLifeAuthVerifier.xiaomi()
        val payload = CarLifeProtocol.pbString(1, proof)
        assertTrue(verifier.verify(challenge, payload, info))
        assertFalse(verifier.verify("2.0;different_nonce", payload, info))
        assertFalse(verifier.verify(challenge, payload, info.copy(model = "other")))
        assertFalse(verifier.verify(challenge, payload, info.copy(sdk = "30")))
        assertFalse(verifier.verify(challenge, payload))
        assertFalse(verifier.verify(CarLifeProtocol.LEGACY_CHALLENGE, payload, info))
    }

    @Test
    fun malformedProofAndChallengeCannotFallBackToLegacy() {
        for (invalid in listOf("", "true", proof.uppercase(), proof.dropLast(1), "0".repeat(32))) {
            assertFalse(CarLifeAuthentication.verifyVersion2(challenge, info, invalid))
        }
        for (invalid in listOf("2.0;", "2.0;short", "2.0;$nonce;extra", "2.0;${"a".repeat(129)}")) {
            assertFalse(CarLifeAuthentication.verifyVersion2(invalid, info, proof))
        }
    }

    @Test
    fun challengeUsesFresh128BitNonce() {
        val first = CarLifeAuthentication.newChallenge()
        val second = CarLifeAuthentication.newChallenge()
        assertTrue(Regex("2\\.0;[0-9a-f]{32}").matches(first))
        assertTrue(Regex("2\\.0;[0-9a-f]{32}").matches(second))
        assertNotEquals(first, second)
    }

    @Test
    fun earlyAdmissionAndFeatureRequestWaitForVerifiedProof() {
        val machine = connectedMachine()
        val ack = machine.onPeerAuthResult(CarLifeProtocol.pbInt(1, 1))
        assertEquals(listOf(CarLifeProtocol.HU_MD_AUTH_RESULT_ACK), ack.map { it.kind })
        assertTrue(machine.onFeatureRequest().isEmpty())
        assertEquals(CarLifeStateMachine.State.PROTOCOL_MATCHED, machine.state)
        val requests = machine.onInfo(infoPayload())
        assertEquals(listOf(CarLifeProtocol.HU_INFO, CarLifeProtocol.HU_AUTH_REQUEST), requests.map { it.kind })
        assertEquals(challenge, CarLifeProtocol.firstStringField(requests.last().payload, 1))
        assertEquals(
            listOf(CarLifeProtocol.HU_AUTH_RESULT, CarLifeProtocol.HU_FEATURE_RESPONSE),
            machine.onAuthResponse(CarLifeProtocol.pbString(1, proof)).map { it.kind },
        )
        assertEquals(CarLifeStateMachine.State.FEATURES_READY, machine.state)
    }

    @Test
    fun encryptedLateAdmissionStillGatesVideoInitialization() {
        val machine = connectedMachine(CarLifeSeedExchange())
        machine.onInfo(infoPayload())
        assertEquals(
            listOf(CarLifeProtocol.HU_AUTH_RESULT),
            machine.onAuthResponse(CarLifeProtocol.pbString(1, proof)).map { it.kind },
        )
        assertEquals(listOf(CarLifeProtocol.HU_FEATURE_RESPONSE), machine.onFeatureRequest().map { it.kind })
        finishEncryption(machine)
        assertFalse(machine.canInitializeVideo)
        assertTrue(machine.requestVideoInit().isEmpty())
        assertEquals(CarLifeStateMachine.State.ENCRYPTED, machine.state)
        val admission =
            CarLifeCommandRouter(machine).route(
                CarLifeFrame(
                    channel = CarLifeProtocol.PORT_COMMAND,
                    kind = CarLifeProtocol.MD_AUTH_RESULT,
                    timestamp = 0,
                    reserved = 0,
                    payload = machine.payloadCipher!!.encrypt(CarLifeProtocol.pbInt(1, 1)),
                ),
            )
        assertEquals(
            listOf(CarLifeProtocol.HU_MD_AUTH_RESULT_ACK),
            admission.actions.map { it.kind },
        )
        assertTrue(admission.encryptionReady)
        assertEquals(listOf(CarLifeProtocol.VIDEO_ENCODER_INIT), machine.requestVideoInit().map { it.kind })
    }

    @Test
    fun rejectedAdmissionAndTamperedProofNeverEmitSuccessOrFeatures() {
        val rejected = connectedMachine()
        rejected.onInfo(infoPayload())
        assertTrue(rejected.onPeerAuthResult(CarLifeProtocol.pbInt(1, 0)).isEmpty())
        assertEquals(CarLifeStateMachine.State.FAILED, rejected.state)
        assertTrue(rejected.onAuthResponse(CarLifeProtocol.pbString(1, proof)).isEmpty())

        val tampered = connectedMachine()
        tampered.onPeerAuthResult(CarLifeProtocol.pbInt(1, 1))
        tampered.onFeatureRequest()
        tampered.onInfo(infoPayload())
        assertTrue(tampered.onAuthResponse(CarLifeProtocol.pbString(1, "0".repeat(32))).isEmpty())
        assertEquals(CarLifeStateMachine.State.FAILED, tampered.state)
        assertTrue(tampered.onRsaKeyRequest().isEmpty())
    }

    @Test
    fun repeatedAdmissionOrFeatureRequestsFailWithoutMoreActions() {
        val admitted = connectedMachine()
        admitted.onPeerAuthResult(CarLifeProtocol.pbInt(1, 1))
        assertTrue(admitted.onPeerAuthResult(CarLifeProtocol.pbInt(1, 1)).isEmpty())
        assertEquals(CarLifeStateMachine.State.FAILED, admitted.state)

        val features = connectedMachine()
        features.onFeatureRequest()
        assertTrue(features.onFeatureRequest().isEmpty())
        assertEquals(CarLifeStateMachine.State.FAILED, features.state)
    }

    private fun connectedMachine(exchange: CarLifeSeedExchange? = null): CarLifeStateMachine =
        CarLifeStateMachine(CarLifeAuthVerifier.xiaomi(), exchange, challengeFactory = { challenge }).apply {
            onConnected()
            onProtocolMatched()
        }

    private fun finishEncryption(machine: CarLifeStateMachine) {
        val payload = machine.onRsaKeyRequest().single().payload
        val encoded = Base64.getDecoder().decode(CarLifeProtocol.firstStringField(payload, 1)!!)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
        val seed =
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal(ByteArray(16) { 0x5A })
            }
        machine.onEncryptedSeed(CarLifeProtocol.pbString(1, Base64.getEncoder().encodeToString(seed)))
        machine.onEncryptReady(ByteArray(0))
    }

    private fun infoPayload(): ByteArray =
        CarLifeProtocol.pbString(4, info.brand) +
            CarLifeProtocol.pbString(14, info.model) +
            CarLifeProtocol.pbString(20, info.sdk)
}
