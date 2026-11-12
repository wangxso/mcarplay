package com.mcarplay.app

/**
 * 逐个唤醒暂存的晚到端点。
 *
 * 不能先 `takeAll` 再逐条投递：第一个端点接手后会占用交接互斥，后续 begin 失败，
 * 但它们已经从暂存删除，就此丢失。这里一次只取一条，只有 [deliver] 真正接手
 * （拿到交接所有权或进入空端点等待）才移除；交接忙/被拒时放回自己的槽位，
 * 等下一次所有权释放再唤醒。已关闭链路的条目先剪掉，避免复活死链路。
 */
internal class PendingEndpointWake<T : Any>(
    private val pending: PendingHandoffUpdate<T>,
    private val isActive: (T) -> Boolean,
    private val isBusy: () -> Boolean,
    private val deliver: (T) -> Boolean,
) {
    /**
     * @param prefer 优先投递的条目（通常为刚释放所有权的 observation 自己的
     *   late endpoint），其次才处理其他暂存项。
     * @return 是否成功接手了一条端点
     */
    fun wake(prefer: (T) -> Boolean): Boolean {
        pending.retainIf(isActive)
        val candidate = pending.takeIf(prefer) ?: pending.takeIf { !prefer(it) } ?: return false
        if (isBusy()) {
            pending.requeue(candidate)
            return false
        }
        if (!deliver(candidate)) {
            if (isActive(candidate)) pending.requeue(candidate)
            return false
        }
        return true
    }
}
