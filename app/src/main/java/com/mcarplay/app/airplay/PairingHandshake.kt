package com.mcarplay.app.airplay

import java.io.IOException

internal class PairingRejectedException(
    val path: String,
    val state: Int,
    val errorCode: Int,
) : IOException("$path state=$state TLV error=$errorCode(${PairingErrorCode.of(errorCode).label})")

internal fun decodePairingResponse(
    path: String,
    expectedState: Int,
    body: ByteArray,
): Map<Int, ByteArray> {
    val response = Tlv8.decode(body)
    val state = response[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
    val error = response[Tlv8.TYPE_ERROR]?.firstOrNull()?.toInt()?.and(0xFF)
    if (error != null) throw PairingRejectedException(path, state, error)
    if (state != expectedState) throw IOException("$path state=$state expected=$expectedState")
    return response
}

internal class PairingHandshake(
    private val identity: ControllerIdentity,
    private val transport: () -> TlvTransport,
    private val reconnect: () -> Unit,
    private val log: (String) -> Unit,
    private val onSetupForbidden: () -> Unit = {},
    private val onTrustInvalidated: () -> Unit = {},
    /** pair-setup 完成（M6 通过）；复用已保存信任或 403 后直接 verify 时不调用。 */
    private val onSetupComplete: () -> Unit = {},
) {
    data class Result(
        val keys: PairVerifyClient.ControlKeys,
        val receiver: PairSetupClient.ReceiverIdentity?,
    )

    fun run(
        remembered: PairSetupClient.ReceiverIdentity?,
        advertisedKey: ByteArray?,
    ): Result {
        if (remembered != null) {
            log("PAIRING: stage=VERIFY reuse=true")
            try {
                return Result(verify(remembered.publicKey, remembered.pairingId), remembered)
            } catch (error: PairingRejectedException) {
                if (error.errorCode != 2) throw error
                onTrustInvalidated()
                log("PAIRING: 已保存的信任被明确拒绝——换新连接重新注册一次")
                reconnect()
            }
        }
        log("PAIRING: stage=SETUP reuse=false")
        val receiver =
            try {
                PairSetupClient(identity, transport(), log).run().also { onSetupComplete() }
            } catch (error: IOException) {
                if (!error.message.orEmpty().contains("403")) throw error
                onSetupForbidden()
                log("PAIRING: pair-setup 被 403 拒绝——换新连接尝试 pair-verify")
                reconnect()
                return Result(verify(advertisedKey, null), null)
            }
        log("PAIRING: stage=VERIFY reuse=false")
        return Result(verify(receiver?.publicKey ?: advertisedKey, receiver?.pairingId), receiver)
    }

    private fun verify(
        publicKey: ByteArray?,
        receiverId: String?,
    ): PairVerifyClient.ControlKeys = PairVerifyClient(identity, transport(), log).run(publicKey, receiverId)
}
