package com.mcarplay.app.audio

import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeChannel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges CarLife's native PCM into an already-negotiated CarPlay audio
 * stream. The queue decouples CarLife's socket thread from RTP pacing.
 */
class CarLifeAudioBridge(
    private val sender: AudioSender,
    private val targetSampleRate: Int,
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
                            val (channel, event) = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
                            route(channel, event)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        sender.stopExternalSource()
                    }
                },
                "carlife-audio-bridge",
            )
        worker.isDaemon = true
        thread = worker
        worker.start()
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
    private var promptChannel: CarLifeChannel? = null
    private var promptAt = 0L

    private fun route(
        channel: CarLifeChannel,
        event: CarLifeAudioEvent,
    ) {
        if (event !is CarLifeAudioEvent.Pcm) {
            converters.remove(channel)
            if (event is CarLifeAudioEvent.Stop && promptChannel == channel) promptChannel = null
            return
        }
        val now = System.nanoTime()
        if (channel == CarLifeChannel.MEDIA && promptChannel != null && now - promptAt < PROMPT_HOLD_NANOS) {
            converters.remove(channel)
            return
        }
        if (channel != CarLifeChannel.MEDIA) {
            promptChannel = channel
            promptAt = now
        }
        val converter =
            converters[channel]?.takeIf { it.sourceRate == event.sampleRate && it.sourceChannels == event.channels }
                ?: PcmResampler(event.sampleRate, event.channels, targetSampleRate).also {
                    converters[channel] = it
                    log("AUDIO: ${channel.name} PCM ${event.sampleRate}Hz/${event.channels}ch → ${targetSampleRate}Hz/2ch")
                }
        val pcm = converter.convert(event.littleEndian)
        if (pcm.isEmpty()) return
        sender.submitExternalPcm(pcm)
        accepted++
        if (accepted == 1L || accepted % LOG_EVERY == 0L) {
            log("AUDIO: CarWith 原生 PCM 接入 #$accepted ${pcm.size}B nonzero=${pcm.any { it != 0.toByte() }} dropped=$dropped")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private companion object {
        const val QUEUE_LIMIT = 64
        const val POLL_MILLIS = 100L
        const val LOG_EVERY = 2_000L
        const val PROMPT_HOLD_NANOS = 500_000_000L
    }
}
