package com.mcarplay.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 3b J 层：会话级取消信号必须可打断、幂等、换代次后旧代永不复活，
 * 且取消唤醒不得把中断位污染给复用线程（B-10 回归）。
 */
class SessionCancelSignalTest {
    @Test
    fun initiallyActiveAndCancelIsIdempotent() {
        val signal = SessionCancelSignal()
        assertFalse(signal.isCancelled)
        signal.cancel()
        signal.cancel()
        assertTrue(signal.isCancelled)
    }

    @Test(timeout = 5_000L)
    fun cancelWakesAllSleepersPromptly() {
        val signal = SessionCancelSignal()
        val entered = CountDownLatch(4)
        val results = List(4) { AtomicBoolean(true) }
        val threads =
            results.map { result ->
                Thread {
                    entered.countDown()
                    result.set(signal.sleep(30_000L))
                }.also { it.start() }
            }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        val start = System.nanoTime()
        signal.cancel()
        threads.forEach { it.join(1_000) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("cancel 后 sleep 必须快速返回（实际 ${elapsedMs}ms）", elapsedMs < 150L)
        results.forEach { assertFalse(it.get()) }
        threads.forEach { assertFalse("取消唤醒不得泄漏中断位", it.isInterrupted) }
    }

    @Test(timeout = 5_000L)
    fun cancelWakesAllAwaitersAndLeavesLatchPending() {
        val signal = SessionCancelSignal()
        val never = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val results = List(2) { AtomicBoolean(true) }
        val threads =
            results.map { result ->
                Thread {
                    entered.countDown()
                    result.set(signal.await(never, 30_000L))
                }.also { it.start() }
            }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        signal.cancel()
        threads.forEach { it.join(1_000) }
        results.forEach { assertFalse(it.get()) }
        threads.forEach { assertFalse("await 被信号唤醒后必须清除自身中断位", it.isInterrupted) }
    }

    @Test(timeout = 5_000L)
    fun cancelledEpochNeverRevivesAfterReset() {
        val signal = SessionCancelSignal()
        val oldSleep = AtomicBoolean(true)
        val oldThread = Thread { oldSleep.set(signal.sleep(30_000L)) }
        oldThread.start()
        Thread.sleep(100)
        signal.cancel()
        oldThread.join(1_000)
        assertFalse(oldSleep.get())
        assertTrue(signal.isCancelled)

        signal.reset()
        assertFalse("reset 后新代次必须重新可用", signal.isCancelled)
        assertTrue("新代次正常睡满", signal.sleep(10L))

        // 旧代次线程已结束，不能因 reset 而继续睡；新代次取消仍能独立工作。
        val newSleep = AtomicBoolean(true)
        val newThread = Thread { newSleep.set(signal.sleep(30_000L)) }
        newThread.start()
        Thread.sleep(100)
        signal.cancel()
        newThread.join(1_000)
        assertFalse(newSleep.get())
    }

    @Test(timeout = 5_000L)
    fun resetWhileActiveIsNoOpAndDoesNotWakeSleepers() {
        val signal = SessionCancelSignal()
        val finished = AtomicBoolean(false)
        val sleeper =
            Thread {
                signal.sleep(700L)
                finished.set(true)
            }
        sleeper.start()
        Thread.sleep(100)
        signal.reset()
        assertFalse("B-10：运行中 reset 不得取消或唤醒在途等待", finished.get())
        assertFalse(signal.isCancelled)
        sleeper.join(2_000)
        assertTrue(finished.get())
    }

    @Test(timeout = 5_000L)
    fun awaitClearsOnlySignalInterruptAndPreservesExternalInterrupt() {
        val signal = SessionCancelSignal()
        val never = CountDownLatch(1)
        val signalInterruptLeaked = AtomicBoolean(true)
        val signalThread =
            Thread {
                signal.await(never, 30_000L)
                signalInterruptLeaked.set(Thread.currentThread().isInterrupted)
            }
        signalThread.start()
        Thread.sleep(100)
        signal.cancel()
        signalThread.join(1_000)
        assertFalse("信号中断位必须清除", signalInterruptLeaked.get())

        // 全新信号上的外部 interrupt 必须保留。
        val second = SessionCancelSignal()
        val externallyInterrupted = AtomicBoolean(false)
        val externalThread =
            Thread {
                second.await(never, 30_000L)
                externallyInterrupted.set(Thread.currentThread().isInterrupted)
            }
        externalThread.start()
        Thread.sleep(100)
        externalThread.interrupt()
        externalThread.join(1_000)
        assertTrue("外部 interrupt 必须保留", externallyInterrupted.get())

        // sleep 的外部 interrupt 同样保留，且返回 false。
        val third = SessionCancelSignal()
        val sleepInterrupted = AtomicBoolean(false)
        val sleepThread =
            Thread {
                val ok = third.sleep(30_000L)
                assertFalse(ok)
                sleepInterrupted.set(Thread.currentThread().isInterrupted)
            }
        sleepThread.start()
        Thread.sleep(100)
        sleepThread.interrupt()
        sleepThread.join(1_000)
        assertTrue("sleep 的外部 interrupt 必须保留", sleepInterrupted.get())
    }

    @Test(timeout = 5_000L)
    fun alreadyCompletedLatchReturnsTrueAndCancelledLatchReturnsFalseImmediately() {
        val signal = SessionCancelSignal()
        val done = CountDownLatch(1)
        done.countDown()
        assertTrue(signal.await(done, 30_000L))

        signal.cancel()
        assertFalse(signal.await(CountDownLatch(1), 30_000L))
        assertFalse(signal.sleep(30_000L))
    }
}
