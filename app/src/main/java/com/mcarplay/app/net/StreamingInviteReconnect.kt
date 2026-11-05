package com.mcarplay.app.net

/**
 * 推流中收到车机 ctrl 邀请时是否重建会话。
 *
 * 连接阶段的邀请由连接流程（awaitCtrlInvite / 空端点观察任务）消费；这里只管会话已在推流时的邀请。
 * 大众 Cinemo 栈车机此时发邀请表示「要重建会话」，手机不接则车机约 18 s 后自行拆会话（黑屏）。
 * 重连本身会让车机再发邀请，故重连后 [DEBOUNCE_MS] 内的邀请一律忽略，避免反复重建。
 */
internal object StreamingInviteReconnect {
    const val DEBOUNCE_MS = 15_000L

    enum class Decision {
        /** 不在推流（或新一轮正在建立）：邀请归连接流程处理。 */
        NOT_STREAMING,

        /** 该车机未登记邀请重连：只记日志。 */
        NOT_ENABLED,

        /** 距上次邀请重连不足 [DEBOUNCE_MS]。 */
        DEBOUNCED,

        RECONNECT,
    }

    /** @param lastReconnectAtMillis 上次邀请重连的 uptime 时刻；0 = 本次连接尚未重连过 */
    fun decide(
        streaming: Boolean,
        enabled: Boolean,
        nowMillis: Long,
        lastReconnectAtMillis: Long,
    ): Decision =
        when {
            !streaming -> Decision.NOT_STREAMING
            !enabled -> Decision.NOT_ENABLED
            lastReconnectAtMillis > 0L && nowMillis - lastReconnectAtMillis < DEBOUNCE_MS -> Decision.DEBOUNCED
            else -> Decision.RECONNECT
        }
}
