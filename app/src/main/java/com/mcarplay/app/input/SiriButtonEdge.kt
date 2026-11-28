package com.mcarplay.app.input

/** 每次按下只启动一次语音。HeadUnit-1 长按会连发，且可能不上报松开。 */
internal class SiriButtonEdge {
    private var lastDownAt: Long? = null

    fun onAction(
        action: Any?,
        nowMs: Long,
    ): Boolean {
        if (action !is Number) return false
        return when (action.toLong()) {
            BUTTON_DOWN -> {
                val previousDownAt = lastDownAt
                lastDownAt = nowMs
                previousDownAt == null || nowMs - previousDownAt >= REPEAT_GAP_MS
            }
            BUTTON_UP -> {
                lastDownAt = null
                false
            }
            else -> false
        }
    }

    private companion object {
        const val BUTTON_DOWN = 2L
        const val BUTTON_UP = 3L
        const val REPEAT_GAP_MS = 2_000L
    }
}
