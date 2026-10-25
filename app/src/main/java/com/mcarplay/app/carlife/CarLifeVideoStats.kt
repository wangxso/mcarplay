package com.mcarplay.app.carlife

/** Thread-safe counters for evidence that real CarWith video reached the app. */
class CarLifeVideoStats {
    data class Snapshot(
        val frames: Long,
        val keyFrames: Long,
        val bytes: Long,
        val timestampSpanSeconds: Long,
    ) {
        fun summary(): String = "frames=$frames key=$keyFrames bytes=$bytes span=${timestampSpanSeconds}s"
    }

    private var frames = 0L
    private var keyFrames = 0L
    private var bytes = 0L
    private var firstTimestamp: Long? = null
    private var lastTimestamp: Long? = null

    @Synchronized
    fun record(accessUnit: CarLifeVideoAccessUnit) {
        frames++
        if (accessUnit.keyFrame) keyFrames++
        bytes += accessUnit.data.size
        if (firstTimestamp == null) firstTimestamp = accessUnit.timestamp
        lastTimestamp = accessUnit.timestamp
    }

    @Synchronized
    fun snapshot(): Snapshot =
        Snapshot(
            frames = frames,
            keyFrames = keyFrames,
            bytes = bytes,
            timestampSpanSeconds =
                if (firstTimestamp != null && lastTimestamp != null) {
                    (lastTimestamp!! - firstTimestamp!!).coerceAtLeast(0)
                } else {
                    0
                },
        )
}
