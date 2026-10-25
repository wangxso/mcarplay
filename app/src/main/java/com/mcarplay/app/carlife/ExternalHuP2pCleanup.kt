package com.mcarplay.app.carlife

import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the P2P channel alive until removeGroup replies or its bounded timeout expires. */
internal class ExternalHuP2pCleanup(
    private val scheduleTimeout: (Runnable) -> Unit,
    private val cancelTimeout: (Runnable) -> Unit,
    private val closeChannel: () -> Unit,
    private val onTimeout: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val timeout = Runnable { finish(timedOut = true) }

    fun release(removeGroup: ((() -> Unit) -> Unit)? = null) {
        if (!started.compareAndSet(false, true)) return
        if (removeGroup == null) {
            finish()
            return
        }
        try {
            scheduleTimeout(timeout)
            removeGroup { finish() }
        } catch (error: Exception) {
            finish()
            throw error
        }
    }

    private fun finish(timedOut: Boolean = false) {
        if (!finished.compareAndSet(false, true)) return
        try {
            if (timedOut) onTimeout()
        } finally {
            try {
                cancelTimeout(timeout)
            } finally {
                closeChannel()
            }
        }
    }
}
