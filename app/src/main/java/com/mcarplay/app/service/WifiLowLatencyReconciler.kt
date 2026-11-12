package com.mcarplay.app.service

import java.util.concurrent.Executor

/**
 * 收敛到最新会话目标。executor 必须串行，耗时命令不能阻塞调用线程。
 * 持久标记表示命令可能已生效；不确定失败也保留标记供下次恢复。
 */
internal class WifiLowLatencyReconciler(
    private val executor: Executor,
    private val isReady: (desired: Boolean) -> Boolean,
    private val isMarked: () -> Boolean,
    private val setMarker: (Boolean) -> Boolean,
    private val setForced: (Boolean) -> Boolean,
) {
    @Volatile
    private var desired = false

    // 以下状态只在串行 executor 上访问。
    private var enabledInProcess = false

    fun setDesired(enabled: Boolean) {
        desired = enabled
        retry()
    }

    fun retry() {
        executor.execute { reconcile() }
    }

    private fun reconcile() {
        val target = desired
        if (target && enabledInProcess) return
        if (!target && !enabledInProcess && !isMarked()) return
        if (!isReady(target)) return
        if (target) {
            if (!setMarker(true)) return
            enabledInProcess = runCatching { setForced(true) }.getOrDefault(false)
        } else {
            val restored = runCatching { setForced(false) }.getOrDefault(false)
            // 即使恢复失败也不应假定模式仍启用：下个会话需要再次执行启用命令。
            enabledInProcess = false
            if (restored) setMarker(false)
        }
    }
}
