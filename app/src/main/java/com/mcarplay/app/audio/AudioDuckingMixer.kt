package com.mcarplay.app.audio

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 导航/语音提示与音乐同时出现时的压低（ducking）参数。
 *
 * 默认值取自常见车载做法：提示开始前约 250ms 把音乐线性淡到 -15dB，提示结束后再等
 * [holdMs] 防止句间抖动，然后用 [releaseMs] 淡回原音量。所有时长都按样本计算，
 * 与 CarWith 送数据的节奏无关。
 */
data class AudioDuckingConfig(
    /** 提示期间音乐的目标增益（dB）；0 = 不压低，-60 ≈ 静音。 */
    val duckDb: Float = -15f,
    /** 音乐从原音量淡到 [duckDb] 的时长；提示在淡完之后才混入，避免首字被音乐盖住。 */
    val attackMs: Int = 250,
    /** 提示结束后音乐淡回原音量的时长。 */
    val releaseMs: Int = 400,
    /** 最后一段提示样本混入后，保持压低的时长（TTS 常按句分多段 INIT/DATA/END）。 */
    val holdMs: Int = 400,
    /** 这么久没有新的音乐 PCM 就视为音乐未在播放，提示直接单独发出。 */
    val mediaIdleMs: Int = 300,
    /** 每路提示最多缓存的时长；超出时丢最旧的样本。 */
    val maxPromptMs: Int = 8_000,
) {
    init {
        require(duckDb in MIN_DUCK_DB..0f) { "duckDb must be within $MIN_DUCK_DB..0 dB" }
        require(attackMs in 0..MAX_RAMP_MS && releaseMs in 0..MAX_RAMP_MS) { "ramp must be within 0..$MAX_RAMP_MS ms" }
        require(holdMs in 0..MAX_RAMP_MS) { "holdMs must be within 0..$MAX_RAMP_MS ms" }
        require(mediaIdleMs in 20..MAX_RAMP_MS) { "mediaIdleMs must be within 20..$MAX_RAMP_MS ms" }
        require(maxPromptMs in 100..60_000) { "maxPromptMs must be within 100..60000 ms" }
    }

    /** [duckDb] 对应的线性幅度增益。 */
    val duckGain: Float get() = 10.0.pow(duckDb / 20.0).toFloat()

    private companion object {
        const val MIN_DUCK_DB = -60f
        const val MAX_RAMP_MS = 5_000
    }
}

/**
 * 手机侧混音：把 CarWith 9240 音乐与 9241 导航 TTS / 9242 语音提示混成一路
 * 立体声 s16 LE PCM，再交给唯一一条已协商的 AirPlay 音频流（AAC-LC 或 PCM 档都在
 * 混音之后编码，降级逻辑不受影响）。
 *
 * 时钟：音乐在播放时由音乐驱动——每来一段音乐就输出等长的一段，从提示缓存里取同样
 * 帧数叠加；音乐静止（[AudioDuckingConfig.mediaIdleMs] 无数据或收到停止/暂停）时，
 * [drain] 直接把缓存的提示单独输出。这样两路不会在发送队列里首尾相接、把时间轴拉长。
 *
 * 增益包络逐样本线性变化：压低时按 attack 斜率下降，恢复时按 release 斜率上升，
 * 不会出现跳变爆音。提示只在音乐已经淡到目标增益后才开始混入（音乐静止时立即混入）。
 *
 * 只在单线程（[CarLifeAudioBridge] 的工作线程）里调用；所有输入都已是目标采样率的
 * 立体声 s16 LE。
 */
