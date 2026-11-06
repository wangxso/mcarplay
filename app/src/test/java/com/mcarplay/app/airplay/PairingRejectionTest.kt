package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PairingRejectionTest {
    private fun rejection(
        path: String,
        code: Int,
        state: Int = 4,
    ) = PairingRejectedException(path, state, code)

    @Test
    fun knownAndPrivateCodesAreMapped() {
        assertEquals(PairingErrorCode.AUTHENTICATION, rejection("/pair-verify", 0x02).code)
        assertEquals(PairingErrorCode.MAX_PEERS, rejection("/pair-setup", 0x04).code)
        assertEquals(PairingErrorCode.OTHER, rejection("/pair-setup", 0x42).code)
    }

    @Test
    fun fullPeerTableAndExhaustedTriesStopImmediately() {
        for (path in listOf("/pair-setup", "/pair-verify")) {
            assertEquals(PairingRejectionAction.STOP, pairingRejectionAction(rejection(path, 0x04)))
            assertEquals(PairingRejectionAction.STOP, pairingRejectionAction(rejection(path, 0x05)))
        }
    }

    @Test
    fun authenticationStopsOnlyWhenReRegistrationAfterRejectedTrustFails() {
        assertEquals(PairingRejectionAction.RETRY, pairingRejectionAction(rejection("/pair-setup", 0x02)))
        assertEquals(PairingRejectionAction.RETRY, pairingRejectionAction(rejection("/pair-verify", 0x02)))
        assertEquals(PairingRejectionAction.RETRY, pairingRejectionAction(rejection("/pair-verify", 0x02), trustInvalidated = true))
        assertEquals(PairingRejectionAction.STOP, pairingRejectionAction(rejection("/pair-setup", 0x02), trustInvalidated = true))
    }

    @Test
    fun transientAndUnknownCodesKeepRetrying() {
        for (code in listOf(0x01, 0x03, 0x06, 0x07, 0x42)) {
            for (path in listOf("/pair-setup", "/pair-verify")) {
                assertEquals(PairingRejectionAction.RETRY, pairingRejectionAction(rejection(path, code), trustInvalidated = true))
            }
        }
    }

    @Test
    fun backoffSlowsRetriesLikeForbidden() {
        assertEquals(5_000L, pairingRetryDelayMs(rejection("/pair-setup", 0x03)))
        assertEquals(500L, pairingRetryDelayMs(rejection("/pair-setup", 0x07)))
        assertEquals(5_000L, pairingRetryDelayMs(IOException("RTSP/1.0 403 Forbidden")))
        assertEquals(500L, pairingRetryDelayMs(IOException("Connection reset")))
    }

    /** 调用方按 contains("403") / "404" 等判 HTTP 状态：TLV 拒绝的消息不能撞上这些子串。 */
    @Test
    fun messageNamesCodeButNeverLooksLikeHttpStatus() {
        for (state in 0..0xFF) {
            for (code in 0..0xFF) {
                val message = rejection("/pair-verify", code, state).message.orEmpty()
                for (status in listOf("403", "404", "405", "455", "501")) {
                    assertFalse("$message 含 $status", message.contains(status))
                }
            }
        }
        assertTrue(rejection("/pair-verify", 0x02).message.orEmpty().contains("error=2(Authentication)"))
    }

    /** 车机忘了本机又拒绝重新注册：握手以 pair-setup 的 Authentication 收尾，且信任已作废。 */
    @Test
    fun rejectedTrustAndRejectedReRegistrationEndInStop() {
        val rejectAll =
            TlvTransport { _, _ ->
                Tlv8.encode(
                    listOf(
                        Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(2)),
                        Tlv8.Item(Tlv8.TYPE_ERROR, byteArrayOf(2)),
                    ),
                )
            }
        var trustInvalidated = false
        val keyPair = AirPlayCrypto.ed25519Generate()
        val handshake =
            PairingHandshake(
                ControllerIdentity("controller", keyPair.privateKey, keyPair.publicKey),
                { rejectAll },
                {},
                {},
                onTrustInvalidated = { trustInvalidated = true },
            )
        val remembered = PairSetupClient.ReceiverIdentity("receiver", AirPlayCrypto.ed25519Generate().publicKey)
        val error = assertThrows(PairingRejectedException::class.java) { handshake.run(remembered, null) }
        assertTrue(trustInvalidated)
        assertEquals("/pair-setup", error.path)
        assertEquals(PairingRejectionAction.STOP, pairingRejectionAction(error, trustInvalidated))
    }
}
