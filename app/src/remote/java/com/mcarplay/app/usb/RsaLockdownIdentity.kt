package com.mcarplay.app.usb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 台架进程内临时生成的 RSA Lockdown 身份。
 *
 * DevicePublicKey 使用 Lockdown 常见的 PKCS#1 RSA PUBLIC KEY PEM；TLS 使用同一密钥和
 * 一张自签名的 v1 X.509 证书。DiPlay/MacPlay 的 USB Lockdown 客户端不校验证书链，
 * 因此身份只需在本进程生命周期内一致。材料不写入磁盘。
 */
class RsaLockdownIdentity private constructor(
    val keyPair: KeyPair,
    val certificate: X509Certificate,
    val publicKeyPkcs1Pem: ByteArray,
) {
    companion object {
        fun generate(): RsaLockdownIdentity {
            val keyPair =
                KeyPairGenerator
                    .getInstance("RSA")
                    .apply { initialize(KEY_BITS) }
                    .generateKeyPair()
            val certificateDer = selfSignedCertificate(keyPair)
            val certificate =
                CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
            certificate.checkValidity()
            val publicKey = keyPair.public as RSAPublicKey
            return RsaLockdownIdentity(keyPair, certificate, pkcs1PublicKeyPem(publicKey))
        }

        private fun pkcs1PublicKeyPem(publicKey: RSAPublicKey): ByteArray {
            val pkcs1 =
                sequence(
                    integer(publicKey.modulus),
                    integer(publicKey.publicExponent),
                )
            val encoded =
                Base64
                    .getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII))
                    .encodeToString(pkcs1)
            return buildString {
                append("-----BEGIN RSA PUBLIC KEY-----\n")
                append(encoded)
                append("\n-----END RSA PUBLIC KEY-----\n")
            }.toByteArray(StandardCharsets.US_ASCII)
        }

        private fun selfSignedCertificate(keyPair: KeyPair): ByteArray {
            val algorithm = sequence(oid(SHA256_WITH_RSA), nullValue())
            val name =
                sequence(
                    set(
                        sequence(
                            oid(COMMON_NAME),
                            utf8("iPhone"),
                        ),
                    ),
                )
            val now = System.currentTimeMillis()
            val validity =
                sequence(
                    utcTime(Date(now - ONE_DAY_MILLIS)),
                    utcTime(Date(now + TEN_YEARS_MILLIS)),
                )
            val tbs =
                sequence(
                    integer(BigInteger.ONE),
                    algorithm,
                    name,
                    validity,
                    name,
                    keyPair.public.encoded,
                )
            val signature =
                Signature
                    .getInstance("SHA256withRSA")
                    .apply {
                        initSign(keyPair.private)
                        update(tbs)
                    }.sign()
            return sequence(tbs, algorithm, bitString(signature))
        }

        private fun utcTime(date: Date): ByteArray {
            val formatter =
                SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            return der(0x17, formatter.format(date).toByteArray(StandardCharsets.US_ASCII))
        }

        private fun oid(value: String): ByteArray {
            val parts = value.split('.').map(String::toInt)
            require(parts.size >= 2 && parts[0] in 0..2 && parts[1] in 0..39)
            val out = ByteArrayOutputStream()
            out.write(parts[0] * 40 + parts[1])
            for (part in parts.drop(2)) {
                require(part >= 0)
                val groups = ArrayDeque<Int>()
                var remaining = part
                do {
                    groups.addFirst(remaining and 0x7F)
                    remaining = remaining ushr 7
                } while (remaining != 0)
                for (index in groups.indices) {
                    val last = index == groups.lastIndex
                    out.write(groups[index] or if (last) 0 else 0x80)
                }
            }
            return der(0x06, out.toByteArray())
        }

        private fun integer(value: BigInteger): ByteArray {
            var bytes = value.toByteArray()
            if (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1].toInt() and 0x80 == 0) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            return der(0x02, bytes)
        }

        private fun utf8(value: String): ByteArray = der(0x0C, value.toByteArray(StandardCharsets.UTF_8))

        private fun nullValue(): ByteArray = der(0x05, byteArrayOf())

        private fun bitString(value: ByteArray): ByteArray = der(0x03, byteArrayOf(0) + value)

        private fun sequence(vararg values: ByteArray): ByteArray = der(0x30, values.reduce(ByteArray::plus))

        private fun set(vararg values: ByteArray): ByteArray = der(0x31, values.reduce(ByteArray::plus))

        private fun der(
            tag: Int,
            value: ByteArray,
        ): ByteArray {
            val length = derLength(value.size)
            return byteArrayOf(tag.toByte()) + length + value
        }

        private fun derLength(length: Int): ByteArray {
            require(length >= 0)
            if (length < 0x80) return byteArrayOf(length.toByte())
            val bytes =
                buildList {
                    var remaining = length
                    while (remaining != 0) {
                        add(0, (remaining and 0xFF).toByte())
                        remaining = remaining ushr 8
                    }
                }.toByteArray()
            require(bytes.size <= 0x7F)
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }

        private const val KEY_BITS = 2048
        private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val TEN_YEARS_MILLIS = 3650L * ONE_DAY_MILLIS
        private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
        private const val COMMON_NAME = "2.5.4.3"
    }
}
