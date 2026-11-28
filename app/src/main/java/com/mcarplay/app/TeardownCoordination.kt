package com.mcarplay.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal interface CompletionState {
    var completion: CountDownLatch?
    val userStopGeneration: Long

    fun setSessionActive(active: Boolean)

    fun allowNewHandoffs()
}

internal class TeardownCoordination(
    private val ownerMonitor: Any,
    private val state: CompletionState,
    private val tasks: NamedTasks,
) {
    fun complete(
        captured: CountDownLatch,
        logDone: () -> Unit,
    ) {
        synchronized(ownerMonitor) {
            state.setSessionActive(false)
            if (state.completion === captured) {
                state.allowNewHandoffs()
                state.completion = null
            }
        }
        captured.countDown()
        logDone()
    }

    /**
     * 调用前由原 `@Synchronized` 入口持 [ownerMonitor]。
     *
     * @return 已排队等待旧收尾时返回 true；当前没有收尾任务时返回 false。
     */
    fun deferStartIfStopping(
        retryOriginalEntry: () -> Unit,
        logTimeout: () -> Unit,
    ): Boolean {
        val stopping = state.completion ?: return false
        val stopGeneration = state.userStopGeneration
        tasks.submit("connect-after-disconnect") {
            if (!stopping.await(10, TimeUnit.SECONDS)) {
                logTimeout()
                return@submit
            }
            synchronized(ownerMonitor) {
                if (state.userStopGeneration == stopGeneration) retryOriginalEntry()
            }
        }
        return true
    }
}
