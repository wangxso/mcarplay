package com.mcarplay.app.airplay

import java.io.IOException

/** TLV8 请求/应答传输抽象：真实实现走 RTSP POST，测试用 mock 接收端直连。 */
fun interface TlvTransport {
    /** 发送一个 TLV8 请求体，返回接收端的 TLV8 应答体。 */
    fun exchange(
        path: String,
        body: ByteArray,
    ): ByteArray
}

/** 手机（控制器）长期身份：ed25519 密钥对 + 配对 ID，首次使用时生成。 */
class ControllerIdentity(
    val pairingId: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/**
 * AirPlay /pair-setup 客户端（M1/M3/M5）。
 * 参考接收端：固定码 SRP-6a（"Pair-Setup"/"3939"），之后在加密信封里交换长期 ed25519 公钥。
 */
class PairSetupClient(
    private val identity: ControllerIdentity,
    private val transport: TlvTransport,
    private val log: (String) -> Unit,
) {
    /** 完成后接收端已保存我们的长期公钥；返回接收端静态身份（来自 M6，可选用）。 */
    fun run(): ReceiverIdentity? {
        // M1: 请求 SRP 参数（Method=None + State=1，对照 catplay PairSetup::start）
        val m2 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_METHOD, byteArrayOf(0)),
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                        ),
                    ),
                ),
            )
        val salt = m2[Tlv8.TYPE_SALT] ?: throw IOException("pair-setup M2 缺少 salt")
        val serverPublic = m2[Tlv8.TYPE_PUBLIC_KEY] ?: throw IOException("pair-setup M2 缺少 publicKey")
        log("pair-setup: M2 收到 SRP 参数 salt=${salt.size}B B=${serverPublic.size}B")

        // M3: 客户端公钥 A + 证明 M1
        val srp = Srp6aClient.start(salt, serverPublic)
        val clientProof = srp.clientProof() ?: throw IOException("pair-setup: SRP B 非法")
        val m4 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(3)),
                            Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, srp.publicKeyA),
                            Tlv8.Item(Tlv8.TYPE_PROOF, clientProof),
                        ),
                    ),
                ),
            )
        val serverProof = m4[Tlv8.TYPE_PROOF] ?: throw IOException("pair-setup M4 缺少 proof")
        if (!srp.serverProofValid(serverProof)) throw IOException("pair-setup: 服务器证明校验失败")
        val sessionKey = srp.sessionKey ?: throw IOException("pair-setup: 会话密钥缺失")
        log("pair-setup: M4 SRP 互验通过")

        // M5: 加密信封提交长期公钥
        val encryptionKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Encrypt-Salt".toByteArray(),
                "Pair-Setup-Encrypt-Info".toByteArray(),
            )
        val controllerSignKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Controller-Sign-Salt".toByteArray(),
                "Pair-Setup-Controller-Sign-Info".toByteArray(),
            )
        val identifierBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature =
            AirPlayCrypto.ed25519Sign(
                identity.privateKey,
                controllerSignKey + identifierBytes + identity.publicKey,
            )
        val sub =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, identifierBytes),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, identity.publicKey),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        val m6 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(5)),
                            Tlv8.Item(
                                Tlv8.TYPE_ENCRYPTED_DATA,
                                AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg05"), sub),
                            ),
                        ),
                    ),
                ),
            )
        if (m6[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt() != 6) {
            throw IOException("pair-setup M6 状态非 6：${m6[Tlv8.TYPE_ERROR]?.firstOrNull()}")
        }
        log("pair-setup: ✓ M6 完成，接收端已保存我们的长期公钥")

        // 解析 M6 加密信封里的接收端身份（完整性校验）
        val encrypted = m6[Tlv8.TYPE_ENCRYPTED_DATA] ?: return null
        val inner =
            Tlv8.decode(
                AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg06"), encrypted),
            )
        val receiverId = inner[Tlv8.TYPE_IDENTIFIER] ?: return null
        val receiverKey = inner[Tlv8.TYPE_PUBLIC_KEY] ?: return null
        val receiverSignature = inner[Tlv8.TYPE_SIGNATURE] ?: return null
        val accessorySignKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Accessory-Sign-Salt".toByteArray(),
                "Pair-Setup-Accessory-Sign-Info".toByteArray(),
            )
        val valid =
            AirPlayCrypto.ed25519Verify(
                receiverKey,
                accessorySignKey + receiverId + receiverKey,
                receiverSignature,
            )
        log(
            "pair-setup: 接收端身份 id=${receiverId.toString(Charsets.UTF_8)} " +
                "pub=${receiverKey.toHexString().take(16)}… 签名校验=$valid",
        )
        return ReceiverIdentity(receiverId.toString(Charsets.UTF_8), receiverKey)
    }

    data class ReceiverIdentity(
        val pairingId: String,
        val publicKey: ByteArray,
    )
}

