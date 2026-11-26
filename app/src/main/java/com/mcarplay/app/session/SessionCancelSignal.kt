package com.mcarplay.app.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 会话级（跨轮次）取消与可打断等待信号（Phase 3b）。
 *
 * 每次终态 [cancel] 后，下次 [reset] 换发不可变新 [Epoch]：
 *  - 旧 Epoch 永不复活（彻底避免已取消线程在新会话启动后继续睡/复活）；
 *  - 若当前 Epoch 未取消，[reset] 为 no-op（严格保护附录 B-10：运行中重入 `startConnection` 不误取消在途任务）。
 */
internal class SessionCancelSignal {
    private class Waiter(
        val thread: Thread,
    ) {
        var active: Boolean = true
        var interruptedBySignal: Boolean = false
    }

    private class Epoch {
        private val cancelledFlag = AtomicBoolean(false)
        private val wakeLatch = CountDownLatch(1)
        private val awaiters = ConcurrentHashMap.newKeySet<Waiter>()

        val isCancelled: Boolean get() = cancelledFlag.get()

        fun cancel() {
            if (!cancelledFlag.compareAndSet(false, true)) return
            wakeLatch.countDown()
            val snapshot = awaiters.toList()
            snapshot.forEach { waiter ->
                synchronized(waiter) {
                    if (waiter.active) {
                        waiter.interruptedBySignal = true
                        waiter.thread.interrupt()
                    }
                }
            }
        }

        fun sleep(ms: Long): Boolean {
            if (isCancelled) return false
            if (ms <= 0L) return true
            return try {
                !wakeLatch.await(ms, TimeUnit.MILLISECONDS) && !isCancelled
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun await(
            latch: CountDownLatch,
            timeoutMs: Long,
        ): Boolean {
            if (isCancelled) return false
            if (latch.count == 0L) return true
            if (timeoutMs <= 0L) return false
            val waiter = Waiter(Thread.currentThread())
            awaiters.add(waiter)
            var externallyInterrupted = false
            return try {
                if (isCancelled) return false
                try {
                    latch.await(timeoutMs, TimeUnit.MILLISECONDS) && !isCancelled
                } catch (_: InterruptedException) {
                    externallyInterrupted = true
                    false
                }
            } finally {
                awaiters.remove(waiter)
                val bySignal =
                    synchronized(waiter) {
                        waiter.active = false
                        waiter.interruptedBySignal
                    }
                if (bySignal) {
                    Thread.interrupted()
                } else if (externallyInterrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private val epochRef = AtomicReference(Epoch())

    val isCancelled: Boolean get() = epochRef.get().isCancelled

    fun cancel() = epochRef.get().cancel()

    /** 仅当当前 Epoch 已被取消时换发新 Epoch；运行中重复调用安全 no-op（B-10）。 */
    fun reset() {
        while (true) {
            val current = epochRef.get()
            if (!current.isCancelled) return
            if (epochRef.compareAndSet(current, Epoch())) return
        }
    }

    fun sleep(ms: Long): Boolean = epochRef.get().sleep(ms)

    fun await(
        latch: CountDownLatch,
        timeoutMs: Long,
    ): Boolean = epochRef.get().await(latch, timeoutMs)
}
