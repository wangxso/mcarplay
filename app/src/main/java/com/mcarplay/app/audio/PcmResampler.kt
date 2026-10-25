package com.mcarplay.app.audio

import java.io.ByteArrayOutputStream

/** Streaming s16 LE interpolation with a shared integer clock across input chunks. */
class PcmResampler(
    val sourceRate: Int,
    val sourceChannels: Int,
    val targetRate: Int,
) {
    private var inputFrames = 0L
    private var nextOutput = 0L
    private var previousLeft = 0
    private var previousRight = 0

    init {
        require(sourceRate in 8_000..192_000 && targetRate in 8_000..192_000)
        require(sourceChannels in 1..2)
    }

    fun convert(pcm: ByteArray): ByteArray {
        require(pcm.size % (sourceChannels * 2) == 0) { "Incomplete PCM sample frame" }
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < pcm.size) {
            val left = sample(pcm, offset)
            val right = if (sourceChannels == 2) sample(pcm, offset + 2) else left
            if (inputFrames == 0L) {
                previousLeft = left
                previousRight = right
            }
            val currentTime = inputFrames * targetRate
            while (nextOutput <= currentTime) {
                val fraction = if (inputFrames == 0L) 0L else nextOutput - (inputFrames - 1) * targetRate
                writeSample(output, previousLeft + ((left - previousLeft).toLong() * fraction / targetRate).toInt())
                writeSample(output, previousRight + ((right - previousRight).toLong() * fraction / targetRate).toInt())
                nextOutput += sourceRate
            }
            previousLeft = left
            previousRight = right
            inputFrames++
            offset += sourceChannels * 2
        }
        return output.toByteArray()
    }

    private fun sample(
        pcm: ByteArray,
        offset: Int,
    ): Int = (((pcm[offset + 1].toInt() and 0xFF) shl 8) or (pcm[offset].toInt() and 0xFF)).toShort().toInt()

    private fun writeSample(
        output: ByteArrayOutputStream,
        value: Int,
    ) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }
}
