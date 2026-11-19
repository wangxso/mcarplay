package com.mcarplay.app.bt

/**
 * EA 退役计时语义的窄封装：同步短路、后台发送、条件冲刷与 finally close。
 *
 * EA 的决策与幂等仍留在 [com.mcarplay.app.iap2.Iap2SenderSession.stopEaSessions]。
 * helper 不吸收后台任务异常；`dispatch` 提交失败也不会 close。
 */
internal class EaTimingShutdown(
    private val reason: String?,
    private val closeLink: () -> Unit,
) {
    internal fun interface Dispatcher {
        fun dispatch(
            name: String,
            task: () -> Unit,
        )
    }

    internal fun interface Sleeper {
        fun sleep(millis: Long)
    }

    internal interface Sender {
        fun hasSessionsToStop(): Boolean

        fun stop(reason: String): Int
    }

    internal fun run(
        session: Sender?,
        dispatcher: Dispatcher,
        sleeper: Sleeper,
    ) {
        if (reason == null || session == null || !session.hasSessionsToStop()) {
            closeLink()
            return
        }
        dispatcher.dispatch(BT_EA_STOP_TASK_NAME) {
            try {
                if (session.stop(reason) > 0) sleeper.sleep(EA_STOP_FLUSH_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                closeLink()
            }
        }
    }
}
