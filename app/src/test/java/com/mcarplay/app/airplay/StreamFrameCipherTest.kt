package com.mcarplay.app.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流帧加密的两种形态（catplay `ScreenFrameCodec` 的三分类）。
 *
 * 关键回归点：
 *  1. 根对象必须落在字节 8 —— CarPlay 210.81 老车机不读 trailer 的 topObject，
 *     直接按偏移 8 取根（catplay 上游 9ad3a7e 的修复原因）；
 *  2. AES-128-CTR 的 key/iv 派生必须与 catplay `derive_aes_stream_keys` 逐字节一致；
 *     密钥流**跨帧连续**（每帧新建实例会让第二帧起全部乱码）；
 *  3. 未配对会话的会话密钥是全零（`et=NONE` 的约定，不是随机值）。
 */
class StreamFrameCipherTest {
    @Test
    fun encodedPlistRootObjectStartsAtByteEight() {
        // 对象数 > 255 才用多字节偏移表（真车 SETUP 体就有几百个对象）
        val values = linkedMapOf<String, Any?>()
        repeat(150) { values["field%03d".format(it)] = it.toLong() }
        val data = BinaryPlist.encode(values)

        assertEquals("bplist00", String(data, 0, 8, Charsets.US_ASCII))
        // 根是字典（0xD0 | count）；第一个字节就是根标记
        assertEquals(0xD0, data[8].toInt() and 0xF0)

        val trailer = data.size - 32
        val width = data[trailer + 6].toInt() and 0xFF
        assertTrue("多对象应使用多字节偏移表（width=$width）", width > 1)
        val count = readUint(data, trailer + 8, 8).toInt()
        val root = readUint(data, trailer + 16, 8).toInt()
        val table = readUint(data, trailer + 24, 8).toInt()
        val rootOffset = readUint(data, table + root * width, width).toInt()
        assertEquals("CarPlay 210.81 直接读字节 8", 8, rootOffset)

        // 重排后仍必须能被标准解析器读回（偏移表修正正确）
        val decoded = BinaryPlist.parse(data) as Map<*, *>
        assertEquals(150, decoded.size)
        assertEquals(149L, decoded["field149"])
    }

    @Test
    fun smallPlistKeepsRootAtByteEightToo() {
        val data = BinaryPlist.encode(linkedMapOf("a" to 1L, "b" to "x"))
        assertEquals(0xD0, data[8].toInt() and 0xF0)
        assertEquals(1L, (BinaryPlist.parse(data) as Map<*, *>)["a"])
    }

    @Test
    fun aesStreamKeysMatchCatplayDerivation() {
        val sessionKey = ByteArray(16) { (it + 1).toByte() }
        val connectionId = 0x0102_0304_0506_0708L
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(sessionKey, connectionId)

        fun expected(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    "72623859790382856".toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)

        assertArrayEquals(expected("AirPlayStreamKey"), key)
        assertArrayEquals(expected("AirPlayStreamIV"), iv)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
    }

    @Test
    fun unpairedSessionUsesZeroSessionKey() {
        assertArrayEquals(ByteArray(16), AirPlayStreamKeys.ZERO_SESSION_KEY)
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, 1L)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
        // 零密钥派生出的流密钥不应也是全零（否则加解密退化成明文）
        assertNotEquals(ByteArray(16).toList(), key.toList())
    }

    @Test
    fun aesCtrKeystreamIsContinuousAcrossFrames() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val iv = ByteArray(16) { (it * 5).toByte() }
        val frameA = ByteArray(152) { it.toByte() }
        val frameB = ByteArray(152) { (it + 7).toByte() }

        // 发送端：同一实例连续两帧
        val sender = AirPlayCrypto.Aes128CtrStream(key, iv)
        val wireA = frameA.copyOf().also(sender::applyKeystream)
        val wireB = frameB.copyOf().also(sender::applyKeystream)

        // 接收端：同一实例连续解密
        val receiver = AirPlayCrypto.Aes128CtrStream(key, iv)
        val plainA = wireA.copyOf().also(receiver::applyKeystream)
        val plainB = wireB.copyOf().also(receiver::applyKeystream)

        assertArrayEquals(frameA, plainA)
        assertArrayEquals(frameB, plainB)
        // 若错误地每帧新建实例（密钥流重置），wireB 会等于 wireA 的前缀
        assertNotEquals(wireA.take(16), wireB.take(16))
    }

    @Test
    fun chachaStreamKeyMatchesHkdfDerivation() {
        val secret = ByteArray(32) { it.toByte() }
        val key = AirPlayStreamKeys.chachaStreamKey(secret, 42L)
        assertArrayEquals(
            AirPlayCrypto.hkdfSha512(
                secret,
                "DataStream-Salt42".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            ),
            key,
        )
        assertEquals(32, key.size)
    }

    private fun readUint(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xFF) }
        return value
    }
}
