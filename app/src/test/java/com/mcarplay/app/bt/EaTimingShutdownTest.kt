package com.mcarplay.app.bt

import com.mcarplay.app.architecture.KotlinSource
import com.mcarplay.app.architecture.SourceTree
import com.mcarplay.app.bt.EaTimingShutdown.Dispatcher
import com.mcarplay.app.bt.EaTimingShutdown.Sender
import com.mcarplay.app.bt.EaTimingShutdown.Sleeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** 用真实生产 helper 验证 EA 退役的短路、发送、条件冲刷、close 与异常边界。 */
class EaTimingShutdownTest {
    private class RecordingSender(
        hasResult: Boolean,
        stopResult: Int,
        stopError: Throwable? = null,
        private val sharedEvents: ArrayList<String> = ArrayList(),
    ) : Sender {
        private val hasResultState = AtomicBoolean(hasResult)
        private val stopResultState = AtomicInteger(stopResult)
        private val stopErrorState = AtomicReference<Throwable?>(stopError)
        private val hasCount = AtomicInteger()
        private val stopCount = AtomicInteger()
        private val stopReasons = ArrayList<String>()

        override fun hasSessionsToStop(): Boolean {
            hasCount.incrementAndGet()
            return hasResultState.get()
        }

        override fun stop(reason: String): Int {
            stopCount.incrementAndGet()
            synchronized(stopReasons) { stopReasons.add(reason) }
            sharedEvents.add("stop")
            stopErrorState.getAndSet(null)?.let { error -> throw error }
            return stopResultState.get()
        }

        fun hasCalls(): Int = hasCount.get()

        fun stopCalls(): Int = stopCount.get()

        fun stopReasonAt(index: Int): String {
            synchronized(stopReasons) {
                assertTrue("stop reason index 越界", index in stopReasons.indices)
                return stopReasons[index]
            }
        }
    }

    private class RecordingSleeper : Sleeper {
        val requests = ArrayList<Long>()

        override fun sleep(millis: Long) {
            synchronized(requests) { requests.add(millis) }
        }
    }

    private class ControlledDispatcher : Dispatcher {
        val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(
            name: String,
            task: () -> Unit,
        ) {
            names.add(name)
            tasks.put(task)
        }

        val names = ArrayList<String>()
    }

    private class OrderedAdapter(
        private val reason: String,
        private val sender: Sender,
        private val events: ArrayList<String>,
    ) {
        fun run(
            dispatcher: Dispatcher,
            sleeper: Sleeper,
        ) {
            val shutdown =
                EaTimingShutdown(
                    reason = reason,
                    closeLink = { synchronized(events) { events.add("close") } },
                )
            shutdown.run(
                session = sender,
                dispatcher = dispatcher,
                sleeper = { millis ->
                    synchronized(events) { events.add("sleep:$millis") }
                    sleeper.sleep(millis)
                },
            )
        }
    }

    private fun runTask(task: Runnable) {
        task.run()
    }

