package com.mcarplay.app.carlife

import com.mcarplay.app.input.TouchReports

enum class CarLifeTouchAction {
    DOWN,
    MOVE,
    UP,
}

/**
 * CarLife accepts one strict touch gesture: DOWN, zero or more MOVEs, then UP.
 * Keeping the transition rules here also gives the network layer a single
 * place to synthesize a final UP when a channel or session is torn down.
 */
class CarLifeTouchState {
    private var active = false
    private var lastX = 0
    private var lastY = 0

    @Synchronized
    fun onContact(
        x: Int,
        y: Int,
        down: Boolean,
        width: Int,
        height: Int,
    ): CarLifeTouchAction? {
        if (width <= 0 || height <= 0 || x !in 0 until width || y !in 0 until height) return null
        val action =
            when {
                down && !active -> CarLifeTouchAction.DOWN
                down -> CarLifeTouchAction.MOVE
                active -> CarLifeTouchAction.UP
                else -> return null
            }
        lastX = x
        lastY = y
        active = action != CarLifeTouchAction.UP
        return action
    }

    @Synchronized
    fun releasePoint(): Pair<Int, Int>? {
        if (!active) return null
        active = false
        return lastX to lastY
    }

    @Synchronized
    fun reset() {
        active = false
    }
}

internal fun mapCarLifeTouch(
    x: Int,
    y: Int,
    source: TouchReports.CoordinateRange,
    targetWidth: Int,
    targetHeight: Int,
): Pair<Int, Int>? {
    if (!source.isValid || targetWidth <= 0 || targetHeight <= 0) return null
    return mapTouchAxis(x, source.minX, source.maxX, targetWidth) to
        mapTouchAxis(y, source.minY, source.maxY, targetHeight)
}

private fun mapTouchAxis(
    value: Int,
    sourceMin: Int,
    sourceMax: Int,
    targetSize: Int,
): Int {
    val span = (sourceMax - sourceMin).coerceAtLeast(1)
    val clamped = value.coerceIn(sourceMin, sourceMax)
    val scaled = (clamped - sourceMin).toLong() * (targetSize - 1)
    return ((scaled + span / 2) / span).toInt().coerceIn(0, targetSize - 1)
}
