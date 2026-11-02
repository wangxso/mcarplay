package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthSetupResponseTest {
    private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    @Test
    fun describesKeyCertificateAndSignature() {
        val response = ByteArray(32) + be32(5) + ByteArray(5) + be32(64) + ByteArray(64)
        assertEquals("公钥=32B 证书=5B 签名=64B", describeAuthSetupResponse(response))
    }

    @Test
    fun rejectsTruncatedOrInconsistentResponses() {
        assertEquals("（应答过短）", describeAuthSetupResponse(ByteArray(10)))
        assertEquals("（证书长度非法）", describeAuthSetupResponse(ByteArray(32) + be32(1000) + ByteArray(4)))
        assertEquals("公钥=32B 证书=0B 签名=长度不符", describeAuthSetupResponse(ByteArray(32) + be32(0) + be32(64) + ByteArray(3)))
    }
}
