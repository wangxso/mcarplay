package com.mcarplay.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class RsaLockdownIdentityTest {
    @Test
    fun certificateAndPublicPemUseTheSameGeneratedKey() {
        val identity = RsaLockdownIdentity.generate()
        val pem = identity.publicKeyPkcs1Pem.toString(Charsets.US_ASCII)
        val base64 =
            pem
                .substringAfter("-----BEGIN RSA PUBLIC KEY-----")
                .substringBefore("-----END RSA PUBLIC KEY-----")
                .filterNot(Char::isWhitespace)
        val parsed = parsePkcs1(Base64.getDecoder().decode(base64))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(parsed)
        assertArrayEquals(publicKey.encoded, identity.keyPair.public.encoded)

        identity.certificate.verify(identity.keyPair.public)
        identity.certificate.checkValidity()
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(identity.keyPair.private)
        signature.update("mcarplay".toByteArray())
        val signed = signature.sign()
        signature.initVerify(publicKey)
        signature.update("mcarplay".toByteArray())
        assertTrue(signature.verify(signed))
        assertEquals("CN=iPhone", identity.certificate.subjectX500Principal.name)
    }

    private fun parsePkcs1(der: ByteArray): RSAPublicKeySpec {
        val cursor = Cursor(der)
        cursor.expect(0x30)
        val sequenceEnd = cursor.endOf(cursor.readLength())
        cursor.expect(0x02)
        val modulus = cursor.readInteger()
        cursor.expect(0x02)
        val exponent = cursor.readInteger()
        assertEquals(sequenceEnd, cursor.position)
        return RSAPublicKeySpec(modulus, exponent)
    }

    private class Cursor(
        private val bytes: ByteArray,
    ) {
        var position = 0

        fun expect(tag: Int) {
            assertEquals(tag, bytes[position++].toInt() and 0xFF)
        }

        fun readLength(): Int {
            val first = bytes[position++].toInt() and 0xFF
            if (first < 0x80) return first
            val count = first and 0x7F
            var value = 0
            repeat(count) { value = (value shl 8) or (bytes[position++].toInt() and 0xFF) }
            return value
        }

        fun endOf(length: Int): Int = position + length

        fun readInteger(): java.math.BigInteger {
            val length = readLength()
            val value = bytes.copyOfRange(position, position + length)
            position += length
            return java.math.BigInteger(value)
        }
    }
}
