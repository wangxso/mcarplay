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
        /** 用户点了「返回车机」：应把主屏与主音频交还车机。 */
        val returnToCar: Boolean = false,
    )

    /** 只在命令读取线程上访问。 */
    private val unknownKindCounts = HashMap<Int, Int>()

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

            CarLifeProtocol.MD_RETURN_TO_CAR ->
                Result(log = "CARLIFE: 收到返回车机命令 kind=0x10021，交还主屏与主音频", returnToCar = true)

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

            else -> Result(log = unknownCommandLog(frame))
        }

    /**
     * 未识别的 MD 命令只记 kind、长度和 protobuf 字段形状，不记内容；用来查清 CarWith 按钮
     * （如「返回车机」）实际发出的命令。每种 kind 前 [UNKNOWN_LOG_FIRST] 次逐条记录，之后只在第 2ⁿ 次记录。
     */
    private fun unknownCommandLog(frame: CarLifeFrame): String? {
        val count = unknownKindCounts[frame.kind]?.plus(1) ?: if (unknownKindCounts.size < MAX_TRACKED_UNKNOWN_KINDS) 1 else return null
        unknownKindCounts[frame.kind] = count
        if (count > UNKNOWN_LOG_FIRST && count and (count - 1) != 0) return null
        val kind =
            "0x" +
                frame.kind
                    .toUInt()
                    .toString(16)
                    .uppercase()
        return "CARLIFE: 未处理命令 kind=$kind len=${frame.payload.size} fields=${protobufShape(frame.payload)} 第${count}次"
    }

    companion object {
        private const val UNKNOWN_LOG_FIRST = 3
        private const val MAX_TRACKED_UNKNOWN_KINDS = 64
        private const val MAX_SHAPE_FIELDS = 8
        private const val MAX_LOGGED_VARINT = 255L

        /** 例如 `[1=2, 2:len4]`；只有 0..255 的 varint 写出数值（多为枚举），其余只写类型和长度。 */
        internal fun protobufShape(payload: ByteArray): String {
            if (payload.isEmpty()) return "[]"
            val parts = ArrayList<String>()
            var offset = 0
            try {
                while (offset < payload.size) {
                    if (parts.size == MAX_SHAPE_FIELDS) {
                        parts += "…"
                        break
                    }
                    val (tag, tagSize) = CarLifeProtocol.readVarint(payload, offset)
                    offset += tagSize
                    val field = tag ushr 3
                    when ((tag and 7).toInt()) {
                        0 -> {
                            val (value, size) = CarLifeProtocol.readVarint(payload, offset)
                            offset += size
                            parts += if (value in 0..MAX_LOGGED_VARINT) "$field=$value" else "$field:varint"
                        }
                        1 -> {
                            offset += 8
                            parts += "$field:fixed64"
                        }
                        2 -> {
                            val (length, size) = CarLifeProtocol.readVarint(payload, offset)
                            offset += size
                            require(length in 0..(payload.size - offset).toLong())
                            offset += length.toInt()
                            parts += "$field:len$length"
                        }
                        5 -> {
                            offset += 4
                            parts += "$field:fixed32"
                        }
                        else -> error("wire type")
                    }
                    require(offset <= payload.size)
                }
            } catch (_: Exception) {
                return "非protobuf"
            }
            return parts.joinToString(prefix = "[", postfix = "]")
        }
    }

    private fun result(actions: List<CarLifeStateMachine.Action>): Result =
        Result(
            actions = actions,
            failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
        )
}
