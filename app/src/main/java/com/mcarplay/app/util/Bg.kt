package com.mcarplay.app.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 短命后台任务的共享线程池：UI 点击/会话流程/轮询里的一次性任务统一走这里，
 * 替代随手 new Thread（线程创建开销 + 不可观测 + 失控堆积风险）。
 *
 * cached 池：空闲 60s 自动回收、有任务无空闲线程时新建——行为与 new Thread 等价，
 * 但高峰复用、有统一命名。长驻循环线程（GL 合成/链路 reader/accept 循环等）
 * 不走这里：它们需要独立命名、interrupt 与生命周期持有。
 */
object Bg {
    private val counter = AtomicInteger(0)

    val pool: ExecutorService =
        Executors.newCachedThreadPool { r ->
            Thread(r, "app-bg-${counter.incrementAndGet()}").apply { isDaemon = true }
        }

    /** 提交一次性后台任务。[name] 进线程名（阻塞/崩溃时 logcat 可定位）。 */
    fun run(
        name: String,
        block: () -> Unit,
    ) {
        pool.execute {
            Thread.currentThread().name = "app-bg-$name"
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.w("Bg", "bg task '$name' failed", t)
            }
        }
    }
}
