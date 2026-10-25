package com.mcarplay.app.carlife

/**
 * Pure protocol routing for command-channel frames.
 *
 * Socket ownership stays in [CarLifeSession]; this class makes handshake
 * ordering and outgoing actions testable without Android framework objects.
 */
class CarLifeCommandRouter(
    private val stateMachine: CarLifeStateMachine,
) {
    data class Result(
        val actions: List<CarLifeStateMachine.Action> = emptyList(),
        val log: String? = null,
        val failed: Boolean = false,
        val encryptionReady: Boolean = false,
    )

    fun route(frame: CarLifeFrame): Result {
        if (frame.channel != CarLifeProtocol.PORT_COMMAND) {
            return Result(failed = true)
        }
        val decoded =
            try {
                val cipher = stateMachine.payloadCipher
                if (cipher == null || frame.payload.isEmpty()) frame else frame.copy(payload = cipher.decrypt(frame.payload))
            } catch (_: Exception) {
                stateMachine.fail()
                return Result(log = "CARLIFE: 命令通道解密失败", failed = true)
            }
        return routeDecoded(decoded)
    }

    private fun routeDecoded(frame: CarLifeFrame): Result =
        when (frame.kind) {
            CarLifeProtocol.MD_PROTOCOL_MATCH ->
                result(stateMachine.onProtocolMatched())

            CarLifeProtocol.MD_FOREGROUND -> {
                val foreground = stateMachine.onForeground()
                Result(
                    actions = foreground.actions,
                    log = if (foreground.changed) "CARLIFE: 收到前台请求，已回 GO_TO_FOREGROUND" else null,
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )
            }

            CarLifeProtocol.MD_INFO -> {
                val actions = stateMachine.onInfo(frame.payload)
                Result(
                    actions = actions,
                    log =
                        stateMachine.deviceInfo?.let {
                            "CARLIFE: MD_INFO brand=${it.brand} model=${it.model} sdk=${it.sdk}"
                        },
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )
            }

            CarLifeProtocol.MD_AUTH_RESPONSE ->
                result(stateMachine.onAuthResponse(frame.payload))

            CarLifeProtocol.MD_AUTH_RESULT -> {
                val actions = stateMachine.onPeerAuthResult(frame.payload)
                val failed = stateMachine.state == CarLifeStateMachine.State.FAILED
                Result(
                    actions = actions,
                    log = "CARLIFE: 手机渠道准入结果 ${if (failed) "拒绝或顺序无效" else "通过"}",
                    failed = failed,
                    encryptionReady = stateMachine.canInitializeVideo,
                )
            }

            CarLifeProtocol.MD_FEATURE_REQUEST ->
                result(stateMachine.onFeatureRequest())

            CarLifeProtocol.MD_RSA_KEY_REQUEST ->
                Result(
                    actions = stateMachine.onRsaKeyRequest(),
                    log = "CARLIFE: 收到 RSA 公钥请求",
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )

            CarLifeProtocol.MD_AES_SEED ->
                Result(
                    actions = stateMachine.onEncryptedSeed(frame.payload),
                    log = "CARLIFE: 收到加密 AES 种子",
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )

            CarLifeProtocol.MD_ENCRYPT_READY -> {
                val actions = stateMachine.onEncryptReady(frame.payload)
                Result(
                    actions = actions,
                    log = "CARLIFE: 收到 ENCRYPT_READY",
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                    encryptionReady = stateMachine.canInitializeVideo,
                )
            }

            CarLifeProtocol.VIDEO_ENCODER_INIT_DONE -> {
                val format = CarLifeProtocol.parseVideoFormat(frame.payload)
                if (format == null) {
                    stateMachine.fail()
                    Result(failed = true)
                } else {
                    Result(
                        actions = stateMachine.onVideoInitDone(frame.payload),
                        log = "CARLIFE: VIDEO_INIT_DONE ${format.width}x${format.height}@${format.fps}",
                        failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                    )
                }
            }

            else -> Result()
        }

    private fun result(actions: List<CarLifeStateMachine.Action>): Result =
        Result(
            actions = actions,
            failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
        )
}
