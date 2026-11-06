package com.mcarplay.app.carlife

import com.mcarplay.app.diagnostics.DiagnosticExportPolicy
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
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
    enum class Failure { NONE, REFUSED, TIMEOUT, PERMISSION, IO, OTHER, CANCELLED }

    data class Progress(
        val attempts: Int = 0,
        val openedThisAttempt: List<Int> = emptyList(),
        val pendingPort: Int? = null,
        val lastFailedPort: Int? = null,
        val lastFailure: Failure = Failure.NONE,
        val allConnectedOnce: Boolean = false,
        val timedOut: Boolean = false,
    )

    private val closed = AtomicBoolean(false)
    private val sockets = EnumMap<CarLifeChannel, Socket>(CarLifeChannel::class.java)

    @Volatile
    private var progress = Progress()

    @Volatile
    private var startedAtMs: Long? = null

    @Volatile
    private var finishedAtMs: Long? = null

    /** 在所有端口就绪前触发，供调用方只在确有必要时请求 CarWith 入口。 */
    @Volatile
    var onAttemptFailed: ((attempt: Int, error: Exception) -> Unit)? = null

    /** 当前连接的只读进度；用于把超时归因到入口阶段，不额外探测端口。 */
    fun progressSnapshot(): Progress = progress

    /** 观察现有连接尝试；不额外探测端口，避免第二条连接重置 CarLife 会话。 */
    fun diagnosticSummary(): String {
        val current = progress
        val elapsed = startedAtMs?.let { ((finishedAtMs ?: nowMs()) - it).coerceAtLeast(0) } ?: 0
        return "attempts=${current.attempts} waitMs=$elapsed pendingPort=${current.pendingPort?.takeUnless { closed.get() } ?: 0} " +
            "lastFailedPort=${current.lastFailedPort ?: 0} lastFailure=${current.lastFailure} " +
            "openedThisAttempt=${current.openedThisAttempt.joinToString(",").ifEmpty { "none" }} " +
            "allConnectedOnce=${current.allConnectedOnce} timedOut=${current.timedOut} stopped=${closed.get()}"
    }

    fun connectAll(
        timeoutMs: Long,
        retryDelayMs: Long = 400L,
    ): Map<CarLifeChannel, Socket> {
        val startedAt = nowMs()
        startedAtMs = startedAt
        val deadline = startedAt + timeoutMs
        var attempt = 0
        var lastError = "listeners not ready"
        while (!closed.get() && nowMs() < deadline) {
            attempt++
            progress = progress.copy(attempts = attempt, openedThisAttempt = emptyList(), pendingPort = null)
            val candidate = EnumMap<CarLifeChannel, Socket>(CarLifeChannel::class.java)
            var connecting: CarLifeChannel? = null
            try {
                CarLifeChannel.entries.forEach { channel ->
                    if (closed.get()) throw IOException("cancelled")
                    connecting = channel
                    progress = progress.copy(pendingPort = channel.port)
                    candidate[channel] = connect(channel.port)
                    progress = progress.copy(openedThisAttempt = candidate.keys.map { it.port }, pendingPort = null)
                    connecting = null
                }
                synchronized(sockets) {
                    if (closed.get()) throw IOException("cancelled")
                    sockets.putAll(candidate)
                }
                finishedAtMs = nowMs()
                progress = progress.copy(allConnectedOnce = true)
                return candidate
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
                val failure =
                    when {
                        closed.get() || error is InterruptedException -> Failure.CANCELLED
                        error is SecurityException -> Failure.PERMISSION
                        error is SocketTimeoutException -> Failure.TIMEOUT
                        error is IOException && error.message.orEmpty().contains("refused", ignoreCase = true) -> Failure.REFUSED
                        error is IOException -> Failure.IO
                        else -> Failure.OTHER
                    }
                val changed = progress.lastFailedPort != connecting?.port || progress.lastFailure != failure
                if (failure != Failure.CANCELLED && (attempt == 1 || changed || attempt % 25 == 0)) {
                    DiagnosticExportPolicy.exception("CARLIFE_CONNECT_PORT_${connecting?.port ?: 0}", error)
                }
                progress = progress.copy(pendingPort = null, lastFailedPort = connecting?.port, lastFailure = failure)
                candidate.values.forEach { runCatching { it.close() } }
                if (failure != Failure.CANCELLED) {
                    runCatching { onAttemptFailed?.invoke(attempt, error) }
                }
                log("CARLIFE: 连接尝试 $attempt 未完成 port=${connecting?.port ?: 0} opened=${candidate.size} reason=$failure")
                val remaining = deadline - nowMs()
                if (!closed.get() && remaining > 0) sleep(minOf(retryDelayMs, remaining))
            }
        }
        if (closed.get()) throw IOException("CarLife connection cancelled")
        finishedAtMs = nowMs()
        progress = progress.copy(timedOut = true)
        throw CarLifeListenerTimeoutException("CarLife listener wait deadline: $lastError")
    }

    fun socket(channel: CarLifeChannel): Socket? =
        synchronized(sockets) {
            sockets[channel]?.takeUnless { it.isClosed }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (finishedAtMs == null) finishedAtMs = nowMs()
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

/** CarWith 的七路监听在等待期内未全部就绪。 */
class CarLifeListenerTimeoutException(
    message: String,
) : IOException(message)
