package com.mcarplay.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioDuckingMixerTest {
    private var now = 1_000_000_000L
    private val config = AudioDuckingConfig(duckDb = -12f, attackMs = 10, releaseMs = 20, holdMs = 50, mediaIdleMs = 100)

    /** 1kHz 采样率：1 帧 = 1ms，便于按毫秒核对包络。 */
    private fun mixer(custom: AudioDuckingConfig = config) = AudioDuckingMixer<String>(RATE, custom) { now }

    @Test
    fun mediaPassesThroughUnchangedWithoutPrompt() {
        val mixer = mixer()
        val music = constant(40, 10_000, -10_000)
        assertArrayEquals(music, mixer.media(music))
        assertEquals(1f, mixer.gain)
        assertFalse(mixer.ducking)
    }

    @Test
    fun promptWaitsForAttackThenMixesOverDuckedMusic() {
        val mixer = mixer()
        mixer.media(constant(5, 10_000, 10_000))
        mixer.prompt(TTS, constant(5, 1_000, 1_000))
        assertTrue(mixer.ducking)
        val left = left(mixer.media(constant(20, 10_000, 10_000)))
        val duckedMusic = (10_000 * config.duckGain).toInt()
        // 10ms attack = 10 帧：提示要等音乐淡到位才混入（浮点误差允许早一帧到位）
        val start = left.indexOfFirst { abs(it - (duckedMusic + 1_000)) <= 1 }
        assertTrue("prompt started at $start", start in 9..10)
        // attack 期间音乐单调下降、每帧变化不超过一个步长，无跳变
        for (index in 1 until start) {
            assertTrue(left[index] < left[index - 1])
            assertTrue(left[index - 1] - left[index] <= 800)
        }
        for (index in start until start + 5) assertEquals(duckedMusic + 1_000, left[index], 1)
        // 5 帧提示播完后只剩压低的音乐
        for (index in start + 5 until 20) assertEquals(duckedMusic, left[index], 1)
        assertEquals(config.duckGain, mixer.gain)
    }

    @Test
    fun musicHoldsThenRampsBackSmoothlyAfterPrompt() {
        val mixer = mixer()
        mixer.media(constant(1, 10_000, 10_000))
        mixer.prompt(TTS, constant(5, 500, 500))
        mixer.media(constant(20, 10_000, 10_000))
        // 保持期内仍然压低
        now += 30_000_000L
        assertTrue(mixer.ducking)
        mixer.media(constant(10, 10_000, 10_000))
        assertEquals(config.duckGain, mixer.gain)
        // 保持期结束：release 20ms 内逐帧回升到原音量
        now += 30_000_000L
        assertFalse(mixer.ducking)
        val left = left(mixer.media(constant(30, 10_000, 10_000)))
        for (index in 1 until 20) assertTrue(left[index] > left[index - 1])
        assertEquals(10_000, left[19], 400)
        assertEquals(10_000, left[29])
        assertEquals(1f, mixer.gain)
    }

    @Test
    fun promptPlaysAloneWhenMusicIsIdle() {
        val mixer = mixer()
        val prompt = constant(8, 1_234, -1_234)
        mixer.prompt(TTS, prompt)
        assertArrayEquals(prompt, mixer.drain())
        assertEquals(0, mixer.drain().size)
        assertEquals(config.duckGain, mixer.gain)
    }

    @Test
    fun promptWaitsForMusicWhileMusicIsActiveAndFlushesAfterStop() {
        val mixer = mixer()
        mixer.media(constant(4, 100, 100))
        mixer.prompt(TTS, constant(4, 700, 700))
        assertEquals(0, mixer.drain().size)
        mixer.mediaStopped()
        assertArrayEquals(constant(4, 700, 700), mixer.drain())
    }

    @Test
    fun promptIsReleasedWhenMusicStallsWithoutStop() {
        val mixer = mixer()
        mixer.media(constant(4, 100, 100))
        mixer.prompt(TTS, constant(4, 700, 700))
        now += 99_000_000L
        assertEquals(0, mixer.drain().size)
        now += 2_000_000L
        assertEquals(16, mixer.drain().size)
    }

    @Test
    fun musicResumingDuringHoldStartsDuckedWithoutBurst() {
        val mixer = mixer()
        mixer.prompt(TTS, constant(4, 700, 700))
        mixer.drain()
        now += 10_000_000L
        val left = left(mixer.media(constant(5, 10_000, 10_000)))
        assertEquals((10_000 * config.duckGain).toInt(), left[0], 1)
    }

    @Test
    fun musicResumingAfterHoldStartsAtFullVolume() {
        val mixer = mixer()
        mixer.prompt(TTS, constant(4, 700, 700))
        mixer.drain()
        now += 500_000_000L
        assertEquals(0, mixer.drain().size)
        assertArrayEquals(constant(3, 9_000, 9_000), mixer.media(constant(3, 9_000, 9_000)))
    }

    @Test
    fun concurrentPromptSourcesAreSummedNotQueuedBackToBack() {
        val mixer = mixer()
        mixer.prompt(TTS, constant(4, 1_000, 1_000))
        mixer.prompt(VOICE, constant(2, 300, -300))
        val out = mixer.drain()
        assertEquals(4 * 4, out.size)
        assertEquals(listOf(1_300, 1_300, 1_000, 1_000), left(out))
        assertEquals(listOf(700, 700, 1_000, 1_000), right(out))
    }

    @Test
    fun mixClampsInsteadOfWrapping() {
        val mixer = mixer(config.copy(duckDb = 0f))
        mixer.media(constant(1, 0, 0))
        mixer.prompt(TTS, constant(2, 30_000, -30_000))
        val out = mixer.media(constant(2, 30_000, -30_000))
        assertEquals(listOf(32_767, 32_767), left(out))
        assertEquals(listOf(-32_768, -32_768), right(out))
        assertEquals(4L, mixer.clippedSamples)
    }

    @Test
    fun zeroDuckDbMixesImmediatelyWithoutAttenuation() {
        val mixer = mixer(config.copy(duckDb = 0f))
        mixer.media(constant(1, 0, 0))
        mixer.prompt(TTS, constant(3, 100, 100))
        assertEquals(listOf(5_100, 5_100, 5_100), left(mixer.media(constant(3, 5_000, 5_000))))
    }

    @Test
    fun promptBufferDropsOldestFramesWhenFull() {
        val mixer = mixer(config.copy(maxPromptMs = 100))
        mixer.prompt(TTS, constant(100, 1, 1))
        mixer.prompt(TTS, constant(10, 2, 2))
        assertEquals(10L, mixer.droppedPromptFrames)
        val left = left(mixer.drain())
        assertEquals(100, left.size)
        assertEquals(1, left.first())
        assertEquals(2, left.last())
    }

    @Test
    fun mediaChunkBoundariesDoNotChangeEnvelope() {
        val music = constant(60, 8_000, -8_000)
        val prompt = constant(20, 600, 600)
        val whole = mixer()
        whole.media(constant(1, 0, 0))
        whole.prompt(TTS, prompt)
        val expected = whole.media(music)
        val chunked = mixer()
        chunked.media(constant(1, 0, 0))
        chunked.prompt(TTS, prompt)
        val actual =
            music
                .toList()
                .chunked(12)
                .flatMap { chunked.media(it.toByteArray()).toList() }
                .toByteArray()
        assertArrayEquals(expected, actual)
    }

    @Test
    fun configMapsDbToLinearGainAndRejectsOutOfRangeValues() {
        assertEquals(0.251f, AudioDuckingConfig(duckDb = -12f).duckGain, 0.001f)
        assertEquals(1f, AudioDuckingConfig(duckDb = 0f).duckGain)
        assertTrue(runCatching { AudioDuckingConfig(duckDb = 3f) }.isFailure)
        assertTrue(runCatching { AudioDuckingConfig(attackMs = -1) }.isFailure)
        assertTrue(runCatching { AudioDuckingConfig(mediaIdleMs = 0) }.isFailure)
    }

    @Test
    fun defaultConfigDucksWithinRequestedWindow() {
        val defaults = AudioDuckingConfig()
        assertTrue(defaults.duckDb in -20f..-12f)
        assertTrue(defaults.attackMs in 200..500)
        assertTrue(defaults.releaseMs in 200..500)
    }

    @Test
    fun resetClearsPromptsAndEnvelope() {
        val mixer = mixer()
        mixer.prompt(TTS, constant(4, 700, 700))
        mixer.drain()
        mixer.prompt(TTS, constant(4, 700, 700))
        mixer.reset()
        assertFalse(mixer.ducking)
        assertEquals(1f, mixer.gain)
        assertEquals(0, mixer.drain().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialStereoFrames() {
        mixer().media(ByteArray(3))
    }

    private fun assertEquals(
        expected: Int,
        actual: Int,
        tolerance: Int,
    ) {
        assertTrue("expected $expected±$tolerance but was $actual", abs(expected - actual) <= tolerance)
    }

    private companion object {
        const val RATE = 1_000
        const val TTS = "tts"
        const val VOICE = "voice"

        fun constant(
            frames: Int,
            left: Int,
            right: Int,
        ): ByteArray {
            val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) {
                buffer.putShort(left.toShort())
                buffer.putShort(right.toShort())
            }
            return buffer.array()
        }

        fun left(pcm: ByteArray): List<Int> = channel(pcm, 0)

        fun right(pcm: ByteArray): List<Int> = channel(pcm, 1)

        private fun channel(
            pcm: ByteArray,
            index: Int,
        ): List<Int> {
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until pcm.size / 4).map { buffer.getShort(it * 4 + index * 2).toInt() }
        }
    }
}
