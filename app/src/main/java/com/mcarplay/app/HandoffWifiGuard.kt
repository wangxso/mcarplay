package com.mcarplay.app

/** 绑定、广播启动与断开清理共用锁；取消后旧任务不能重新持有网络。 */
internal class HandoffWifiGuard(
    val lock: Any = Any(),
) {
    fun <N : Any> matchesRequiredNetwork(
        required: N?,
        current: N?,
    ): Boolean = required == null || required == current

    fun bind(
        isCancelled: () -> Boolean,
        connect: () -> Boolean,
        release: () -> Unit,
        onBound: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (isCancelled()) return@synchronized false
            if (!connect()) {
                if (isCancelled()) release()
                return@synchronized false
            }
            if (isCancelled()) {
                release()
                return@synchronized false
            }
            onBound()
            if (isCancelled()) {
                release()
                return@synchronized false
            }
            true
        }

    fun runIfActive(
        isCancelled: () -> Boolean,
        action: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (isCancelled()) return@synchronized false
            action()
            !isCancelled()
        }

    fun cleanup(action: () -> Unit) = synchronized(lock) { action() }
}
