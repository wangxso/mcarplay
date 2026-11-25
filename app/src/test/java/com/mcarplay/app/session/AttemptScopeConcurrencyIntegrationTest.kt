package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * I 层多线程并发集成测试：验证 [RealAttemptScope] 与 [AttemptRegistry] 在真实锁序与高并发竞态下的
 * 零死锁、零中断位泄漏与状态单调性。
 */
class AttemptScopeConcurrencyIntegrationTest {
    private val obsA = ReceiverObservation(binding = "binding-i", epoch = 1, session = 1)
    private val obsB = ReceiverObservation(binding = "binding-i", epoch = 1, session = 2)

    @Test(timeout = 15_000L)
    fun lockOrderStressBetweenWifiBindLockAndControllerMonitorHasZeroDeadlocks() {
        val wifiBindLock = Any()
        val controllerMonitor = Any()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(50) { round ->
                val scope = RealAttemptScope("lock-round-$round", obsA)
                val enteredWifiLock = CountDownLatch(1)
                val workerDone = CountDownLatch(1)
                val workerResult = AtomicBoolean(true)
                val workerInterrupted = AtomicBoolean(true)
                val neverLatch = CountDownLatch(1)

                val t1 =
                    pool.submit {
                        synchronized(wifiBindLock) {
                            enteredWifiLock.countDown()
                            val ok =
                                if (round % 2 == 0) {
                                    scope.sleep(5_000L)
                                } else {
                                    scope.await(neverLatch, 5_000L)
                                }
                            workerResult.set(ok)
                            workerInterrupted.set(Thread.currentThread().isInterrupted)
                        }
                        workerDone.countDown()
                    }

                assertTrue("round $round worker 未及时进入 wifiBindLock", enteredWifiLock.await(2, TimeUnit.SECONDS))
                val cancelStart = System.nanoTime()
                val t2 =
                    pool.submit {
                        synchronized(controllerMonitor) {
                            scope.cancel(DiagnosticCode.USER_STOP)
                            synchronized(wifiBindLock) {
                                // 模拟 disconnect() 取消后立即进入 wifiHandoffGuard.cleanup。
                            }
                        }
                    }

                t1.get(2, TimeUnit.SECONDS)
                t2.get(2, TimeUnit.SECONDS)
                assertTrue(workerDone.await(2, TimeUnit.SECONDS))
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStart)
                assertTrue("round $round 耗时 ${elapsedMs}ms 超过 200ms 预算", elapsedMs < 200L)
                assertFalse("round $round 被取消的等待必须返回 false", workerResult.get())
                assertFalse("round $round 不得泄漏线程中断位", workerInterrupted.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(timeout = 20_000L)
    fun awaitAndCancelRaceStressNeverLeaksInterruptFlagToReusedPoolThread() {
        // 使用单线程工作池模拟 Bg 复用线程，连续 200 轮压测中断位绝不残留。
        val workerPool = Executors.newSingleThreadExecutor()
        val racePool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { round ->
                val scope = RealAttemptScope("race-round-$round", obsA)
                val latch = CountDownLatch(1)
                val barrier = CyclicBarrier(3)

                val workerFuture =
                    workerPool.submit<Boolean> {
                        assertFalse("round $round 进入前复用线程不得残留中断位", Thread.currentThread().isInterrupted)
                        barrier.await(2, TimeUnit.SECONDS)
                        scope.await(latch, 5_000L)
                        Thread.currentThread().isInterrupted
                    }
                val countDownFuture =
                    racePool.submit {
                        barrier.await(2, TimeUnit.SECONDS)
                        latch.countDown()
                    }
                val cancelFuture =
                    racePool.submit {
                        barrier.await(2, TimeUnit.SECONDS)
                        scope.cancel(DiagnosticCode.BT_LINK_CLOSED)
                    }

                countDownFuture.get(2, TimeUnit.SECONDS)
                cancelFuture.get(2, TimeUnit.SECONDS)
                val leaked = workerFuture.get(2, TimeUnit.SECONDS)
                assertFalse("round $round 退出 await 后工作线程中断位必须恒为 false", leaked)
            }
        } finally {
            workerPool.shutdownNow()
            racePool.shutdownNow()
        }
    }

    @Test(timeout = 15_000L)
    fun b5ConcurrentLinkCloseAndNewEndpointActivationNeverRevivesOldScope() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(30) { round ->
                val registry = AttemptRegistry()
                val scopeA = RealAttemptScope("b5-a-$round", obsA)
                val scopeB = RealAttemptScope("b5-b-$round", obsB)
                registry.activate(scopeA)

                val neverLatch = CountDownLatch(1)
                val entered = CountDownLatch(1)
                val workerResult = AtomicBoolean(true)
                val workerInterrupted = AtomicBoolean(true)

                val waiter =
                    pool.submit {
                        entered.countDown()
                        workerResult.set(scopeA.await(neverLatch, 5_000L))
                        workerInterrupted.set(Thread.currentThread().isInterrupted)
                    }

                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val triggerAt = System.nanoTime()
                val switcher =
                    pool.submit {
                        assertTrue(registry.cancelIfObservation(obsA, DiagnosticCode.BT_LINK_CLOSED))
                        assertFalse("B-5：激活 scopeB 前 scopeA 必须已失效", scopeA.isActive)
                        registry.activate(scopeB)
                        assertFalse("B-5：激活 scopeB 后 scopeA 仍恒为失效", scopeA.isActive)
                    }

                switcher.get(2, TimeUnit.SECONDS)
                waiter.get(2, TimeUnit.SECONDS)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - triggerAt)

                assertTrue("round $round 唤醒耗时 ${elapsedMs}ms 超过 150ms", elapsedMs < 150L)
                assertFalse(workerResult.get())
                assertFalse(workerInterrupted.get())
                assertFalse(scopeA.isActive)
                assertFalse(scopeA.isCurrent)
                assertTrue(scopeB.isActive)
                assertTrue(scopeB.isCurrent)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
