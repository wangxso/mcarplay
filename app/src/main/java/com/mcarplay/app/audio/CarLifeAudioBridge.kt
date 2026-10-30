package com.mcarplay.app.audio

import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges CarLife's native PCM into an already-negotiated CarPlay audio
 * stream. The queue decouples CarLife's socket thread from RTP pacing.
 */
class CarLifeAudioBridge(
    private val sender: AudioSender,
    private val targetSampleRate: Int,
    private val ducking: AudioDuckingConfig = AudioDuckingConfig(),
    private val log: (String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<Pair<CarLifeChannel, CarLifeAudioEvent>>(QUEUE_LIMIT)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var accepted = 0L
    private var dropped = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        sender.startExternalSource()
        val worker =
            Thread(
                {
                    try {
                        while (running.get()) {
                            val next = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                            if (next == null) {
                                // 音乐停发但没有 STOP 时，靠空闲轮询把等待中的提示放出去。
                                flushPrompts()
                                continue
                            }
                            route(next.first, next.second)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        mixer.reset()
                        sender.stopExternalSource()
                    }
                },
                "carlife-audio-bridge",
            )
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    /** 会话中调整提示期间的音乐压低幅度；由工作线程在下一次处理时应用。 */
    fun setDuckDb(duckDb: Float) {
        pendingDuckDb.set(duckDb)
    }

    fun submit(
        channel: CarLifeChannel,
        event: CarLifeAudioEvent,
    ) {
        if (!running.get()) return
        if (!queue.offer(channel to event)) {
            queue.poll()
            dropped++
            queue.offer(channel to event)
        }
    }

    private val converters = mutableMapOf<CarLifeChannel, PcmResampler>()
    private val mixer = AudioDuckingMixer<CarLifeChannel>(targetSampleRate, ducking)
    private var duckingLogged = false

    private val pendingDuckDb = AtomicReference<Float?>()

    /**
     * 9240 音乐直接混音输出；9241 导航 TTS、9242 语音提示进入提示缓存，由 [AudioDuckingMixer]
     * 在音乐淡到目标增益后叠加，或在音乐静止时单独输出。旧实现在提示后 500ms 内整段丢弃
     * 音乐 PCM，听感是「导航一来音乐就断」，且提示结束后的音乐片段被截掉。
     */
    private fun route(
        channel: CarLifeChannel,
        event: CarLifeAudioEvent,
    ) {
        applyPendingDuckDb()
        if (event !is CarLifeAudioEvent.Pcm) {
            converters.remove(channel)
            if (event is CarLifeAudioEvent.Stop && channel == CarLifeChannel.MEDIA) mixer.mediaStopped()
            flushPrompts()
            return
        }
        val converter =
            converters[channel]?.takeIf { it.sourceRate == event.sampleRate && it.sourceChannels == event.channels }
                ?: PcmResampler(event.sampleRate, event.channels, targetSampleRate).also {
                    converters[channel] = it
                    log("AUDIO: ${channel.name} PCM ${event.sampleRate}Hz/${event.channels}ch → ${targetSampleRate}Hz/2ch")
                }
        val pcm = converter.convert(event.littleEndian)
        if (pcm.isEmpty()) return
        if (channel == CarLifeChannel.MEDIA) {
            emit(mixer.media(pcm))
            logDuckingChange()
        } else {
            mixer.prompt(channel, pcm)
            flushPrompts()
        }
        accepted++
        if (accepted == 1L || accepted % LOG_EVERY == 0L) {
            log(
                "AUDIO: CarWith 原生 PCM 接入 #$accepted ${pcm.size}B nonzero=${pcm.any { it != 0.toByte() }} dropped=$dropped " +
                    "promptDropped=${mixer.droppedPromptFrames} clipped=${mixer.clippedSamples}",
            )
        }
    }

    /** 音乐未在播放时把缓存的提示单独发出；同时记录压低状态的切换。 */
    private fun flushPrompts() {
        applyPendingDuckDb()
        emit(mixer.drain())
        logDuckingChange()
    }

    private fun applyPendingDuckDb() {
        val duckDb = pendingDuckDb.getAndSet(null) ?: return
        if (duckDb == mixer.config.duckDb) return
        mixer.setDuckDb(duckDb)
        log("AUDIO: 导航时音乐音量改为 ${duckDb}dB（会话中生效）")
    }

    private fun logDuckingChange() {
        val active = mixer.ducking
        if (active == duckingLogged) return
        duckingLogged = active
        log(
            if (active) {
                "AUDIO: 提示音开始，音乐压低到 ${mixer.config.duckDb}dB（淡出 ${ducking.attackMs}ms）"
            } else {
                "AUDIO: 提示音结束，音乐淡回原音量（${ducking.releaseMs}ms）"
            },
        )
    }

    private fun emit(pcm: ByteArray) {
        if (pcm.isNotEmpty()) sender.submitExternalPcm(pcm)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private companion object {
        const val QUEUE_LIMIT = 64
        const val POLL_MILLIS = 20L
        const val LOG_EVERY = 2_000L
    }
}
