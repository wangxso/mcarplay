package com.mcarplay.app.input

/**
 * 车机媒体键 HID 报告的边沿判定：把「状态式」报告变成「按下一次 = 触发一次」。
 *
 * 车机的 Consumer 报告是**状态**而不是事件：按住会以 ~10Hz 连推同一个值/位图，
 * 松开推 0x00。直接按帧触发的话，一次长按会跳掉几十首歌。因此只在 Usage「新出现」时
 * 触发；同一组按键持续按住超过 [holdRepeatMillis] 才再触发一次，保持方向盘长按
 * 「连续切歌」的手感。
 *
 * iAP2 0x6802 与 AirPlay `hidSendReport` 两条通路共用；每台 HID 设备一个实例。
 * 非线程安全，由调用方在各自的读取线程/锁内使用。
 */
class MediaKeyEdge(
    private val holdRepeatMillis: Long = HOLD_REPEAT_MILLIS,
) {
    /** 上一次报文里按下的 Usage 集合（松开后清空）。 */
    private var lastUsages: Set<Int> = emptySet()

    /** 当前这批按键的起始时刻；0 = 无按键。 */
    private var holdStartedAtMs = 0L

    /**
     * 一帧报告 → 本帧应触发的 Usage（多数帧返回空）。
     *
     * @param usages 本帧处于按下状态的 Usage（[ConsumerKeys.KeyMap.usagesFor] 的结果）
     * @param nowMs 单调时钟毫秒
     */
    fun onReport(
        usages: List<Int>,
        nowMs: Long,
    ): List<Int> {
        if (usages.isEmpty()) {
            reset()
            return emptyList()
        }
        val fresh = usages.filter { it !in lastUsages }
        val repeat =
            fresh.isEmpty() &&
                holdStartedAtMs > 0 &&
                nowMs - holdStartedAtMs >= holdRepeatMillis
        lastUsages = usages.toSet()
        if (fresh.isNotEmpty()) holdStartedAtMs = nowMs
        if (fresh.isEmpty() && !repeat) return emptyList()
        if (repeat) holdStartedAtMs = nowMs
        return if (fresh.isNotEmpty()) fresh else usages
    }

    /** 释放全部按键（报文 0x00、会话重建）。 */
    fun reset() {
        lastUsages = emptySet()
        holdStartedAtMs = 0L
    }

    companion object {
        /** 同一按键持续按住多久才允许再次触发（模拟长按连发，短按绝不重复）。 */
        const val HOLD_REPEAT_MILLIS = 1_500L
    }
}