    private fun awaitTrue(action: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (action()) return
        }
        assertTrue("状态未在 2 秒内达成", action())
    }

    @Test
    fun nullReasonShortCircuitsCloseOnCallingThread() {
        val sender = RecordingSender(hasResult = true, stopResult = 1)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0
        val shutdown = EaTimingShutdown(reason = null, closeLink = { closeCount++ })

        shutdown.run(sender, dispatcher, sleeper)

        assertEquals(1, closeCount)
        assertEquals(0, dispatcher.names.size)
        assertTrue(dispatcher.tasks.isEmpty())
        assertEquals(0, sender.hasCalls())
        assertEquals(0, sender.stopCalls())
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun nullSessionShortCircuitsCloseOnCallingThread() {
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })

        shutdown.run(null, dispatcher, sleeper)

        assertEquals(1, closeCount)
        assertEquals(0, dispatcher.names.size)
        assertTrue(dispatcher.tasks.isEmpty())
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun falseHasSessionsShortCircuitsAfterSingleEntryRead() {
        val sender = RecordingSender(hasResult = false, stopResult = 0)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })

        shutdown.run(sender, dispatcher, sleeper)

        assertEquals(1, closeCount)
        assertEquals(0, dispatcher.names.size)
        assertTrue(dispatcher.tasks.isEmpty())
        assertEquals(1, sender.hasCalls())
        assertEquals(0, sender.stopCalls())
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun emptyReasonIsTransparentThroughEntryAndStop() {
        val sender = RecordingSender(hasResult = true, stopResult = 1)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0
        val shutdown = EaTimingShutdown(reason = "", closeLink = { closeCount++ })

        shutdown.run(sender, dispatcher, sleeper)

        assertEquals(listOf("iap2-ea-stop"), dispatcher.names)
        assertEquals(0, closeCount)
        assertEquals(1, sender.hasCalls())
        runTask(dispatcher.tasks.poll()!!)
        assertEquals("", sender.stopReasonAt(0))
        assertEquals(listOf(200L), sleeper.requests)
        assertEquals(1, closeCount)
        assertEquals(1, sender.stopCalls())
    }

    @Test
    fun zeroSentRunsTaskWithoutFlushAndClosesExactlyOnce() {
        val sender = RecordingSender(hasResult = true, stopResult = 0)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })

        shutdown.run(sender, dispatcher, sleeper)

        assertEquals(listOf("iap2-ea-stop"), dispatcher.names)
        assertEquals(1, dispatcher.tasks.size)
        assertEquals(0, closeCount)
        assertEquals(1, sender.hasCalls())
        runTask(dispatcher.tasks.poll()!!)
        assertEquals("disconnect", sender.stopReasonAt(0))
        assertEquals(0, sleeper.requests.size)
        assertEquals(1, closeCount)
        assertEquals(1, sender.stopCalls())
    }

    @Test
    fun positiveSentObservesStopThenFlushThenClose() {
        val events = ArrayList<String>()
        val sender = RecordingSender(sharedEvents = events, hasResult = true, stopResult = 2)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        val adapter = OrderedAdapter("空端点等待超时", sender, events)

        adapter.run(dispatcher, sleeper)

        assertEquals(0, events.size)
        assertEquals(1, dispatcher.tasks.size)
        runTask(dispatcher.tasks.poll()!!)
        assertEquals(listOf("stop", "sleep:200", "close"), events)
        assertEquals("空端点等待超时", sender.stopReasonAt(0))
        assertEquals(listOf(200L), sleeper.requests)
        assertEquals(1, sender.stopCalls())
    }

    @Test
    fun shortCircuitRunsOnCallingThreadAndHoldsExistingMonitor() {
        val sender = RecordingSender(hasResult = false, stopResult = 0)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        val monitor = ReentrantLock()
        val closeCaller = AtomicReference<Thread?>()
        val closeHeldMonitor = AtomicBoolean(false)

        val called =
            monitor.withLock {
                EaTimingShutdown(reason = "disconnect", closeLink = {
                    closeCaller.set(Thread.currentThread())
                    closeHeldMonitor.set(monitor.isHeldByCurrentThread)
                }).run(sender, dispatcher, sleeper)
                true
            }

        assertTrue(called)
        assertEquals(Thread.currentThread(), closeCaller.get())
        assertTrue(closeHeldMonitor.get())
        assertEquals(0, dispatcher.names.size)
        assertTrue(dispatcher.tasks.isEmpty())
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun interruptFromStopRestoresFlagAndCloses() {
        val sender = RecordingSender(hasResult = true, stopResult = 0, stopError = InterruptedException())
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })

        shutdown.run(sender, dispatcher, sleeper)

        val wasInterrupted = AtomicBoolean(false)
        val task = dispatcher.tasks.poll()!!
        val worker =
            thread(start = true) {
                wasInterrupted.set(Thread.currentThread().isInterrupted)
                runTask(task)
                wasInterrupted.set(Thread.currentThread().isInterrupted)
            }
        worker.join(TimeUnit.SECONDS.toMillis(2))
        assertFalse(worker.isAlive)
        assertTrue(wasInterrupted.get())
        assertEquals(1, closeCount)
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun interruptFromSleepRestoresFlagAndCloses() {
        val sender = RecordingSender(hasResult = true, stopResult = 1)
        val dispatcher = ControlledDispatcher()
        val sleeper =
            object : Sleeper {
                override fun sleep(millis: Long) {
                    assertEquals(EA_STOP_FLUSH_MILLIS, millis)
                    throw InterruptedException()
                }
            }
        var closeCount = 0
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })

        shutdown.run(sender, dispatcher, sleeper)

        val wasInterrupted = AtomicBoolean(false)
        val task = dispatcher.tasks.poll()!!
        val worker =
            thread(start = true) {
                wasInterrupted.set(Thread.currentThread().isInterrupted)
                runTask(task)
                wasInterrupted.set(Thread.currentThread().isInterrupted)
            }
        worker.join(TimeUnit.SECONDS.toMillis(2))
        assertFalse(worker.isAlive)
        assertTrue(wasInterrupted.get())
        assertEquals(1, closeCount)
    }

    @Test
    fun closeFailureOverridesStopFailure() {
        val stopError = IllegalStateException("stop failed")
        val sender = RecordingSender(hasResult = true, stopResult = 0, stopError = stopError)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        val closeError = IllegalStateException("close failed")
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { throw closeError })

        shutdown.run(sender, dispatcher, sleeper)

        val task = dispatcher.tasks.poll()!!
        val thrown = runCatching { runTask(task) }.exceptionOrNull()
        assertSame(closeError, thrown)
        assertEquals(1, sender.stopCalls())
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun closeFailureOverridesSleeperFailure() {
        val sender = RecordingSender(hasResult = true, stopResult = 1)
        val dispatcher = ControlledDispatcher()
        val sleeper =
            object : Sleeper {
                override fun sleep(millis: Long) {
                    assertEquals(EA_STOP_FLUSH_MILLIS, millis)
                    throw IllegalStateException("sleep failed")
                }
            }
        val closeError = IllegalStateException("close failed")
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { throw closeError })

        shutdown.run(sender, dispatcher, sleeper)

        val task = dispatcher.tasks.poll()!!
        val thrown = runCatching { runTask(task) }.exceptionOrNull()
        assertSame(closeError, thrown)
        assertEquals(1, sender.stopCalls())
    }

    @Test
    fun dispatchSubmissionFailureDoesNotClose() {
        val sender = RecordingSender(hasResult = true, stopResult = 1)
        val sleeper = RecordingSleeper()
        var closeCount = 0
        val submitError = IllegalStateException("submit failed")
        val dispatcher =
            object : Dispatcher {
                override fun dispatch(
                    name: String,
                    task: () -> Unit,
                ) {
                    assertEquals("iap2-ea-stop", name)
                    throw submitError
                }
            }
        val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })

        val thrown = runCatching { shutdown.run(sender, dispatcher, sleeper) }.exceptionOrNull()

        assertSame(submitError, thrown)
        assertEquals(0, closeCount)
        assertEquals(1, sender.hasCalls())
        assertEquals(0, sender.stopCalls())
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun duplicateCallsHaveNoSharedCloseOnceToken() {
        val sender = RecordingSender(hasResult = true, stopResult = 1)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0

        repeat(2) {
            val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })
            shutdown.run(sender, dispatcher, sleeper)
        }

        assertEquals(2, dispatcher.tasks.size)
        assertEquals(0, closeCount)
        assertEquals(2, sender.hasCalls())
        repeat(2) {
            runTask(dispatcher.tasks.poll()!!)
        }
        assertEquals(2, closeCount)
        assertEquals(2, sender.stopCalls())
        assertEquals(listOf(200L, 200L), sleeper.requests)
    }

    @Test
    fun staleTaskIsIsolatedFromNewSession() {
        val oldSender = RecordingSender(hasResult = true, stopResult = 1)
        val newSender = RecordingSender(hasResult = false, stopResult = 0)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        val oldClosed = CountDownLatch(1)
        val newClosed = CountDownLatch(1)
        val oldShutdown = EaTimingShutdown(reason = "disconnect", closeLink = { oldClosed.countDown() })
        val newShutdown = EaTimingShutdown(reason = "disconnect", closeLink = { newClosed.countDown() })

        oldShutdown.run(oldSender, dispatcher, sleeper)
        assertEquals(1, dispatcher.tasks.size)
        val staleTask = dispatcher.tasks.poll()!!
        newShutdown.run(newSender, dispatcher, sleeper)
        assertEquals(0, newClosed.count)
        assertEquals(1, oldClosed.count)

        val worker = thread(start = true) { runTask(staleTask) }
        worker.join(TimeUnit.SECONDS.toMillis(2))
        assertFalse(worker.isAlive)
        assertTrue(oldClosed.await(1, TimeUnit.SECONDS))
        assertEquals("disconnect", oldSender.stopReasonAt(0))
        assertEquals(1, oldSender.stopCalls())
        assertEquals(1, newSender.hasCalls())
        assertEquals(0, newSender.stopCalls())
        assertEquals(listOf(200L), sleeper.requests)
    }

    @Test
    fun staleOldTaskAndNewShortCircuitCanInterleave() {
        val oldSender = RecordingSender(hasResult = true, stopResult = 1)
        val newSender = RecordingSender(hasResult = false, stopResult = 0)
        val sleeper = RecordingSleeper()
        val oldClosed = CountDownLatch(1)
        val newClosed = CountDownLatch(1)
        val oldShutdown = EaTimingShutdown(reason = "old", closeLink = { oldClosed.countDown() })
        val newShutdown = EaTimingShutdown(reason = "new", closeLink = { newClosed.countDown() })
        val beforeOldStop = CountDownLatch(1)
        val afterOldStop = CountDownLatch(1)
        val newHasEntered = CountDownLatch(1)
        val oldStopStarted = AtomicBoolean(false)
        val newHasCompleted = AtomicBoolean(false)
        val oldDispatcher =
            object : Dispatcher {
                override fun dispatch(
                    name: String,
                    task: () -> Unit,
                ) {
                    assertEquals("iap2-ea-stop", name)
                    thread {
                        beforeOldStop.await(2, TimeUnit.SECONDS)
                        oldStopStarted.set(true)
                        try {
                            task()
                        } finally {
                            afterOldStop.countDown()
                        }
                    }
                }
            }

        oldShutdown.run(oldSender, oldDispatcher, sleeper)
        val newWorker =
            thread {
                newHasEntered.countDown()
                newShutdown.run(newSender, ControlledDispatcher(), sleeper)
                newHasCompleted.set(true)
            }
        newHasEntered.await(2, TimeUnit.SECONDS)
        beforeOldStop.countDown()
        awaitTrue { oldStopStarted.get() && newHasCompleted.get() }
        afterOldStop.await(2, TimeUnit.SECONDS)
        newWorker.join(TimeUnit.SECONDS.toMillis(2))
        assertFalse(newWorker.isAlive)
        assertTrue(oldClosed.await(2, TimeUnit.SECONDS))
        assertTrue(newClosed.await(2, TimeUnit.SECONDS))
        assertEquals("old", oldSender.stopReasonAt(0))
        assertEquals(1, oldSender.stopCalls())
        assertEquals(1, newSender.hasCalls())
        assertEquals(0, newSender.stopCalls())
    }

    @Test
    fun zeroSentSecondRetireStillEntersBackgroundAndCloses() {
        val sender = RecordingSender(hasResult = true, stopResult = 0)
        val dispatcher = ControlledDispatcher()
        val sleeper = RecordingSleeper()
        var closeCount = 0

        repeat(2) {
            val shutdown = EaTimingShutdown(reason = "disconnect", closeLink = { closeCount++ })
            shutdown.run(sender, dispatcher, sleeper)
            runTask(dispatcher.tasks.poll()!!)
        }

        assertEquals(2, dispatcher.names.size)
        assertEquals(2, closeCount)
        assertEquals(2, sender.hasCalls())
        assertEquals(2, sender.stopCalls())
        assertEquals("disconnect", sender.stopReasonAt(0))
        assertEquals("disconnect", sender.stopReasonAt(1))
        assertEquals(0, sleeper.requests.size)
    }

    @Test
    fun wiringKeepsProductionEntryAndCallSites() {
        val btSession = SourceTree.mainKotlinFiles.getValue("bt/BtSession.kt").readText()
        val helper = SourceTree.mainKotlinFiles.getValue("bt/EaTimingShutdown.kt").readText()
        val server = SourceTree.mainKotlinFiles.getValue("bt/Iap2BluetoothServer.kt").readText()
        val client = SourceTree.mainKotlinFiles.getValue("bt/Iap2BluetoothClient.kt").readText()
        val helperCode = KotlinSource.scan(helper).code
        val shutdownCode = KotlinSource.scan(btSession).code
        val shutdownCodeNormalized = shutdownCode.replace(Regex("\\s+"), " ")
        val helperCodeNormalized = helperCode.replace(Regex("\\s+"), " ")
        val adapterAndWrapper = shutdownCode.substringAfter("internal fun retireControlLink(")
        val adapterAndWrapperNormalized = adapterAndWrapper.replace(Regex("\\s+"), " ")
        val runStart = helperCodeNormalized.indexOf("internal fun run(")
        val runBodyStart = helperCodeNormalized.indexOf("{", runStart)
        val runBodyEnd = helperCodeNormalized.indexOf("} } } }", runBodyStart)
        val runBody = helperCodeNormalized.substring(runBodyStart, runBodyEnd + 7)
        val actualSessionHasReads =
            Regex("""session\?\.hasSessionsToStop\(\)|!session\.hasSessionsToStop\(\)""")
                .findAll(helperCodeNormalized)
                .map { it.value }
                .filterNot { it == "session?.hasSessionsToStop()" }
                .count()

        assertTrue(shutdownCode.contains("EaTimingShutdown("))
        assertTrue(shutdownCode.contains("reason = eaStopReason"))
        assertTrue(shutdownCode.contains("session?.toEaStopSender()"))
        assertFalse(helperCodeNormalized.contains("Bg.run("))
        assertFalse(helperCode.contains("synchronized"))
        assertFalse(helperCode.contains("ReentrantLock"))
        assertEquals(1, actualSessionHasReads)
        assertTrue(runBody.contains("!session.hasSessionsToStop()"))
        assertTrue(runBody.contains("session.stop(reason)"))
        assertTrue(runBody.contains("sleeper.sleep(EA_STOP_FLUSH_MILLIS)"))
        assertTrue(shutdownCodeNormalized.contains("BT_EA_STOP_TASK_NAME") || shutdownCodeNormalized.contains("iap2-ea-stop"))
        assertTrue(adapterAndWrapperNormalized.contains("session?.toEaStopSender()"))
        assertTrue(adapterAndWrapperNormalized.contains("dispatcher = { name, task -> Bg.run(name, task) }"))
        assertTrue(adapterAndWrapperNormalized.contains("sleeper = { millis -> Thread.sleep(millis) }"))
        assertTrue(server.contains("retireControlLink(session, eaStopReason)"))
        assertTrue(server.contains("channel.close()"))
        assertTrue(client.contains("retireControlLink(session, eaStopReason)"))
    }
}
