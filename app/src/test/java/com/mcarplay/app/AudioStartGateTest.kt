package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * requestUI 重进与 changeModes 归还可能同时调度音频重试。音频 SETUP 可持续数秒，
 * 期间 `audioSender` 尚未赋值；没有 single-flight 时两个入口都会建立流，后写入者
 * 覆盖先前的引用，另一条流仍启动却永远无法停止（双音频流 + 持续泄漏）。
 */
class AudioStartGateTest {
    @Test
    fun concurrentRequestUiAndChangeModesRetriesStartOnlyOneAudioChannel() {
        val gate = AudioStartGate()
        val ready = CountDownLatch(1)
        val done = CountDownLatch(2)
        val starts = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2) {
                pool.execute {
                    ready.await()
                    if (gate.tryAcquire()) {
                        starts.incrementAndGet()
                        try {
                            // 模拟 SETUP/协商窗口：此时 audioSender 仍未赋值。
                            Thread.sleep(100)
                        } finally {
                            gate.release()
                        }
                    }
                    done.countDown()
                }
            }
            ready.countDown()
            assertTrue("both entry points must finish", done.await(5, TimeUnit.SECONDS))
            assertEquals("only one entry point may establish an audio channel", 1, starts.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun gateRejectsAStartWhileSetupIsInFlightAndAllowsALaterRetry() {
        val gate = AudioStartGate()
        assertTrue(gate.tryAcquire())
        assertFalse("a second entry point must not start during SETUP", gate.tryAcquire())
        gate.release()
        assertTrue("a later retry may start after the first finished", gate.tryAcquire())
        gate.release()
        assertTrue("the gate must be reusable", gate.tryAcquire())
    }
}
