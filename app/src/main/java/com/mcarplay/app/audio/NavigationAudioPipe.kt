package com.mcarplay.app.audio

import android.os.Binder
import android.os.ParcelFileDescriptor
import com.mcarplay.app.shizuku.CarInputClient
import com.mcarplay.app.shizuku.ICarInput
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Buffers a few frames so playback of media never waits for the map audio lane. */
internal class NavigationAudioPipe private constructor(
    private val service: ICarInput,
    private val owner: Binder,
    private val descriptor: ParcelFileDescriptor,
    private val bytesPerFrame: Int,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val frames = ArrayBlockingQueue<ByteArray>(4)

    init {
        Thread(::read, "navigation-pcm").apply {
            isDaemon = true
            start()
        }
    }

    private fun read() {
        try {
            DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(descriptor)).use { input ->
                while (!closed.get()) {
                    val frame = ByteArray(bytesPerFrame)
                    input.readFully(frame)
                    if (!frames.offer(frame)) {
                        frames.poll()
                        frames.offer(frame)
                    }
                }
            }
        } catch (_: IOException) {
            // The capture has ended or the session was disconnected.
        } finally {
            close()
        }
    }

    val isClosed: Boolean get() = closed.get()

    fun poll(): ByteArray? = frames.poll()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { descriptor.close() }
        runCatching { service.closeNavigationAudio(owner) }
        frames.clear()
    }

    companion object {
        fun open(
            sampleRate: Int,
            bytesPerFrame: Int,
            mapUids: IntArray,
            log: (String) -> Unit,
        ): NavigationAudioPipe? {
            val service = CarInputClient.service
            val owner = Binder()
            val descriptor = runCatching { service?.openNavigationAudio(sampleRate, owner, mapUids) }.getOrNull()
            if (service == null || descriptor == null) {
                log("AUDIO: 地图导航专用采集未就绪，继续转发普通媒体")
                return null
            }
            log("AUDIO: 已启动自绘 UI 地图独立音频采集")
            return NavigationAudioPipe(service, owner, descriptor, bytesPerFrame)
        }
    }
}
