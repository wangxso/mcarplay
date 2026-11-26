package com.mcarplay.app.session

import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3b I 层：真实线程下的锁序、B-5/B-7、50 轮多锁压测与 100 轮线程池中断位回归。
 */
class Phase3bConcurrencyIntegrationTest {
    @Test(timeout = 20_000L)
    fun multiLockDeadlockStressCompletesUnderTwoSeconds() {
        val pool = Executors.newFixedThreadPool(2)
        val wifiBindLock = Any()
        val controllerMonitor = Any()
        val failures = AtomicInteger(0)
        val start = System.nanoTime()
        try {
            repeat(50) { round ->
                val scope = RealAttemptScope("round-$round", null)
                val signal = SessionCancelSignal()
                val entered = CountDownLatch(1)
                val done = CountDownLatch(1)
                val never = CountDownLatch(1)
                pool.submit {
                    synchronized(wifiBindLock) {
                        entered.countDown()
                        try {
                            if (round % 2 == 0) {
                                scope.sleep(5_000L)
                            } else {
                                scope.await(never, 5_000L)
                            }
                        } finally {
                            done.countDown()
                        }
                    }
                }
                assertTrue("round $round 未进入 wifiBindLock", entered.await(2, TimeUnit.SECONDS))
                pool
                    .submit {
                        synchronized(controllerMonitor) {
                            scope.cancel(DiagnosticCode.USER_STOP)
                            signal.cancel()
                            synchronized(wifiBindLock) {
                                // 取消后必须能拿回 wifiBindLock；拿不到即为死锁。
                            }
                        }
                    }.get(2, TimeUnit.SECONDS)
                if (!done.await(2, TimeUnit.SECONDS)) failures.incrementAndGet()
            }
        } finally {
            pool.shutdownNow()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("50 轮总耗时 ${elapsedMs}ms 超过 2s", elapsedMs < 2_000L)
        assertTrue("存在未能退场的轮次", failures.get() == 0)
    }

    @Test(timeout = 10_000L)
    fun b5LinkCloseCancelsAttemptButNotSessionWideSignal() {
        val signal = SessionCancelSignal()
        val scopeA = RealAttemptScope("a", null)
        val scopeB = RealAttemptScope("b", null)
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val worker =
            Thread {
                entered.countDown()
                result.set(scopeA.sleep(30_000L))
                done.countDown()
            }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        val start = System.nanoTime()
        scopeA.cancel(DiagnosticCode.BT_LINK_CLOSED)
        assertTrue(done.await(500, TimeUnit.MILLISECONDS))
        assertTrue("B-5 退场耗时超过 150ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 150L)
        assertFalse(result.get())
        assertTrue("0x5703 会话信号不得随 BT 拆链取消", !signal.isCancelled)
        assertTrue(scopeB.isActive)
        worker.join(500)
    }

    @Test(timeout = 10_000L)
    fun b7NormalWaitCompletesWithoutFalseCancellation() {
        val scope = RealAttemptScope("normal", null)
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val worker =
            Thread {
                completed.set(scope.await(latch, 2_000L))
            }
        worker.start()
        Thread.sleep(100)
        latch.countDown()
        worker.join(2_000)
        assertTrue("正常路径必须完整等待到 latch 归零", completed.get())
        assertTrue(scope.isActive)
        assertFalse(scope.cancelReason != null)
    }

    @Test(timeout = 20_000L)
    fun poolAwaitCancelRaceLeavesNoInterruptLeakOver100Rounds() {
        val workerPool = Executors.newSingleThreadExecutor()
        val racePool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                val scope = RealAttemptScope("pool-$round", null)
                val latch = CountDownLatch(1)
                val future =
                    workerPool.submit<Boolean> {
                        assertFalse("round $round 进入前不得残留中断位", Thread.currentThread().isInterrupted)
                        scope.await(latch, 5_000L)
                        Thread.currentThread().isInterrupted
                    }
                racePool
                    .submit {
                        scope.cancel(DiagnosticCode.BT_LINK_CLOSED)
                    }.get(2, TimeUnit.SECONDS)
                val leaked = future.get(2, TimeUnit.SECONDS)
                assertFalse("round $round 退出 await 后中断位必须为 false", leaked)
            }
        } finally {
            workerPool.shutdownNow()
            racePool.shutdownNow()
        }
    }
}
