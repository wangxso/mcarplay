package com.mcarplay.app.carlife

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher

/**
 * One-shot RSA-2048 exchange used by CarLife's CONTENT_ENCRYPTION flow.
 *
 * The HU publishes an ephemeral RSA-2048 key. The phone returns its AES
 * seed encrypted with that key. Control/video and audio INIT bodies use AES;
 * Xiaomi's mixed-audio PCM payloads have their own protection rule.
 */
class CarLifeSeedExchange(
    private val random: SecureRandom = SecureRandom(),
) {
    private var keyPair: KeyPair? = null

    fun createPublicKey(): String {
        check(keyPair == null) { "CarLife seed exchange already created" }
        val pair =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048, random) }
                .generateKeyPair()
        keyPair = pair
        return Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    fun decryptSeed(encryptedSeedBase64: String): ByteArray? =
        runCatching {
            val pair = keyPair ?: return null
            val encryptedSeed = Base64.getDecoder().decode(encryptedSeedBase64)
            Cipher
                .getInstance("RSA/ECB/PKCS1Padding")
                .run {
                    init(Cipher.DECRYPT_MODE, pair.private)
                    doFinal(encryptedSeed)
                }
        }.getOrNull()?.takeIf { it.size in intArrayOf(16, 24, 32) }

    fun clear() {
        keyPair = null
    }
}
