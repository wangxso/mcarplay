package com.mcarplay.app.audio

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
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val starts = AtomicInteger()
        try {
            val first =
                pool.submit {
                    assertTrue(gate.tryAcquire())
                    starts.incrementAndGet()
                    acquired.countDown()
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    } finally {
                        gate.release()
                    }
                }
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Boolean> { gate.tryAcquire() }
            assertFalse("the second entry must finish while SETUP is paused", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertTrue(gate.tryAcquire())
            gate.release()
        } finally {
            release.countDown()
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
