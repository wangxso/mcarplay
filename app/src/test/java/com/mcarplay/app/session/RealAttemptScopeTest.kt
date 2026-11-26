package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** J 层单元测试：[RealAttemptScope] 的状态单调性、资源清理与可打断等待语义。 */
class RealAttemptScopeTest {
    private val obs = ReceiverObservation(binding = "binding-1", epoch = 2, session = 5)

    @Test
    fun initialStateIsCurrentActiveAndHasNoCancelReason() {
        val scope = RealAttemptScope(id = "attempt-1", observation = obs)
        assertEquals("attempt-1", scope.id)
        assertSame(obs, scope.observation)
        assertTrue(scope.isCurrent)
        assertTrue(scope.isActive)
        assertNull(scope.cancelReason)
    }

    @Test
    fun cancelKeepsIsCurrentTrueWhileClearingIsActiveAndRecordingReason() {
        val scope = RealAttemptScope(id = "attempt-2", observation = obs)
        scope.cancel(DiagnosticCode.USER_STOP)
        assertFalse("取消后 isActive 必须为 false", scope.isActive)
        assertTrue("取消后仍须保持 isCurrent=true 以负责释放交接与记诊断", scope.isCurrent)
        assertEquals(DiagnosticCode.USER_STOP, scope.cancelReason)
    }

    @Test
    fun repeatedCancelIsIdempotentAndPreservesFirstReason() {
        val scope = RealAttemptScope(id = "attempt-3", observation = obs)
        scope.cancel(DiagnosticCode.USER_STOP)
        scope.cancel(DiagnosticCode.BT_LINK_CLOSED)
        scope.cancel(DiagnosticCode.SUPERSEDED)
        assertFalse(scope.isActive)
        assertTrue(scope.isCurrent)
        assertEquals("首次取消原因必须胜出", DiagnosticCode.USER_STOP, scope.cancelReason)
    }

    @Test
    fun supersedeClearsBothIsCurrentAndIsActiveAndNeverRevivesOnCancel() {
        val scope = RealAttemptScope(id = "attempt-4", observation = obs)
        scope.supersede()
        assertFalse(scope.isCurrent)
        assertFalse(scope.isActive)
        assertNull(scope.cancelReason)

        scope.cancel(DiagnosticCode.BT_LINK_CLOSED)
        assertFalse(scope.isCurrent)
        assertFalse(scope.isActive)
        assertEquals(DiagnosticCode.BT_LINK_CLOSED, scope.cancelReason)
    }

    @Test
    fun checkActiveThrowsAttemptCancelledWithExpectedReason() {
        val active = RealAttemptScope(id = "a-active", observation = obs)
        active.checkActive()

        val cancelled = RealAttemptScope(id = "a-cancelled", observation = obs)
        cancelled.cancel(DiagnosticCode.BT_LINK_CLOSED)
        try {
            cancelled.checkActive()
            fail("取消后 checkActive() 必须抛出 AttemptCancelled")
        } catch (ex: AttemptCancelled) {
            assertEquals(DiagnosticCode.BT_LINK_CLOSED, ex.reason)
        }

        val superseded = RealAttemptScope(id = "a-superseded", observation = obs)
        superseded.supersede()
        try {
            superseded.checkActive()
            fail("取代后 checkActive() 必须抛出 AttemptCancelled")
        } catch (ex: AttemptCancelled) {
            assertNull(ex.reason)
        }
    }

    @Test
    fun ownClosesResourcesInLifoOrderExactlyOnceOnCancel() {
        val scope = RealAttemptScope(id = "attempt-lifo", observation = obs)
        val order = mutableListOf<String>()
        val r1Count = AtomicInteger(0)
        val r2Count = AtomicInteger(0)
        val r1 =
            AutoCloseable {
                r1Count.incrementAndGet()
                order.add("r1")
            }
        val r2 =
            AutoCloseable {
                r2Count.incrementAndGet()
                order.add("r2")
            }

        assertSame(r1, scope.own(r1))
        assertSame(r2, scope.own(r2))
        assertTrue(order.isEmpty())

        scope.cancel(DiagnosticCode.USER_STOP)
        scope.cancel(DiagnosticCode.BT_LINK_CLOSED)
        scope.supersede()

        assertEquals(listOf("r2", "r1"), order)
        assertEquals(1, r1Count.get())
        assertEquals(1, r2Count.get())
    }

    @Test
    fun ownCloseExceptionDoesNotBlockRemainingResources() {
        val scope = RealAttemptScope(id = "attempt-err", observation = obs)
        val closed = mutableListOf<String>()
        scope.own(AutoCloseable { closed.add("r1") })
        scope.own(
            AutoCloseable {
                closed.add("r2-throw")
                throw IllegalStateException("simulated close failure")
            },
        )
        scope.own(AutoCloseable { closed.add("r3") })

        scope.cancel(DiagnosticCode.USER_STOP)
        assertEquals(listOf("r3", "r2-throw", "r1"), closed)
    }

