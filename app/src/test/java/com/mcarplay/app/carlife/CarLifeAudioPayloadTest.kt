package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CarLifeAudioPayloadTest {
    private val cipher = CarLifePayloadCipher(ByteArray(16) { (it + 1).toByte() })

    @Test
    fun xiaomiMixMediaPcmIsPlainEvenWhenLengthIsAnAesMultiple() {
        val pcm = ByteArray(4096) { (it * 13).toByte() }
        assertArrayEquals(
            pcm,
            CarLifeAudioPayload.decode(frame(CarLifeProtocol.MEDIA_DATA, pcm), cipher, CarLifeAudioPayload.Mode.XIAOMI_MIX),
        )
    }

    @Test
    fun xiaomiInitStillRequiresSessionDecryption() {
        val init = CarLifeProtocol.pbInt(1, 44_100) + CarLifeProtocol.pbInt(2, 2) + CarLifeProtocol.pbInt(3, 16)
        assertArrayEquals(
            init,
            CarLifeAudioPayload.decode(
                frame(CarLifeProtocol.MEDIA_INIT, cipher.encrypt(init)),
                cipher,
                CarLifeAudioPayload.Mode.XIAOMI_MIX,
            ),
        )
    }

    @Test(expected = Exception::class)
    fun corruptedXiaomiInitDoesNotFallBackToPlaintext() {
        CarLifeAudioPayload.decode(frame(CarLifeProtocol.MEDIA_INIT, byteArrayOf(1, 2, 3)), cipher, CarLifeAudioPayload.Mode.XIAOMI_MIX)
    }

    @Test
    fun otherSdkPcmUsesEncryption() {
        val pcm = ByteArray(32) { it.toByte() }
        assertArrayEquals(
            pcm,
            CarLifeAudioPayload.decode(frame(CarLifeProtocol.MEDIA_DATA, cipher.encrypt(pcm)), cipher, CarLifeAudioPayload.Mode.ENCRYPTED),
        )
    }

    @Test
    fun xiaomiTtsDoesNotUseMediaPlaintextRule() {
        val pcm = ByteArray(32) { it.toByte() }
        val tts = frame(CarLifeProtocol.NAVI_TTS_DATA, cipher.encrypt(pcm)).copy(channel = CarLifeProtocol.PORT_TTS)
        assertArrayEquals(pcm, CarLifeAudioPayload.decode(tts, cipher, CarLifeAudioPayload.Mode.XIAOMI_MIX))
    }

    private fun frame(
        kind: Int,
        payload: ByteArray,
    ) = CarLifeFrame(CarLifeProtocol.PORT_MEDIA, kind, 1, 0, payload)
}
