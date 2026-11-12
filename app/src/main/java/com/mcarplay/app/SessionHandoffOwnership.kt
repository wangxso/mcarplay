package com.mcarplay.app

import com.mcarplay.app.compat.ReceiverObservation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks which RFCOMM observation owns the one in-flight CarPlay handoff.
 *
 * A newer link can be "current" for receiver detection without owning the endpoint that
 * already started Wi-Fi binding or AirPlay pairing. Only the owning link may cancel it.
 */
internal class SessionHandoffOwnership(
    private val active: AtomicBoolean,
) {
    private val lock = Any()
    private var owner: ReceiverObservation? = null
    private var accepting = true

    /**
     * 获取交接所有权；[onAcquired] 在同一锁内执行，供调用方清除旧的取消标志。
     * 断连先通过 [blockNewHandoffs] 关闭入口，因此不会在该回调之后再被旧端点覆盖。
     */
    fun begin(
        observation: ReceiverObservation?,
        onAcquired: () -> Unit = {},
    ): Boolean =
        synchronized(lock) {
            if (!accepting) return@synchronized false
            if (!active.compareAndSet(false, true)) return@synchronized false
            owner = observation
            onAcquired()
            true
        }

    fun endIfOwnedBy(observation: ReceiverObservation?): Boolean =
        synchronized(lock) {
            val currentOwner = owner
            if (observation != currentOwner) return@synchronized false
            if (!active.compareAndSet(true, false)) return@synchronized false
            owner = null
            true
        }

    fun owns(observation: ReceiverObservation?): Boolean =
        synchronized(lock) {
            active.get() && owner == observation
        }

    /** 只有归属链路能取消交接；状态变更与回调在同一锁内完成。 */
    fun cancelIfOwnedBy(
        observation: ReceiverObservation?,
        isAlreadyCancelled: () -> Boolean = { false },
        onCancel: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (!active.get() || owner != observation) return@synchronized false
            active.set(false)
            owner = null
            if (isAlreadyCancelled()) return@synchronized false
            onCancel()
            true
        }

    /** 取消/终止路径无条件释放，不依赖链路关闭通知或接收轮次是否仍为 current。 */
    fun release() {
        synchronized(lock) {
            owner = null
            active.set(false)
        }
    }

    /** 进入断开/替换流程：原子地禁止新交接并释放现有所有权。 */
    fun blockNewHandoffs() {
        synchronized(lock) {
            accepting = false
            owner = null
            active.set(false)
        }
    }

    /** 断开清理完成，允许下一轮连接重新获取交接。 */
    fun allowNewHandoffs() {
        synchronized(lock) {
            accepting = true
        }
    }
}
