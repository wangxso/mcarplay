package com.mcarplay.app.airplay

import java.math.BigInteger
import java.security.SecureRandom

/**
 * SRP-6a 客户端（RFC 5054 3072-bit 组，SHA-512）——AirPlay pair-setup 变体。
 * 与参考接收端 Srp6a.kt 的约定逐项对齐：
 *  - x = SHA512(salt, SHA512(I || ":" || P))，I = "Pair-Setup"，P = 固定码 "3939"
 *  - k = SHA512(pad(N), pad(g))
 *  - 会话密钥 K = SHA512(bytes(S))；客户端证明 M1 = SHA512(H(N)^H(g), H(I), salt, pad(A), pad(B), K)
 *
 * **填充约定无关：** 接收端在 u、M1、K 里对 A、B、S 用补零还是去前导零的编码各家不一；只要其中任一值
 * 的首字节为 0（各约 1/256），两种编码就不同，接收端判证明错误。2026-11-02 艾瑞泽 8 一次 pair-setup
 * 车机报 `bad setup code`，此前同一固定码数十次成功、紧接着的重试又成功，与此吻合。因此 [start] 重选私钥
 * 直到 A 与 S 都是满 [KEY_BYTES] 字节；B 由接收端决定，满长与否见 [isFullLength]，不满时调用方重新取 M2。
 */
object Srp6aClient {
    private val random = SecureRandom()
    internal val n = BigInteger(N_HEX, 16)
    internal val g: BigInteger = BigInteger.valueOf(5)
    internal val k = toBigInt(AirPlayCrypto.sha512(pad(n), pad(g)))
    private val COLON = byteArrayOf(':'.code.toByte())

    internal fun modulus(): BigInteger = n

    internal fun generator(): BigInteger = g

    internal fun multiplier(): BigInteger = k

    const val USERNAME = "Pair-Setup"
    const val SETUP_CODE = "3939"

    class Session internal constructor(
        val publicKeyA: ByteArray,
        private val b: ByteArray,
        private val salt: ByteArray,
        private val identifier: ByteArray,
        private val sharedSecret: BigInteger?,
    ) {
        /** K = SHA512(bytes(S))，在首次 [clientProof] 成功后可用。 */
        var sessionKey: ByteArray? = null
            private set

        /** S 的大端编码长度（测试用：[start] 保证非退化时为 [KEY_BYTES]）。 */
        internal val sharedSecretSize: Int get() = sharedSecret?.let { toBytes(it).size } ?: 0

        /** 生成客户端证明 M1；若 B 非法则返回 null。 */
        fun clientProof(): ByteArray? {
            val s = sharedSecret ?: return null
            val key = AirPlayCrypto.sha512(toBytes(s))
            sessionKey = key

            val hashN = AirPlayCrypto.sha512(toBytes(n))
            val hashG = AirPlayCrypto.sha512(toBytes(g))
            val hashXor = ByteArray(hashN.size)
            for (index in hashN.indices) {
                hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
            }
            return AirPlayCrypto.sha512(hashXor, AirPlayCrypto.sha512(identifier), salt, publicKeyA, pad(toBigInt(b)), key)
        }

        /** 服务器证明 HAMK = SHA512(pad(A), M1, K)。 */
        fun serverProofValid(serverProof: ByteArray): Boolean {
            val m1 = clientProof() ?: return false
            val key = sessionKey ?: return false
            return AirPlayCrypto.sha512(publicKeyA, m1, key).contentEquals(serverProof)
        }
    }

    /** [value] 的大端编码是否恰为 [KEY_BYTES] 字节（首字节非 0），即补零与否编码相同。 */
    fun isFullLength(value: ByteArray): Boolean = toBytes(toBigInt(value)).size == KEY_BYTES

    fun start(
        salt: ByteArray,
        publicKeyB: ByteArray,
    ): Session {
        val identifier = USERNAME.toByteArray(Charsets.UTF_8)
        val bigB = toBigInt(publicKeyB)
        val x = toBigInt(AirPlayCrypto.sha512(salt, AirPlayCrypto.sha512(identifier, COLON, SETUP_CODE.toByteArray())))
        val base = (bigB - k * g.modPow(x, n)).mod(n)
        val degenerate = bigB.mod(n).signum() == 0 || base.signum() == 0
        var publicKeyA = ByteArray(0)
        var sharedSecret: BigInteger? = null
        var attempts = 0
        while (attempts++ < MAX_KEY_ATTEMPTS) {
            val a = toBigInt(ByteArray(32).also(random::nextBytes))
            val bigA = g.modPow(a, n)
            publicKeyA = pad(bigA)
            if (degenerate) break
            val u = toBigInt(AirPlayCrypto.sha512(publicKeyA, pad(bigB)))
            val s = base.modPow(a + u * x, n)
            sharedSecret = s
            if (toBytes(bigA).size == KEY_BYTES && toBytes(s).size == KEY_BYTES) break
        }
        return Session(
            publicKeyA = publicKeyA,
            b = publicKeyB,
            salt = salt,
            identifier = identifier,
            sharedSecret = sharedSecret,
        )
    }

    internal fun toBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return byteArrayOf(0)
        val encoded = value.toByteArray()
        return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
    }

    internal fun toBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    internal fun pad(value: BigInteger): ByteArray {
        val bytes = toBytes(value)
        if (bytes.size >= KEY_BYTES) return bytes
        return ByteArray(KEY_BYTES).also { bytes.copyInto(it, KEY_BYTES - bytes.size) }
    }

    /** 3072-bit 组的模长（字节）。 */
    const val KEY_BYTES = 384

    /** 每次重选私钥失败率约 2/256，16 次后仍不满长的概率可忽略；用尽时沿用最后一组。 */
    private const val MAX_KEY_ATTEMPTS = 16

    /** RFC 5054 3072-bit 素数（与参考实现一致）。 */
    private const val N_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
            "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB" +
            "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8" +
            "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208" +
            "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
}
