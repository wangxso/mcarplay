package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 队满补要关键帧的限流与线程模型。
 *
 * 关键约束：`signal()` 由 CarLife 读线程调用，返回前**不能**执行网络请求；
 * 请求必须在后台线程上、且 1 s 内最多一次（容量 8 会把队满变得更频繁，否则要帧风暴）。
 */
class OverflowKeyFrameRecoveryTest {
    private fun recovery(
        request: () -> Boolean,
        intervalMillis: Long = 1_000L,
        now: () -> Long = { 0L },
        onRequest: (Boolean) -> Unit = {},
    ) = OverflowKeyFrameRecovery(request = request, intervalMillis = intervalMillis, nowMillis = now, onRequest = onRequest)

    @Test
    fun signalNeverRequestsOnTheCallingThread() {
        val requests = AtomicInteger()
        val lastCaller = arrayOfNulls<String>(1)
        val caller = Thread.currentThread().name
        val recovery =
            recovery(
                request = {
                    requests.incrementAndGet()
                    lastCaller[0] = Thread.currentThread().name
                    true
                },
            )
        recovery.start()
        try {
            assertTrue(recovery.signal())
            awaitCondition { requests.get() == 1 }
            assertFalse("request must not run on the CarLife reader thread", caller == lastCaller[0])
            assertTrue("request must run on the recovery worker", lastCaller[0]!!.contains("video-overflow-keyframe"))
        } finally {
            recovery.stop()
        }
    }

    @Test
    fun repeatedOverflowWithinTheWindowIsThrottled() {
        var now = 0L
        val requests = AtomicInteger()
        val recovery =
            recovery(request = {
                requests.incrementAndGet()
                true
            }, now = { now })
        recovery.start()
        try {
            assertTrue(recovery.signal())
            awaitCondition { requests.get() == 1 }
            assertFalse("the same window must not dispatch a second request", recovery.signal())
            assertFalse("400 ms is still inside the 1 s window", recovery.signal(now = 400))
            assertFalse(recovery.signal(now = 999))
            assertTrue("after the interval a new request is allowed", recovery.signal(now = 1_000))
            awaitCondition { requests.get() == 2 }
            assertEquals(2, requests.get())
        } finally {
            recovery.stop()
        }
    }

    @Test
    fun aFailingRequestIsReportedButDoesNotKillTheWorker() {
        val outcomes = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val attempts = AtomicInteger()
        val recovery =
            recovery(
                request = {
                    when (attempts.incrementAndGet()) {
                        1 -> throw IllegalStateException("socket gone")
                        else -> true
                    }
                },
                onRequest = { outcomes.add(it) },
            )
        recovery.start()
        try {
            assertTrue(recovery.signal())
            awaitCondition { outcomes.size == 1 }
            assertTrue(recovery.signal(now = 1_000))
            awaitCondition { attempts.get() == 2 }
            awaitCondition { outcomes.size == 2 }
            assertEquals(listOf(false, true), outcomes)
        } finally {
            recovery.stop()
        }
    }

    @Test
    fun stopPreventsFurtherRequestsAndEndsTheWorker() {
        var now = 0L
        val requests = AtomicInteger()
        val recovery =
            recovery(request = {
                requests.incrementAndGet()
                true
            }, now = { now })
        recovery.start()
        assertTrue(recovery.signal())
        awaitCondition { requests.get() == 1 }

        val worker = threadNamed("video-overflow-keyframe")
        recovery.stop()
        awaitCondition { worker?.isAlive == false }

        now = 10_000
        assertFalse("a stopped recovery must not dispatch", recovery.signal())
        Thread.sleep(100)
        assertEquals(1, requests.get())
    }

    @Test
    fun signalBeforeStartIsIgnored() {
        val requests = AtomicInteger()
        val recovery =
            recovery(request = {
                requests.incrementAndGet()
                true
            })
        assertFalse(recovery.signal())
        Thread.sleep(50)
        assertEquals(0, requests.get())
        recovery.stop()
    }

    @Test
    fun stopIsIdempotent() {
        val recovery = recovery(request = { true })
        recovery.start()
        recovery.stop()
        recovery.stop()
    }

    private fun threadNamed(name: String): Thread? = Thread.getAllStackTraces().keys.firstOrNull { it.name == name }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline && !condition()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for recovery state", condition())
    }
}
