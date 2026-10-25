package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLifeVideoStatsTest {
    @Test
    fun recordsFramesKeyFramesBytesAndTimestampSpan() {
        val stats = CarLifeVideoStats()

        stats.record(frame(timestamp = 100, bytes = 10, keyFrame = false))
        stats.record(frame(timestamp = 130, bytes = 20, keyFrame = true))

        assertEquals(
            CarLifeVideoStats.Snapshot(
                frames = 2,
                keyFrames = 1,
                bytes = 30,
                timestampSpanSeconds = 30,
            ),
            stats.snapshot(),
        )
    }

    @Test
    fun timestampsThatMoveBackwardsDoNotProduceNegativeSpan() {
        val stats = CarLifeVideoStats()
        stats.record(frame(timestamp = 100, bytes = 10, keyFrame = true))
        stats.record(frame(timestamp = 90, bytes = 10, keyFrame = false))

        assertEquals(0, stats.snapshot().timestampSpanSeconds)
    }

    private fun frame(
        timestamp: Long,
        bytes: Int,
        keyFrame: Boolean,
    ) = CarLifeVideoAccessUnit(
        timestamp = timestamp,
        data = ByteArray(bytes),
        keyFrame = keyFrame,
        sps = null,
        pps = null,
    )
}
