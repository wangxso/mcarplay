package com.mcarplay.app.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制通道加密帧与参考接收端格式互操作（双向、分帧、分片送达）。 */
class ControlCipherTest {
    @Test
    fun phoneAndReceiverCiphersInteropBothDirections() {
        val phoneWriteKey = ByteArray(32) { it.toByte() }
        val phoneReadKey = ByteArray(32) { (it + 7).toByte() }
        val phone = ControlCipher(phoneReadKey, phoneWriteKey)
        val receiver = ControlCipher(phoneWriteKey, phoneReadKey) // 接收端视角：读我写、写我读

        val request =
            "SETUP rtsp://192.168.149.253:7000/ RTSP/1.0\r\nContent-Length: 4\r\n\r\nbody"
                .toByteArray()
        assertArrayEquals(request, receiver.decrypt(phone.encrypt(request)).data)

        val reply = "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
        assertArrayEquals(reply, phone.decrypt(receiver.encrypt(reply)).data)
    }

    @Test
    fun largePayloadIsChunkedIntoMultipleFrames() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 1 })
        val receiver = ControlCipher(ByteArray(32) { 1 }, ByteArray(32))
        val big = ByteArray(0x5000) { (it % 251).toByte() } // 超过单帧 1422 上限
        assertArrayEquals(big, receiver.decrypt(phone.encrypt(big)).data)
    }

    /**
     * 控制帧明文上限必须是 1422（1440 MTU - 2B AAD - 16B tag），与 catplay
     * `AirPlayCipherCodec` 一致。旧实现用 0x4000：超过 MTU 的帧会被严格车机判为
     * 协议违规（catplay 解码侧对 len > 1422 直接报 PayloadTooBig）。
     */
    @Test
    fun eachFrameStaysWithinCatplayMtu() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 3 })
        val receiver = ControlCipher(ByteArray(32) { 3 }, ByteArray(32))
        val big = ByteArray(5000) { (it % 127).toByte() }
        val wire = phone.encrypt(big)

        // 逐帧走线格式：2B 长度 + 明文 + 16B tag；任何一帧的明文长度不得超过 1422
        var offset = 0
        var frames = 0
        while (offset < wire.size) {
            val length = (wire[offset].toInt() and 0xFF) or ((wire[offset + 1].toInt() and 0xFF) shl 8)
            assertTrue("帧明文长度 $length 超过 1422（MTU 1440）", length <= 1422)
            offset += 2 + length + 16
            frames++
        }
        assertEquals("5000 字节至少拆成 4 帧", 4, frames)
        assertArrayEquals(big, receiver.decrypt(wire).data)
    }

    @Test
    fun partialFramesDeliveredInPieces() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 2 })
        val receiver = ControlCipher(ByteArray(32) { 2 }, ByteArray(32))
        val message = "RTSP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val encrypted = receiver.encrypt(message + message)

        val half = encrypted.size / 2
        val first = phone.decrypt(encrypted.copyOfRange(0, half))
        val second = phone.decrypt(first.rest + encrypted.copyOfRange(half, encrypted.size))
        assertArrayEquals(message + message, first.data + second.data)
    }
}