class AudioDuckingMixer<K : Any>(
    private val sampleRate: Int,
    config: AudioDuckingConfig = AudioDuckingConfig(),
    private val clockNanos: () -> Long = System::nanoTime,
) {
    /** 当前生效的参数；[setDuckDb] 可在会话中调整压低幅度。 */
    var config = config
        private set
    private var duckGain = config.duckGain
    private var attackStep = rampStep(sampleRate, config.attackMs, duckGain)
    private var releaseStep = rampStep(sampleRate, config.releaseMs, duckGain)
    private val holdNanos = config.holdMs * NANOS_PER_MILLI
    private val mediaIdleNanos = config.mediaIdleMs * NANOS_PER_MILLI
    private val promptCapacityFrames = (sampleRate.toLong() * config.maxPromptMs / 1_000L).toInt()

    private val prompts = LinkedHashMap<K, StereoFifo>()
    private var lastMediaAt = NEVER
    private var lastPromptAt = NEVER

    /** 提示已开始混入：一直混到缓存清空，中途调整压低幅度也不会把提示截成两段。 */
    private var promptFlowing = false

    /** 当前施加在音乐上的线性增益（1 = 原音量）。 */
    var gain = 1f
        private set

    /** 因提示缓存溢出而丢掉的样本帧数（诊断用）。 */
    var droppedPromptFrames = 0L
        private set

    /** 写满后被限幅的样本数（诊断用）。 */
    var clippedSamples = 0L
        private set

    /** 是否处于「应当压低」状态：有待播提示，或最近一段提示刚播完仍在保持期。 */
    val ducking: Boolean
        get() = hasPromptBuffered() || withinHold(clockNanos())

    /**
     * 调整提示期间的音乐增益（来自「车机设置 → 显示」）。若正处于压低状态，增益按
     * attack/release 斜率平滑移到新目标，不会跳变。
     */
    fun setDuckDb(duckDb: Float) {
        if (duckDb == config.duckDb) return
        config = config.copy(duckDb = duckDb)
        duckGain = config.duckGain
        attackStep = rampStep(sampleRate, config.attackMs, duckGain)
        releaseStep = rampStep(sampleRate, config.releaseMs, duckGain)
    }

    /** 一段导航/语音提示 PCM（立体声 s16 LE）进入缓存；由 [media] 或 [drain] 输出。 */
    fun prompt(
        source: K,
        pcm: ByteArray,
    ) {
        if (pcm.isEmpty()) return
        require(pcm.size % FRAME_BYTES == 0) { "Incomplete stereo PCM frame" }
        val fifo = prompts.getOrPut(source) { StereoFifo(promptCapacityFrames) }
        droppedPromptFrames += fifo.write(pcm)
    }

    /**
     * 一段音乐 PCM：返回同样帧数的混音结果（音乐 × 包络 + 已就绪的提示）。
     */
    fun media(pcm: ByteArray): ByteArray {
        require(pcm.size % FRAME_BYTES == 0) { "Incomplete stereo PCM frame" }
        val now = clockNanos()
        val target = if (hasPromptBuffered() || withinHold(now)) duckGain else 1f
        // 音乐刚从静止恢复：此前听不到音乐，增益可直接落到目标值，不必再淡入淡出。
        if (!mediaActive(now)) gain = target
        lastMediaAt = now
        val frames = pcm.size / FRAME_BYTES
        val output = ByteArray(pcm.size)
        var mixedPrompt = false
        for (frame in 0 until frames) {
            gain =
                when {
                    gain > target -> maxOf(target, gain - attackStep)
                    gain < target -> minOf(target, gain + releaseStep)
                    else -> gain
                }
            val offset = frame * FRAME_BYTES
            var left = readSample(pcm, offset) * gain
            var right = readSample(pcm, offset + 2) * gain
            // 等音乐淡到位后才叠加提示，保证提示的首个音节不被音乐盖住。
            if (promptFlowing || gain <= duckGain) {
                promptFlowing = true
                for (fifo in prompts.values) {
                    if (fifo.isEmpty) continue
                    left += fifo.peekLeft()
                    right += fifo.peekRight()
                    fifo.skipFrame()
                    mixedPrompt = true
                }
            }
            writeSample(output, offset, left)
            writeSample(output, offset + 2, right)
        }
        if (mixedPrompt) lastPromptAt = now
        if (!hasPromptBuffered()) promptFlowing = false
        return output
    }

    /** 收到音乐停止/暂停：后续提示不再等音乐，下一次 [drain] 即单独输出。 */
    fun mediaStopped() {
        lastMediaAt = NEVER
    }

    /**
     * 音乐未在播放时，把所有缓存的提示叠加后一次性输出；音乐在播放时返回空数组
     * （提示随下一段音乐混入）。
     */
    fun drain(): ByteArray {
        val now = clockNanos()
        if (mediaActive(now)) return EMPTY
        if (!hasPromptBuffered()) {
            if (!withinHold(now)) gain = 1f
            return EMPTY
        }
        val frames = prompts.values.maxOf { it.size }
        val output = ByteArray(frames * FRAME_BYTES)
        val mix = FloatArray(frames * 2)
        for (fifo in prompts.values) {
            var frame = 0
            while (!fifo.isEmpty) {
                mix[frame * 2] += fifo.peekLeft().toFloat()
                mix[frame * 2 + 1] += fifo.peekRight().toFloat()
                fifo.skipFrame()
                frame++
            }
        }
        for (index in mix.indices) writeSample(output, index * 2, mix[index])
        gain = duckGain
        lastPromptAt = now
        promptFlowing = false
        return output
    }

    /** 清空提示缓存与包络（会话结束时）。 */
    fun reset() {
        prompts.clear()
        lastMediaAt = NEVER
        lastPromptAt = NEVER
        promptFlowing = false
        gain = 1f
    }

    private fun hasPromptBuffered(): Boolean = prompts.values.any { !it.isEmpty }

    private fun withinHold(now: Long): Boolean = lastPromptAt != NEVER && now - lastPromptAt < holdNanos

    private fun mediaActive(now: Long): Boolean = lastMediaAt != NEVER && now - lastMediaAt < mediaIdleNanos

    private fun writeSample(
        output: ByteArray,
        offset: Int,
        value: Float,
    ) {
        val rounded = value.roundToInt()
        val clamped = rounded.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        if (clamped != rounded) clippedSamples++
        output[offset] = (clamped and 0xFF).toByte()
        output[offset + 1] = ((clamped shr 8) and 0xFF).toByte()
    }

    /** 定长立体声环形缓存；写满时丢最旧的帧。 */
    private class StereoFifo(
        private val capacity: Int,
    ) {
        private val left = ShortArray(capacity)
        private val right = ShortArray(capacity)
        private var head = 0
        var size = 0
            private set

        val isEmpty: Boolean get() = size == 0

        /** 写入并返回因溢出丢掉的帧数。 */
        fun write(pcm: ByteArray): Long {
            var dropped = 0L
            var offset = 0
            while (offset < pcm.size) {
                if (size == capacity) {
                    skipFrame()
                    dropped++
                }
                val tail = (head + size) % capacity
                left[tail] = readSample(pcm, offset).toShort()
                right[tail] = readSample(pcm, offset + 2).toShort()
                size++
                offset += FRAME_BYTES
            }
            return dropped
        }

        fun peekLeft(): Int = left[head].toInt()

        fun peekRight(): Int = right[head].toInt()

        fun skipFrame() {
            head = (head + 1) % capacity
            size--
        }
    }

    private companion object {
        const val FRAME_BYTES = AudioSender.CHANNELS * 2
        const val NANOS_PER_MILLI = 1_000_000L
        const val NEVER = Long.MIN_VALUE
        const val MIN_RAMP_SPAN = 0.01f
        val EMPTY = ByteArray(0)

        /**
         * 每样本帧的增益步长：[millis] 内走完 `1 → duckGain`（0 时长 = 一步到位）。
         * duckGain≈1（0dB）时按满量程计步长，保证会话中从压低改成 0dB 也能平滑回升。
         */
        fun rampStep(
            sampleRate: Int,
            millis: Int,
            duckGain: Float,
        ): Float {
            val frames = sampleRate.toLong() * millis / 1_000L
            val span = (1f - duckGain).takeIf { it > MIN_RAMP_SPAN } ?: 1f
            return if (frames <= 0L) 1f else span / frames
        }

        fun readSample(
            pcm: ByteArray,
            offset: Int,
        ): Int = (((pcm[offset + 1].toInt() and 0xFF) shl 8) or (pcm[offset].toInt() and 0xFF)).toShort().toInt()
    }
}
