package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeAudioParserTest {
    @Test
    fun `parses media init sample rate channels and format`() {
        val parser = CarLifeAudioParser()
        val frame =
            mediaFrame(
                CarLifeProtocol.MEDIA_INIT,
                CarLifeProtocol.pbInt(1, 44_100) + CarLifeProtocol.pbInt(2, 2) + CarLifeProtocol.pbInt(3, 16),
            )

        val event = parser.parse(CarLifeChannel.MEDIA, frame)

        assertEquals(CarLifeAudioEvent.Init(44_100, 2, 16), event)
    }

    @Test
    fun `rejects media data before init`() {
        val parser = CarLifeAudioParser()

        assertNull(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, byteArrayOf(1, 2, 3, 4))))
    }

    @Test
    fun `parses media data after init`() {
        val parser = CarLifeAudioParser()
        parser.parse(
            CarLifeChannel.MEDIA,
            mediaFrame(
                CarLifeProtocol.MEDIA_INIT,
                CarLifeProtocol.pbInt(1, 44_100) + CarLifeProtocol.pbInt(2, 2) + CarLifeProtocol.pbInt(3, 16),
            ),
        )

        val pcm = byteArrayOf(1, 2, 3, 4)
        val event = parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, pcm))

        assertTrue(event is CarLifeAudioEvent.Pcm)
        assertEquals(pcm, (event as CarLifeAudioEvent.Pcm).littleEndian)
    }

    @Test
    fun `parses malformed init without failing session`() {
        val parser = CarLifeAudioParser()

        assertNull(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_INIT, byteArrayOf(0x0f))))
    }

    @Test
    fun `normalizes tts and voice to mono`() {
        val parser = CarLifeAudioParser()
        parser.parse(
            CarLifeChannel.TTS,
            mediaFrame(
                CarLifeProtocol.NAVI_TTS_INIT,
                CarLifeProtocol.pbInt(1, 16_000) + CarLifeProtocol.pbInt(2, 2) + CarLifeProtocol.pbInt(3, 16),
            ),
        )

        val event = parser.parse(CarLifeChannel.TTS, mediaFrame(CarLifeProtocol.NAVI_TTS_DATA, byteArrayOf(1, 2, 3, 4)))

        assertTrue(event is CarLifeAudioEvent.Pcm)
    }

    @Test
    fun `first int field skips length delimited fields`() {
        val payload = CarLifeProtocol.pbString(1, "skip") + CarLifeProtocol.pbInt(2, 1)

        assertEquals(1, CarLifeAudioParser.firstIntField(payload, 2))
    }

    @Test
    fun `pcm includes source format for resampling`() {
        val parser = CarLifeAudioParser()
        parser.parse(CarLifeChannel.MEDIA, initFrame(44_100, 2, 16))
        val event = parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, ByteArray(8))) as CarLifeAudioEvent.Pcm
        assertEquals(44_100, event.sampleRate)
        assertEquals(2, event.channels)
        assertNull(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, ByteArray(3))))
    }

    @Test
    fun `truncated replacement init clears old format`() {
        val parser = CarLifeAudioParser()
        parser.parse(CarLifeChannel.MEDIA, initFrame(44_100, 2, 16))
        assertNull(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_INIT, byteArrayOf(8, 0x80.toByte()))))
        assertNull(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, ByteArray(8))))
    }

    @Test
    fun `eight bit android encoding is not passed as sixteen bit pcm`() {
        val parser = CarLifeAudioParser()
        assertEquals(CarLifeAudioEvent.Init(44_100, 1, 8), parser.parse(CarLifeChannel.MEDIA, initFrame(44_100, 8, 3)))
        assertNull(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, ByteArray(8))))
    }

    @Test
    fun `pause retains format but stop requires new init`() {
        val parser = CarLifeAudioParser()
        parser.parse(CarLifeChannel.MEDIA, initFrame(44_100, 2, 16))
        parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_PAUSE, ByteArray(0)))
        assertTrue(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, ByteArray(8))) is CarLifeAudioEvent.Pcm)
        parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_STOP, ByteArray(0)))
        assertNull(parser.parse(CarLifeChannel.MEDIA, mediaFrame(CarLifeProtocol.MEDIA_DATA, ByteArray(8))))
    }

    private fun initFrame(
        rate: Int,
        channels: Int,
        bits: Int,
    ): CarLifeFrame =
        mediaFrame(
            CarLifeProtocol.MEDIA_INIT,
            CarLifeProtocol.pbInt(1, rate) + CarLifeProtocol.pbInt(2, channels) + CarLifeProtocol.pbInt(3, bits),
        )

    private fun mediaFrame(
        kind: Int,
        payload: ByteArray,
    ): CarLifeFrame =
        CarLifeFrame(
            channel = CarLifeProtocol.PORT_MEDIA,
            kind = kind,
            timestamp = 1,
            reserved = 0,
            payload = payload,
        )
}
