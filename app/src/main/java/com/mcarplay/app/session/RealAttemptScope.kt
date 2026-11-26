package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 真实轮次作用域：单调取消、独立取代、锁外 LIFO 资源清理、无中断污染 [sleep] 与 Per-Waiter 叶锁 [await]。
 *
 * 锁序不变量：内部 [resourceLock] 与 [Waiter] 监视器均为私有叶锁，持锁期间绝不调用任何外部回调、日志或 `close()`。
 */
internal class RealAttemptScope(
    override val id: String,
    override val observation: ReceiverObservation?,
) : AttemptScope {
    private class Waiter(
        val thread: Thread,
    ) {
        var active: Boolean = true
        var interruptedByScope: Boolean = false
    }

    private val reasonRef = AtomicReference<DiagnosticCode?>(null)
    private val supersededFlag = AtomicBoolean(false)
    private val wakeLatch = CountDownLatch(1)
    private val awaiters = ConcurrentHashMap.newKeySet<Waiter>()
    private val resourceLock = Any()
    private val owned = ArrayList<AutoCloseable>()
    private var resourcesClosed = false

    override val cancelReason: DiagnosticCode?
        get() = reasonRef.get()

    override val isCurrent: Boolean
        get() = !supersededFlag.get()

    override val isActive: Boolean
        get() = reasonRef.get() == null && !supersededFlag.get()

    override fun cancel(reason: DiagnosticCode) {
        reasonRef.compareAndSet(null, reason)
        wakeAndCloseOwned()
    }

    /** 标记已被新一轮或终态取代：isCurrent 与 isActive 同时变为 false，并唤醒等待与关闭资源。 */
    fun supersede() {
        supersededFlag.set(true)
        wakeAndCloseOwned()
    }

    override fun checkActive() {
        if (!isActive) throw AttemptCancelled(cancelReason)
    }

    override fun <T : AutoCloseable> own(resource: T): T {
        val closeNow =
            synchronized(resourceLock) {
                if (resourcesClosed || !isActive) {
                    true
                } else {
                    owned.add(resource)
                    false
                }
            }
        if (closeNow) {
            runCatching { resource.close() }
        }
        return resource
    }

    override fun await(
        latch: CountDownLatch,
        timeoutMs: Long,
    ): Boolean {
        if (!isActive) return false
        if (latch.count == 0L) return true
        if (timeoutMs <= 0L) return false
        val waiter = Waiter(Thread.currentThread())
        awaiters.add(waiter)
        var interruptedExternally = false
        try {
            if (!isActive) return false
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && isActive
        } catch (_: InterruptedException) {
            interruptedExternally = true
            return false
        } finally {
            awaiters.remove(waiter)
            val interruptedByScope =
                synchronized(waiter) {
                    waiter.active = false
                    waiter.interruptedByScope
                }
            if (interruptedByScope) {
                Thread.interrupted()
            } else if (interruptedExternally) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun sleep(ms: Long): Boolean {
        if (!isActive) return false
        if (ms <= 0L) return true
        return try {
            val wokenByScope = wakeLatch.await(ms, TimeUnit.MILLISECONDS)
            !wokenByScope && isActive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun wakeAndCloseOwned() {
        wakeLatch.countDown()
        for (waiter in awaiters) {
            synchronized(waiter) {
                if (waiter.active) {
                    waiter.interruptedByScope = true
                    waiter.thread.interrupt()
                }
            }
        }
        val toClose =
            synchronized(resourceLock) {
                if (resourcesClosed && owned.isEmpty()) {
                    emptyList()
                } else {
                    resourcesClosed = true
                    if (owned.isEmpty()) {
                        emptyList()
                    } else {
                        val snapshot = ArrayList(owned)
                        owned.clear()
                        snapshot
                    }
                }
            }
        for (i in toClose.indices.reversed()) {
            runCatching { toClose[i].close() }
        }
    }
}
