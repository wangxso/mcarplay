package com.mcarplay.app.video

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 透传队列溢出后的补要关键帧：限流 + 后台派发。
 *
 * 为什么必须异步：`submitExternalFrame()` 由 CarLife 的视频读线程调用；[request] 最终会写
 * COMMAND 数据口（`CarLifeSession.requestKeyFrame()`），在链路拥塞时可能阻塞。读线程一旦
 * 卡在这里，CarLife 的整条视频通道都会停摆。所以 [signal] 只做限流判定并把任务放进内存队列，
 * 由本类自己的守护线程调用 [request]。
 *
 * 限流：[intervalMillis] 内最多一次，避免容量降到 8 之后把「队满」放大成新的要帧风暴。
 * 尚未派发的放行请求会合并为一次（关键帧请求本身幂等），不会在上一次阻塞解除后连发一串。
 */
internal class OverflowKeyFrameRecovery(
    private val request: () -> Boolean,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onRequest: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)

    /**
     * 是否有已限流放行、尚未派发的补要（0/1）。
     *
     * 合并为一次：关键帧请求是幂等的「请尽快出一个 IDR」。限流已经保证 1 s 内最多一次；
     * 若上一次请求仍阻塞在命令通道上，继续累积只会让它在解除阻塞后连发一串命令。
     */
    private val pendingSignal = AtomicBoolean(false)
    private val work = LinkedBlockingQueue<Unit>(1)
    private val lock = Any()
    private var lastRequestAt: Long? = null
    private var worker: Thread? = null

    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
    }

    fun start() {
        synchronized(lock) {
            if (running.getAndSet(true)) return
            worker =
                Thread({ loop() }, "video-overflow-keyframe").apply {
                    isDaemon = true
                    start()
                }
        }
    }

    /**
     * 停止派发；不等待线程退出（[stop] 可能在 UI 线程的断开路径上调用，不能阻塞）。
     *
     * 守护线程会在当前一次请求返回后看到 `running=false` 并退出；之后 [signal] 一律返回 false。
     */
    fun stop() {
        val thread: Thread?
        synchronized(lock) {
            if (!running.getAndSet(false)) return
            thread = worker
            worker = null
        }
        pendingSignal.set(false)
        work.clear()
        thread?.interrupt()
    }

    /**
     * 记一次队满；返回是否派发了一次补要（限流窗口外才为 true）。
     *
     * 只做内存操作，绝不调用 [request]——调用方是 CarLife 读线程。
     */
    fun signal(now: Long = nowMillis()): Boolean {
        if (!running.get()) return false
        synchronized(lock) {
            val last = lastRequestAt
            if (last != null && now - last < intervalMillis) return false
            lastRequestAt = now
        }
        pendingSignal.set(true)
        // 队列容量 1：已经有一项在排队时 offer 失败也无妨，pendingSignal 已置位。
        work.offer(Unit)
        return true
    }

    private fun loop() {
        while (running.get()) {
            try {
                work.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!running.get()) return
            if (!pendingSignal.getAndSet(false)) continue
            val requested =
                try {
                    request()
                } catch (error: Exception) {
                    log("VIDEO: 透传队列溢出补要关键帧失败 - ${error.message}")
                    false
                }
            runCatching { onRequest(requested) }
        }
    }

    companion object {
        /** 队满后补要关键帧的最小间隔：与屏幕门控的 IDR 重试间隔一致（1 s）。 */
        const val DEFAULT_INTERVAL_MS = 1_000L
        private const val POLL_MILLIS = 250L
    }
}
