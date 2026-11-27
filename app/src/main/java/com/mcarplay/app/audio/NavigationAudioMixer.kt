package com.mcarplay.app.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Runs on the media capture thread. The map's loopback sends PCM even while the map is silent. */
internal class NavigationAudioMixer(
    sampleRate: Int,
    config: AudioDuckingConfig,
    clockNanos: () -> Long = System::nanoTime,
) {
    // Keep the music fade short so its attack does not add much delay to a live prompt.
    private val mixer = AudioDuckingMixer<Unit>(sampleRate, config.copy(attackMs = 20, maxPromptMs = 500), clockNanos)

    val ducking: Boolean get() = mixer.ducking
    val duckDb: Float get() = mixer.config.duckDb

    fun mix(
        media: ByteArray,
        guidance: ByteArray?,
        duckDb: Float,
        musicAttenuationDb: Float = mixer.config.musicAttenuationDb,
    ) {
        mixer.setDuckDb(duckDb)
        mixer.setMusicAttenuationDb(musicAttenuationDb)
        if (guidance != null && enhanceGuidance(guidance)) mixer.prompt(Unit, guidance)
        mixer.media(media, media)
    }

    /** Lift quiet speech by up to 6 dB, reserving headroom when the original voice is already loud. */
    private fun enhanceGuidance(pcm: ByteArray): Boolean {
        var peak = 0
        for (offset in pcm.indices step 2) peak = maxOf(peak, abs(readSample(pcm, offset)))
        if (peak < MIN_GUIDANCE_PEAK) return false
        val gain = (GUIDANCE_PEAK_TARGET / peak).coerceIn(1f, 2f)
        if (gain > 1f) {
            for (offset in pcm.indices step 2) {
                val sample = (readSample(pcm, offset) * gain).roundToInt()
                pcm[offset] = sample.toByte()
                pcm[offset + 1] = (sample shr 8).toByte()
            }
        }
        return true
    }

    private companion object {
        const val GUIDANCE_PEAK_TARGET = 26_000f
        const val MIN_GUIDANCE_PEAK = 16

        fun readSample(
            pcm: ByteArray,
            offset: Int,
        ): Int = (pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xff)
    }
}