/**
 * AirPlay /pair-verify 客户端（M1/M3）：每次会话的临时 X25519 + 长期 ed25519 签名，
 * 成功后得到控制通道的 ChaCha20 密钥。
 */
class PairVerifyClient(
    private val identity: ControllerIdentity,
    private val transport: TlvTransport,
    private val log: (String) -> Unit,
) {
    class ControlKeys(
        val phoneWriteKey: ByteArray,
        val phoneReadKey: ByteArray,
        val sharedSecret: ByteArray,
    )

    fun run(receiverStaticPublicKey: ByteArray? = null): ControlKeys {
        // M1: 临时 X25519 公钥
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-verify",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                            Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, ephemeral.publicKey),
                        ),
                    ),
                ),
            )
        val receiverEphemeral = m2[Tlv8.TYPE_PUBLIC_KEY] ?: throw IOException("pair-verify M2 缺少 publicKey")
        val encrypted = m2[Tlv8.TYPE_ENCRYPTED_DATA] ?: throw IOException("pair-verify M2 缺少 encryptedData")

        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, receiverEphemeral)
        val encryptionKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Pair-Verify-Encrypt-Salt".toByteArray(),
                "Pair-Verify-Encrypt-Info".toByteArray(),
            )
        val inner =
            Tlv8.decode(
                AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), encrypted),
            )
        val receiverId = inner[Tlv8.TYPE_IDENTIFIER] ?: throw IOException("pair-verify M2 信封缺少 identifier")
        val receiverSignature = inner[Tlv8.TYPE_SIGNATURE] ?: throw IOException("pair-verify M2 信封缺少 signature")

        // 用 mDNS/0x4301 提供的接收端静态公钥验证（有则验，无则跳过）
        if (receiverStaticPublicKey != null) {
            val valid =
                AirPlayCrypto.ed25519Verify(
                    receiverStaticPublicKey,
                    receiverEphemeral + receiverId + ephemeral.publicKey,
                    receiverSignature,
                )
            log("pair-verify: 接收端签名校验（对照 pk）=$valid")
        }

        // M3: 我们的长期签名
        val identifierBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature =
            AirPlayCrypto.ed25519Sign(
                identity.privateKey,
                ephemeral.publicKey + identifierBytes + receiverEphemeral,
            )
        val sub =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, identifierBytes),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        val m4 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-verify",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(3)),
                            Tlv8.Item(
                                Tlv8.TYPE_ENCRYPTED_DATA,
                                AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub),
                            ),
                        ),
                    ),
                ),
            )
        if (m4[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt() != 4) {
            throw IOException("pair-verify M4 状态非 4：${m4[Tlv8.TYPE_ERROR]?.firstOrNull()}")
        }
        log("pair-verify: ✓ 完成，派生控制通道密钥")

        // 方向对照接收端实现：接收端 readKey="Control-Write-…"（即我们发出的方向）
        val phoneWriteKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Control-Salt".toByteArray(),
                "Control-Write-Encryption-Key".toByteArray(),
            )
        val phoneReadKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Control-Salt".toByteArray(),
                "Control-Read-Encryption-Key".toByteArray(),
            )
        return ControlKeys(phoneWriteKey, phoneReadKey, shared)
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
