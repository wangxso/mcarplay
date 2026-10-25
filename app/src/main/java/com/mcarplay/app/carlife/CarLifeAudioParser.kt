package com.mcarplay.app.carlife

/** Parsed CarLife audio control/data payload for one audio channel. */
sealed class CarLifeAudioEvent {
    data class Init(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    ) : CarLifeAudioEvent()

    data class Pcm(
        val littleEndian: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    ) : CarLifeAudioEvent()

    data class Stop(
        val kind: Int,
    ) : CarLifeAudioEvent()
}

/**
 * Parses the 12-byte media frames emitted by Xiaomi CarWith/CarLife.
 *
 * The installed CarLife component uses `[length][timestamp][kind]` media
 * headers, protobuf fields 1/2/3 for audio init, and little-endian PCM
 * payloads. These kind values are its `MSG_MEDIA_*`, `MSG_NAVI_TTS_*`, and
 * `MSG_VR_AUDIO_*` constants.
 */
class CarLifeAudioParser {
    private var sampleRate = 0
    private var channels = 0
    private var bitsPerSample = 0

    fun reset() {
        sampleRate = 0
        channels = 0
        bitsPerSample = 0
    }

    fun parse(
        channel: CarLifeChannel,
        frame: CarLifeFrame,
    ): CarLifeAudioEvent? {
        val event =
            when (frame.kind) {
                CarLifeProtocol.MEDIA_INIT,
                CarLifeProtocol.NAVI_TTS_INIT,
                CarLifeProtocol.VR_AUDIO_INIT,
                ->
                    runCatching { parseInit(frame.payload, channel) }.getOrElse {
                        reset()
                        null
                    }

                CarLifeProtocol.MEDIA_DATA -> pcm(frame.payload)
                CarLifeProtocol.NAVI_TTS_DATA, CarLifeProtocol.VR_AUDIO_DATA -> pcm(frame.payload)
                CarLifeProtocol.MEDIA_STOP,
                CarLifeProtocol.MEDIA_PAUSE,
                CarLifeProtocol.NAVI_TTS_END,
                CarLifeProtocol.VR_AUDIO_STOP,
                -> {
                    if (frame.kind != CarLifeProtocol.MEDIA_PAUSE) reset()
                    CarLifeAudioEvent.Stop(frame.kind)
                }

                else -> null
            }
        return event
    }

    private fun parseInit(
        payload: ByteArray,
        channel: CarLifeChannel,
    ): CarLifeAudioEvent.Init? {
        reset()
        val rate = firstIntField(payload, 1)
        val channelField = firstIntField(payload, 2)
        val formatField = firstIntField(payload, 3)
        if (rate == null || rate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        // Normalize Android channel masks and direct channel counts.
        val channelCount =
            when (channelField) {
                1, 4, 8 -> 1
                2, 12 -> 2
                else -> return null
            }
        val bits =
            when (formatField) {
                8, 3 -> 8
                16, 2 -> 16
                else -> return null
            }
        sampleRate = rate
        channels = if (channel == CarLifeChannel.MEDIA) channelCount else 1
        bitsPerSample = bits
        return CarLifeAudioEvent.Init(rate, channels, bits)
    }

    private fun pcm(payload: ByteArray): CarLifeAudioEvent.Pcm? =
        if (sampleRate != 0 && channels != 0 && bitsPerSample == 16 && payload.isNotEmpty() && payload.size % (channels * 2) == 0) {
            CarLifeAudioEvent.Pcm(payload, sampleRate, channels)
        } else {
            null
        }

    companion object {
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000

        fun firstIntField(
            payload: ByteArray,
            fieldNumber: Int,
        ): Int? {
            var offset = 0
            while (offset < payload.size) {
                val (tag, tagSize) = readVarint(payload, offset)
                offset += tagSize
                val field = (tag ushr 3).toInt()
                val wireType = (tag and 7).toInt()
                when (wireType) {
                    0 -> {
                        val (value, valueSize) = readVarint(payload, offset)
                        offset += valueSize
                        if (field == fieldNumber) return value.toInt()
                    }

                    2 -> {
                        val (length, lengthSize) = readVarint(payload, offset)
                        offset += lengthSize + length.toInt()
                    }

                    1 -> offset += 8
                    5 -> offset += 4
                    else -> return null
                }
                if (offset > payload.size) return null
            }
            return null
        }

        private fun readVarint(
            payload: ByteArray,
            start: Int,
        ): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var offset = start
            while (offset < payload.size && shift < 64) {
                val byte = payload[offset].toInt() and 0xFF
                value = value or ((byte and 0x7F).toLong() shl shift)
                offset++
                if (byte and 0x80 == 0) return value to (offset - start)
                shift += 7
            }
            throw IllegalArgumentException("Truncated CarLife audio protobuf")
        }
    }
}
