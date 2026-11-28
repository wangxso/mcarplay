package com.mcarplay.app.audio

internal enum class NavigationPlaybackState(
    val wireValue: Int,
) {
    UNKNOWN(-1),
    IDLE(0),
    PLAYING(1),
    ;

    companion object {
        fun fromWire(value: Int): NavigationPlaybackState = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * A recorder may register successfully but yield only zeroes on some ROMs.
 * Until it proves it can take real samples, bound how long we exclude maps from ordinary capture.
 * After confirmation, quiet intervals are normal; a closed pipe is handled separately by the sender.
 */
internal class NavigationCaptureHealth(
    startedAtMs: Long,
) {
    private var lastMs = startedAtMs
    private var previousState = NavigationPlaybackState.UNKNOWN
    private var unknownSinceMs: Long? = startedAtMs
    private var playingWithoutSignalMs = 0L
    var verified = false
        private set

    fun shouldRestoreGenericCapture(
        frame: ByteArray?,
        nowMs: Long,
        state: NavigationPlaybackState,
    ): Boolean {
        if (verified) return false
        if (frame?.any { it != 0.toByte() } == true) {
            verified = true
            return false
        }
        if (previousState == NavigationPlaybackState.PLAYING) {
            playingWithoutSignalMs += (nowMs - lastMs).coerceAtLeast(0L)
        }
        lastMs = nowMs
        previousState = state
        if (state != NavigationPlaybackState.UNKNOWN) {
            unknownSinceMs = null
        } else if (unknownSinceMs == null) {
            unknownSinceMs = nowMs
        }
        return playingWithoutSignalMs >= ACTIVE_VERIFY_MS ||
            unknownSinceMs?.let { nowMs - it >= UNKNOWN_VERIFY_MS } == true
    }

    companion object {
        private const val ACTIVE_VERIFY_MS = 6_000L
        private const val UNKNOWN_VERIFY_MS = 30_000L
    }
}
