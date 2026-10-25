package com.mcarplay.app.carlife

import java.security.MessageDigest
import java.security.SecureRandom

/** CarLife 2.0 proof binding observed in the installed receiver's verification library. */
object CarLifeAuthentication {
    private const val PREFIX = "2.0;"
    private val noncePattern = Regex("[A-Za-z0-9_-]{6,128}")
    private val proofPattern = Regex("[0-9a-f]{32}")

    fun newChallenge(): String {
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
        return PREFIX + nonce.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    fun isVersion2(challenge: String): Boolean = challenge.startsWith(PREFIX)

    /**
     * This protocol uses an MD5 digest, not an RSA signature. Match its actual
     * device fields and fresh nonce; never fall back to legacy RSA on failure.
     */
    fun verifyVersion2(
        challenge: String,
        info: CarLifeProtocol.DeviceInfo?,
        proof: String,
    ): Boolean {
        if (!isVersion2(challenge) || info == null || !proofPattern.matches(proof)) return false
        val nonce = challenge.removePrefix(PREFIX)
        if (!noncePattern.matches(nonce)) return false
        val input = "CARLIFE${info.brand}${info.model}${info.sdk}$nonce".toByteArray(Charsets.UTF_8)
        val expected =
            MessageDigest.getInstance("MD5").digest(input).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), proof.toByteArray(Charsets.US_ASCII))
    }
}