    @Test
    fun ownAfterCancelClosesImmediatelyAndSupersedeDoesNotDoubleClose() {
        val scope = RealAttemptScope(id = "attempt-late-own", observation = obs)
        scope.cancel(DiagnosticCode.USER_STOP)

        val r3Count = AtomicInteger(0)
        val r3 = AutoCloseable { r3Count.incrementAndGet() }
        assertSame(r3, scope.own(r3))
        assertEquals("已取消后登记的资源必须立即关闭", 1, r3Count.get())

        scope.supersede()
        assertEquals("后续 supersede 不得二次关闭已登记资源", 1, r3Count.get())
    }

    @Test
    fun ownAfterSupersedeClosesImmediatelyAndCancelDoesNotDoubleClose() {
        val scope = RealAttemptScope(id = "attempt-superseded-own", observation = obs)
        scope.supersede()

        val count = AtomicInteger(0)
        scope.own(AutoCloseable { count.incrementAndGet() })
        assertEquals(1, count.get())

        scope.cancel(DiagnosticCode.USER_STOP)
        assertEquals(1, count.get())
    }

    @Test
    fun sleepWakesPromptlyOnCancelWithoutLeakingInterruptFlag() {
        val scope = RealAttemptScope(id = "attempt-sleep-cancel", observation = obs)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val interruptedAfter = AtomicBoolean(true)

        val worker =
            Thread(
                {
                    entered.countDown()
                    result.set(scope.sleep(5_000L))
                    interruptedAfter.set(Thread.currentThread().isInterrupted)
                    finished.countDown()
                },
                "test-real-scope-sleep",
            )
        worker.start()

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(20L)
        val cancelAt = System.nanoTime()
        scope.cancel(DiagnosticCode.USER_STOP)

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt)
        assertEquals(false, result.get())
        assertTrue("取消后 sleep 应在 <150ms 内唤醒，实际=${elapsedMs}ms", elapsedMs < 150L)
        assertFalse("取消唤醒 sleep 不得污染工作线程中断位", interruptedAfter.get())
        worker.join(2_000L)
    }

    @Test
    fun sleepReturnsTrueOnNormalExpiryAndFalseImmediatelyWhenAlreadyCancelled() {
        val scope = RealAttemptScope(id = "attempt-sleep-normal", observation = obs)
        assertTrue(scope.sleep(0L))
        assertTrue(scope.sleep(10L))

        scope.cancel(DiagnosticCode.USER_STOP)
        val start = System.nanoTime()
        assertFalse(scope.sleep(5_000L))
        assertFalse(scope.sleep(0L))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("已取消时 sleep 必须立即返回，实际=${elapsedMs}ms", elapsedMs < 100L)
    }

    @Test
    fun awaitReturnsTrueWhenLatchCountsDownAndFalseOnTimeout() {
        val scope = RealAttemptScope(id = "attempt-await-basic", observation = obs)
        val alreadyZero = CountDownLatch(0)
        assertTrue(scope.await(alreadyZero, 0L))

        val timedOut = CountDownLatch(1)
        assertFalse(scope.await(timedOut, 15L))

        val countdown = CountDownLatch(1)
        val trigger =
            Thread {
                Thread.sleep(15L)
                countdown.countDown()
            }
        trigger.start()
        assertTrue(scope.await(countdown, 2_000L))
        trigger.join(2_000L)

        scope.cancel(DiagnosticCode.USER_STOP)
        assertFalse("已取消时即使 latch 已归零也必须返回 false", scope.await(alreadyZero, 1_000L))
    }

    @Test
    fun awaitWakesPromptlyOnCancelWithoutLeakingInterruptFlag() {
        val scope = RealAttemptScope(id = "attempt-await-cancel", observation = obs)
        val never = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val interruptedAfter = AtomicBoolean(true)

        val worker =
            Thread(
                {
                    entered.countDown()
                    result.set(scope.await(never, 5_000L))
                    interruptedAfter.set(Thread.currentThread().isInterrupted)
                    finished.countDown()
                },
                "test-real-scope-await",
            )
        worker.start()

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(20L)
        val cancelAt = System.nanoTime()
        scope.cancel(DiagnosticCode.BT_LINK_CLOSED)

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt)
        assertEquals(false, result.get())
        assertTrue("取消后 await 应在 <150ms 内唤醒，实际=${elapsedMs}ms", elapsedMs < 150L)
        assertFalse("scope 取消打断 await 后必须清除中断位", interruptedAfter.get())
        worker.join(2_000L)
    }

    @Test
    fun awaitPreservesExternalInterruptFlagAndReturnsFalse() {
        val scope = RealAttemptScope(id = "attempt-await-ext-int", observation = obs)
        val never = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val interruptedAfter = AtomicBoolean(false)

        val worker =
            Thread(
                {
                    entered.countDown()
                    result.set(scope.await(never, 5_000L))
                    interruptedAfter.set(Thread.currentThread().isInterrupted)
                    // 清理测试线程中断位，避免影响 JVM 线程回收。
                    Thread.interrupted()
                    finished.countDown()
                },
                "test-real-scope-ext-interrupt",
            )
        worker.start()

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(20L)
        worker.interrupt()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(false, result.get())
        assertTrue("外部直接 interrupt 必须保留线程中断位", interruptedAfter.get())
        worker.join(2_000L)
    }
}
