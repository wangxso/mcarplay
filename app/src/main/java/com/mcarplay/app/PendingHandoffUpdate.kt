package com.mcarplay.app

/**
 * 暂存交接期间晚到的端点。暂存与失败时释放所有权共用锁：
 * 晚到端点要么由旧交接接手，要么在所有权释放后启动新交接。
 *
 * [keyOf] 非空时按该键（生产为 RFCOMM observation）分槽：A 的交接尚未结束时
 * B 的 0x4301 先到，不能覆盖或混入 A 的暂存；交接释放后 B 仍能被单独唤醒。
 * 为空时退化为单一槽位（纯逻辑单测使用）。
 */
internal class PendingHandoffUpdate<T : Any>(
    private val isActive: () -> Boolean,
    private val keyOf: ((T) -> Any?)? = null,
) {
    private val pending = LinkedHashMap<Any?, T>()
    private val singleSlot = Any()

    private fun slotOf(update: T): Any? = keyOf?.invoke(update) ?: singleSlot

    /**
     * [current] 与 [update] 是否属于同一暂存槽位（生产为同一 RFCOMM observation）。
     *
     * 直接热更新、drain 前必须用它校验：另一条链路的晚到端点只能留在自己的槽位，
     * 否则会把 B 的地址/端口/observation 写进 A 正在配对的客户端。
     */
    fun sameSlot(
        current: T?,
        update: T,
    ): Boolean {
        val currentValue = current ?: return false
        return slotOf(currentValue) === slotOf(update)
    }

    @Synchronized
    fun routeIfActive(
        update: T,
        merge: (T, T) -> T = { _, latest -> latest },
        deliverDirectly: () -> Boolean,
    ): Boolean {
        if (!isActive()) return false
        if (!deliverDirectly()) {
            val slot = slotOf(update)
            val previous = pending[slot]
            pending[slot] = if (previous != null) merge(previous, update) else update
        }
        return true
    }

    @Synchronized
    fun takeIf(matches: (T) -> Boolean): T? {
        val entry = pending.entries.firstOrNull { matches(it.value) } ?: return null
        pending.remove(entry.key)
        return entry.value
    }

    /** 一次取走所有匹配项（交接释放后唤醒仍待处理的 observation）。 */
    @Synchronized
    fun takeAll(matches: (T) -> Boolean): List<T> {
        val selected = pending.entries.filter { matches(it.value) }
        selected.forEach { pending.remove(it.key) }
        return selected.map { it.value }
    }

    /**
     * 把未能投递/交接被占用的端点放回自己的槽位。
     * 同槽已有更新条目时保留更新的那条，避免旧端点覆盖新地址。
     */
    @Synchronized
    fun requeue(update: T) {
        pending.putIfAbsent(slotOf(update), update)
    }

    @Synchronized
    fun takeIfOrRelease(
        matches: (T) -> Boolean,
        release: () -> Unit,
    ): T? {
        val value = takeIf(matches)
        if (value == null) release()
        return value
    }

    @Synchronized
    fun take(): T? {
        val entry = pending.entries.lastOrNull() ?: return null
        pending.remove(entry.key)
        return entry.value
    }

    @Synchronized
    fun retainIf(matches: (T) -> Boolean) {
        pending.entries.removeAll { !matches(it.value) }
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }
}
