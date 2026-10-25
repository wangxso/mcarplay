package com.mcarplay.app.carlife

import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects mcarplay as the CarLife HU to CarWith's seven loopback listeners.
 *
 * A fresh set of sockets is used for every attempt. Partial connections are
 * closed before the next attempt, so CarWith never sees a half-formed session.
 */
class CarLifeChannelConnector(
    private val connect: (Int) -> Socket,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sockets = EnumMap<CarLifeChannel, Socket>(CarLifeChannel::class.java)

    fun connectAll(
        timeoutMs: Long,
        retryDelayMs: Long = 400L,
    ): Map<CarLifeChannel, Socket> {
        val deadline = nowMs() + timeoutMs
        var attempt = 0
        var lastError = "listeners not ready"
        while (!closed.get() && nowMs() < deadline) {
            attempt++
            val candidate = EnumMap<CarLifeChannel, Socket>(CarLifeChannel::class.java)
            try {
                CarLifeChannel.entries.forEach { channel ->
                    if (closed.get()) throw IOException("cancelled")
                    candidate[channel] = connect(channel.port)
                }
                synchronized(sockets) {
                    sockets.putAll(candidate)
                }
                return candidate
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
                candidate.values.forEach { runCatching { it.close() } }
                log("CARLIFE: 连接尝试 $attempt 未完成：$lastError")
                val remaining = deadline - nowMs()
                if (remaining > 0) sleep(minOf(retryDelayMs, remaining))
            }
        }
        throw IOException(
            if (closed.get()) "CarLife connection cancelled" else "CarLife listener wait deadline: $lastError",
        )
    }

    fun socket(channel: CarLifeChannel): Socket? =
        synchronized(sockets) {
            sockets[channel]?.takeUnless { it.isClosed }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(sockets) {
            sockets.values.forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }
}

enum class CarLifeChannel(
    val port: Int,
) {
    COMMAND(7240),
    VIDEO(8240),
    MEDIA(9240),
    TTS(9241),
    VOICE(9242),
    TOUCH(9340),
    DATA(9440),
    ;

    companion object {
        fun fromPort(port: Int): CarLifeChannel? = entries.firstOrNull { it.port == port }
    }
}
