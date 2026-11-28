package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TeardownCoordinationTest {
    @Test
    fun p1CompleteMatchesIdentityOutsideMonitor() {
        withFixture { fixture ->
            val completed = SignalPauseLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.register(completed)

            val completer = fixture.startWorker { fixture.complete(completed) }
            assertTrue("signal pause not reached", completed.signalEntered.await(5, TimeUnit.SECONDS))
            val signalEntry = fixture.events.snapshot.last { it.phase == Phase.SIGNAL_ENTER }
            assertFalse("countDown entry must not hold owner monitor", signalEntry.holdsMonitor == true)
            assertNull("slot must clear before signal", fixture.state.completionWithoutReading())
            assertEquals(1L, completed.count)

            completed.releaseSignal()
            completer.joinOrThrow()

            assertEquals(0L, completed.count)
            fixture.events.assertOrder(
                Event(Phase.GATE_SET, "false"),
                Event(Phase.COMPLETION_READ, "A"),
                Event(Phase.ALLOW, "A"),
                Event(Phase.COMPLETION_CLEAR, null),
                Event(Phase.SIGNAL_ENTER, "A"),
                Event(Phase.SIGNAL_REAL, "A"),
                Event(Phase.LOG_DONE, "A"),
            )
            assertEquals(1, fixture.events.count(Phase.ALLOW))
            assertEquals(1, fixture.events.count(Phase.COMPLETION_CLEAR))
        }
    }

    @Test
    fun p2DeferNullAndStoppingBranch() {
        withFixture { fixture ->
            fixture.state.generationValue = 5L
            var retried = false
            var timedOut = false

            val nullResult =
                fixture.deferStartIfStopping(
                    retry = { retried = true },
                    logTimeout = { timedOut = true },
                )

            assertFalse(nullResult)
            assertFalse(retried)
            assertFalse(timedOut)
            assertTrue(fixture.tasks.isEmpty)
            assertEquals(1, fixture.events.count(Phase.COMPLETION_READ))
            assertEquals(0, fixture.events.count(Phase.GENERATION_READ))

            val stopping = CountDownLatch(1)
            fixture.state.completion = stopping
            val stoppingResult =
                fixture.deferStartIfStopping(
                    retry = { retried = true },
                    logTimeout = { timedOut = true },
                )

            assertTrue(stoppingResult)
            assertFalse(retried)
            assertFalse(timedOut)
            val task = fixture.tasks.single()
            assertEquals("connect-after-disconnect", task.name)
            fixture.events.assertOrder(
                Event(Phase.COMPLETION_READ, null),
                Event(Phase.GENERATION_READ, "5"),
                Event(Phase.TASK_SUBMIT, "connect-after-disconnect"),
            )
        }
    }

    @Test
    fun p3UnchangedGenerationRetriesAfterRealAwait() {
        withFixture { fixture ->
            fixture.state.generationValue = 1L
            val completed = SuccessPauseLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.register(completed)
            fixture.deferStartIfStopping(
                retry = fixture.retryingCallback(),
                logTimeout = fixture.recordingTimeout(),
            )

            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completed.superAwaitEntered.await(5, TimeUnit.SECONDS))

            val completer = fixture.startWorker { fixture.complete(completed) }
            completer.joinOrThrow()
            assertTrue(completed.superAwaitSucceeded.await(5, TimeUnit.SECONDS))
            completed.releaseAfterSuccess()
            waiter.joinOrThrow()

            assertEquals(1, fixture.events.count(Phase.RETRY))
            val retry = fixture.events.first(Phase.RETRY)
            assertTrue("retry callback must hold owner monitor", retry.holdsMonitor == true)
            assertEquals(0L, completed.count)
        }
    }

    @Test
    fun p4GenerationAdvancedBeforeWorkerRejectsRetry() {
        withFixture { fixture ->
            fixture.state.generationValue = 1L
            val completed = SuccessPauseLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.register(completed)
            fixture.deferStartIfStopping(
                retry = fixture.retryingCallback(),
                logTimeout = fixture.recordingTimeout(),
            )
            assertEquals(1, fixture.events.count(Phase.GENERATION_READ))

            synchronized(fixture.monitor) { fixture.state.advanceGeneration() }
            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completed.superAwaitEntered.await(5, TimeUnit.SECONDS))
            completed.countDown()
            assertTrue(completed.superAwaitSucceeded.await(5, TimeUnit.SECONDS))
            completed.releaseAfterSuccess()
            waiter.joinOrThrow()

            assertEquals(0, fixture.events.count(Phase.RETRY))
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
        }
    }

    @Test
    fun p5GenerationAdvancedAfterAwaitRejectsRetryInsideSecondMonitor() {
        withFixture { fixture ->
            fixture.state.generationValue = 1L
            val completed = SuccessPauseLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.register(completed)
            fixture.deferStartIfStopping(
                retry = fixture.retryingCallback(),
                logTimeout = fixture.recordingTimeout(),
            )

            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completed.superAwaitEntered.await(5, TimeUnit.SECONDS))
            completed.countDown()
            assertTrue(completed.superAwaitSucceeded.await(5, TimeUnit.SECONDS))

            synchronized(fixture.monitor) { fixture.state.advanceGeneration() }
            completed.releaseAfterSuccess()
            waiter.joinOrThrow()

            assertEquals(0, fixture.events.count(Phase.RETRY))
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
        }
    }

    @Test
    fun p6GapPublishesBThenSignalsOnlyA() {
        withFixture { fixture ->
            fixture.state.generationValue = 1L
            val completedA = SignalPauseLatch(fixture.monitor, fixture.events, "A")
            val completedB = CountDownLatch(1)
            fixture.state.completion = completedA
            fixture.register(completedA)
            fixture.deferStartIfStopping(
                retry = {
                    fixture.events.record(Phase.RETRY, "A")
                    fixture.deferStartIfStopping(
                        retry = { fixture.events.record(Phase.RETRY, "B") },
                        logTimeout = fixture.recordingTimeout(),
                    )
                },
                logTimeout = fixture.recordingTimeout(),
            )

            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completedA.superAwaitEntered.await(5, TimeUnit.SECONDS))
            val completer = fixture.startWorker { fixture.complete(completedA) }
            assertTrue(completedA.signalEntered.await(5, TimeUnit.SECONDS))

            synchronized(fixture.monitor) {
                fixture.state.completion = completedB
            }
            completedA.releaseSignal()
            completer.joinOrThrow()
            waiter.joinOrThrow()

            assertEquals(0L, completedA.count)
            assertEquals(1L, completedB.count)
            assertSame(completedB, fixture.state.completionWithoutReading())
            assertEquals(1, fixture.events.count(Phase.RETRY, "A"))
            assertEquals(0, fixture.events.count(Phase.RETRY, "B"))
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
            fixture.events.assertOrder(
                Event(Phase.SIGNAL_REAL, "A"),
                Event(Phase.RETRY, "A"),
            )
            assertEquals("connect-after-disconnect", fixture.tasks.single().name)
        }
    }

    @Test
    fun p7GapWithNewUserStopKeepsBUnsignaled() {
        withFixture { fixture ->
            fixture.state.generationValue = 1L
            val completedA = SignalPauseLatch(fixture.monitor, fixture.events, "A")
            val completedB = CountDownLatch(1)
            fixture.state.completion = completedA
            fixture.register(completedA)
            fixture.deferStartIfStopping(
                retry = fixture.retryingCallback("A"),
                logTimeout = fixture.recordingTimeout(),
            )

            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completedA.superAwaitEntered.await(5, TimeUnit.SECONDS))
            val completer = fixture.startWorker { fixture.complete(completedA) }
            assertTrue(completedA.signalEntered.await(5, TimeUnit.SECONDS))
            synchronized(fixture.monitor) {
                fixture.state.completion = completedB
                fixture.state.advanceGeneration()
            }
            completedA.releaseSignal()
            completer.joinOrThrow()
            waiter.joinOrThrow()

            assertEquals(0L, completedA.count)
            assertEquals(1L, completedB.count)
            assertSame(completedB, fixture.state.completionWithoutReading())
            assertEquals(0, fixture.events.count(Phase.RETRY))
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
        }
    }

    @Test
    fun p8GapCanCompleteBBeforeSignalingA() {
        withFixture { fixture ->
            fixture.state.generationValue = 1L
            val completedA = SignalPauseLatch(fixture.monitor, fixture.events, "A")
            val completedB = CountDownLatch(1)
            fixture.state.completion = completedA
            fixture.register(completedA)
            fixture.deferStartIfStopping(
                retry = fixture.retryingCallback("A"),
                logTimeout = fixture.recordingTimeout(),
            )

            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completedA.superAwaitEntered.await(5, TimeUnit.SECONDS))
            val completer = fixture.startWorker { fixture.complete(completedA) }
            assertTrue(completedA.signalEntered.await(5, TimeUnit.SECONDS))

            fixture.complete(completedB)
            assertEquals(0L, completedB.count)
            assertEquals(1L, completedA.count)
            completedA.releaseSignal()
            completer.joinOrThrow()
            waiter.joinOrThrow()

            assertEquals(0L, completedA.count)
            assertEquals(1, fixture.events.count(Phase.RETRY, "A"))
            fixture.events.assertOrder(
                Event(Phase.SIGNAL_REAL, "A"),
                Event(Phase.RETRY, "A"),
            )
        }
    }

    @Test
    fun d1StaleIdentityStillSignalsCapturedLatch() {
        withFixture { fixture ->
            val completedB = CountDownLatch(1)
            val stale = CountDownLatch(1)
            fixture.state.completion = completedB

            fixture.coordination.complete(stale) {
                fixture.events.record(Phase.LOG_DONE, "A")
            }

            assertEquals(0L, stale.count)
            assertEquals(1L, completedB.count)
            assertSame(completedB, fixture.state.completionWithoutReading())
            assertEquals(0, fixture.events.count(Phase.ALLOW))
            assertEquals(0, fixture.events.count(Phase.COMPLETION_CLEAR))
            assertEquals(1, fixture.events.count(Phase.GATE_SET, "false"))
            assertEquals(1, fixture.events.count(Phase.LOG_DONE, "A"))
        }
    }

    @Test
    fun d2RepeatedCompletionDoesNotRepeatAllowOrClear() {
        withFixture { fixture ->
            val completed = CountDownLatch(1)
            fixture.state.completion = completed
            fixture.coordination.complete(completed) {
                fixture.events.record(Phase.LOG_DONE, "first")
            }
            fixture.coordination.complete(completed) {
                fixture.events.record(Phase.LOG_DONE, "second")
            }

            assertEquals(0L, completed.count)
            assertNull(fixture.state.completionWithoutReading())
            assertEquals(1, fixture.events.count(Phase.ALLOW))
            assertEquals(1, fixture.events.count(Phase.COMPLETION_CLEAR))
            assertEquals(2, fixture.events.count(Phase.GATE_SET, "false"))
            assertEquals(2, fixture.events.count(Phase.LOG_DONE))
        }
    }

    @Test
    fun w1FalseAwaitOnlyLogsTimeout() {
        withFixture { fixture ->
            val completed = FalseAwaitLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            var retried = false
            fixture.deferStartIfStopping(
                retry = { retried = true },
                logTimeout = { fixture.events.record(Phase.TIMEOUT_LOG, "A") },
            )

            fixture.tasks.removeFirst().body()

            assertFalse(retried)
            assertEquals(1L, completed.count)
            assertEquals(listOf("10", "SECONDS"), completed.awaitArguments)
            assertEquals(1, fixture.events.count(Phase.TIMEOUT_LOG))
            completed.countDown()
            assertFalse(retried)
        }
    }

    @Test
    fun w2PreInterruptedRealAwaitPropagatesAndClearsFlag() {
        withFixture { fixture ->
            val completed = RealAwaitLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.deferStartIfStopping(
                retry = fixture.retryingCallback(),
                logTimeout = fixture.recordingTimeout(),
            )

            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            waiter.thread.interrupt()
            waiter.thread.join(5_000)
            assertFalse(waiter.thread.isAlive)

            val error = requireNotNull(waiter.error.getAndSet(null))
            assertTrue("expected InterruptedException", error is InterruptedException)
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
            assertEquals(0, fixture.events.count(Phase.RETRY))
            assertFalse("real InterruptedException clears the interrupted flag", waiter.interruptedOnCatch.get())
        }
    }

    @Test
    fun w3InterruptAfterSuccessfulAwaitDoesNotCancelRetry() {
        withFixture { fixture ->
            val completed = SuccessPauseLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.register(completed)
            fixture.register(completed)
            fixture.deferStartIfStopping(
                retry = {
                    fixture.events.record(
                        Phase.RETRY,
                        "interrupted=${Thread.currentThread().isInterrupted}",
                    )
                },
                logTimeout = fixture.recordingTimeout(),
            )

            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completed.superAwaitEntered.await(5, TimeUnit.SECONDS))
            completed.countDown()
            assertTrue(completed.superAwaitSucceeded.await(5, TimeUnit.SECONDS))
            synchronized(fixture.monitor) {
                waiter.thread.interrupt()
            }
            completed.releaseAfterSuccess()
            waiter.joinOrThrow()

            assertEquals(1, fixture.events.count(Phase.RETRY, "interrupted=true"))
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
        }
    }

    @Test
    fun w4SubmitAndCallbackFailuresPropagateWithoutCleanup() {
        withFixture { fixture ->
            fixture.tasks.submitException = SubmitException("submit")
            val completed = CountDownLatch(1)
            fixture.state.completion = completed
            try {
                fixture.deferStartIfStopping(
                    retry = fixture.retryingCallback(),
                    logTimeout = fixture.recordingTimeout(),
                )
                fail("submit rejection must propagate")
            } catch (expected: SubmitException) {
            }
            assertEquals(1L, completed.count)
            assertSame(completed, fixture.state.completionWithoutReading())
            assertTrue(fixture.tasks.isEmpty)
            assertEquals(0, fixture.events.count(Phase.RETRY))
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
        }

        withFixture { fixture ->
            val completed = SuccessPauseLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.register(completed)
            fixture.deferStartIfStopping(
                retry = { throw RetryException("retry") },
                logTimeout = fixture.recordingTimeout(),
            )
            val waiter = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completed.superAwaitEntered.await(5, TimeUnit.SECONDS))
            completed.countDown()
            assertTrue(completed.superAwaitSucceeded.await(5, TimeUnit.SECONDS))
            completed.releaseAfterSuccess()
            waiter.thread.join(5_000)
            assertFalse(waiter.thread.isAlive)
            assertTrue(waiter.error.getAndSet(null) is RetryException)
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
        }
    }

    @Test
    fun g2MultipleWaitersWithSameGenerationRetry() {
        withFixture { fixture ->
            fixture.state.generationValue = 1L
            val completed = SharedSuccessLatch(fixture.monitor, fixture.events, "A")
            fixture.state.completion = completed
            fixture.register(completed)
            fixture.register(completed)
            repeat(2) {
                fixture.deferStartIfStopping(
                    retry = fixture.retryingCallback("waiter-$it"),
                    logTimeout = fixture.recordingTimeout(),
                )
            }
            assertEquals(2, fixture.tasks.size)

            val first = fixture.startWorker { fixture.tasks.removeFirst().body() }
            val second = fixture.startWorker { fixture.tasks.removeFirst().body() }
            assertTrue(completed.superAwaitEntered.await(5, TimeUnit.SECONDS))
            assertTrue(completed.waitersArrived.await(5, TimeUnit.SECONDS))
            completed.countDown()
            assertTrue(completed.superAwaitSucceeded.await(5, TimeUnit.SECONDS))
            completed.releaseAfterSuccess()
            first.joinOrThrow()
            second.joinOrThrow()

            assertEquals(2, fixture.events.count(Phase.RETRY))
            assertEquals(0, fixture.events.count(Phase.TIMEOUT_LOG))
        }
    }

    @Test
    fun x1S1TailPublishesCompletionThroughRealCoordination() {
        withFixture { fixture ->
            val completed = CountDownLatch(1)
            fixture.state.completion = completed
            val client = RecordingClient(fixture.events)
            val tail = DisconnectTail(fixture.tasks)
            val live =
                object : DisconnectLive {
                    override fun log(message: String) {
                        fixture.events.record(Phase.LEAF, message)
                    }

                    override fun detachBt(reason: String) {
                        fixture.events.record(Phase.LEAF, reason)
                    }

                    override fun stopBtServer() {
                        fixture.events.record(Phase.LEAF, "stop-server")
                    }

                    override fun stopBtClient() {
                        fixture.events.record(Phase.LEAF, "stop-client")
                    }

                    override fun finishBtDiagnostics() {
                        fixture.events.record(Phase.LEAF, "finish-diagnostics")
                    }

                    override fun withWifiLock(body: () -> Unit) {
                        fixture.events.record(Phase.LEAF, "wifi-lock")
                        body()
                    }

                    override fun stopCtrlAdvertiser() {
                        fixture.events.record(Phase.LEAF, "stop-ctrl")
                    }

                    override fun stopEndpointBrowser() {
                        fixture.events.record(Phase.LEAF, "stop-browser")
                    }

                    override fun cleanupShellFallback() {
                        fixture.events.record(Phase.LEAF, "shell")
                    }

                    override fun completeOuter(completed: CountDownLatch) {
                        fixture.coordination.complete(completed) {
                            fixture.events.record(Phase.LOG_DONE, "A")
                        }
                    }
                }
            tail.enqueue(
                DisconnectSnapshot(
                    reason = com.mcarplay.app.diagnostics.DiagnosticCode.USER_STOP,
                    client = client,
                    stopCapturedVideo = { fixture.events.record(Phase.LEAF, "video") },
                    releaseCapturedWifi = { fixture.events.record(Phase.LEAF, "wifi") },
                    completed = completed,
                ),
                live,
            )

            fixture.tasks.removeFirst().body()

            assertEquals(0L, completed.count)
            assertNull(fixture.state.completionWithoutReading())
            fixture.events.assertOrder(
                Event(Phase.LEAF, "video"),
                Event(Phase.LEAF, "wifi"),
                Event(Phase.GATE_SET, "false"),
                Event(Phase.COMPLETION_READ, "A"),
                Event(Phase.ALLOW, "A"),
                Event(Phase.COMPLETION_CLEAR, null),
                Event(Phase.LOG_DONE, "A"),
            )
        }
    }

    @Test
    fun x2AllowAndLogFailuresKeepCurrentAbortPoints() {
        withFixture { fixture ->
            val completed = CountDownLatch(1)
            fixture.state.completion = completed
            fixture.state.allowFailure = AllowException("allow")
            try {
                fixture.coordination.complete(completed) {
                    fixture.events.record(Phase.LOG_DONE, "A")
                }
                fail("allow failure must propagate")
            } catch (expected: AllowException) {
            }
            assertEquals(1L, completed.count)
            assertSame(completed, fixture.state.completionWithoutReading())
            assertEquals(1, fixture.events.count(Phase.GATE_SET, "false"))
            assertEquals(1, fixture.events.count(Phase.ALLOW))
            assertEquals(0, fixture.events.count(Phase.LOG_DONE))
        }

        withFixture { fixture ->
            val completed = CountDownLatch(1)
            fixture.state.completion = completed
            try {
                fixture.coordination.complete(completed) {
                    throw LogDoneException("log-done")
                }
                fail("log failure must propagate")
            } catch (expected: LogDoneException) {
            }
            assertEquals(0L, completed.count)
            assertNull(fixture.state.completionWithoutReading())
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}

private class Fixture {
    val monitor = Any()
    val events = EventTable()
    val state = RecordingCompletionState(monitor, events)
    val tasks = ManualNamedTasks(events)
    val coordination = TeardownCoordination(monitor, state, tasks)
    private val workers = mutableListOf<Worker>()
    private val gates = mutableListOf<TestGate>()

    fun startWorker(body: () -> Unit): Worker =
        Worker(body).also { worker ->
            synchronized(workers) { workers.add(worker) }
            worker.start()
        }

    fun register(gate: TestGate) {
        synchronized(gates) { gates.add(gate) }
    }

    fun deferStartIfStopping(
        retry: () -> Unit,
        logTimeout: () -> Unit,
    ): Boolean = synchronized(monitor) { coordination.deferStartIfStopping(retry, logTimeout) }

    fun retryingCallback(identity: String = "A"): () -> Unit =
        {
            events.record(Phase.RETRY, identity, Thread.holdsLock(monitor))
            synchronized(monitor) {
                events.record(Phase.REENTRANT, identity, Thread.holdsLock(monitor))
            }
        }

    fun recordingTimeout(): () -> Unit = { events.record(Phase.TIMEOUT_LOG, "A") }

    fun complete(completed: CountDownLatch) {
        coordination.complete(completed) {
            events.record(Phase.LOG_DONE, "A")
        }
    }

    fun close() {
        synchronized(gates) { gates.toList() }.forEach { gate -> runCatching { gate.release() } }
        val currentWorkers = synchronized(workers) { workers.toList() }
        currentWorkers.forEach { worker ->
            worker.thread.interrupt()
            worker.joinOrThrow()
        }
        tasks.clear()
        currentWorkers.forEach { worker -> worker.error.get()?.let { throw it } }
    }
}

private class RecordingCompletionState(
    private val monitor: Any,
    private val events: EventTable,
) : CompletionState {
    private var completionValue: CountDownLatch? = null
    var generationValue: Long = 0L
    var allowFailure: AllowException? = null

    override var completion: CountDownLatch?
        get() {
            events.record(Phase.COMPLETION_READ, "A")
            return completionValue
        }
        set(value) {
            events.record(
                if (value == null) Phase.COMPLETION_CLEAR else Phase.COMPLETION_PUBLISH,
                identity(value),
            )
            completionValue = value
        }

    override val userStopGeneration: Long
        get() {
            events.record(Phase.GENERATION_READ, generationValue.toString())
            return generationValue
        }

    override fun setSessionActive(active: Boolean) {
        events.record(Phase.GATE_SET, active.toString(), Thread.holdsLock(monitor))
    }

    override fun allowNewHandoffs() {
        events.record(Phase.ALLOW, identity(completionValue), Thread.holdsLock(monitor))
        allowFailure?.let { throw it }
    }

    fun advanceGeneration() {
        generationValue++
        events.record(Phase.GENERATION_SET, generationValue.toString(), Thread.holdsLock(monitor))
    }

    fun completionWithoutReading(): CountDownLatch? = completionValue
}

private class ManualNamedTasks(
    private val events: EventTable,
) : NamedTasks {
    private val lock = Any()
    private val queue = ArrayDeque<QueuedTask>()
    var submitException: SubmitException? = null

    override fun submit(
        name: String,
        body: () -> Unit,
    ) {
        synchronized(lock) {
            submitException?.let { throw it }
            queue.add(QueuedTask(name, body))
        }
        events.record(Phase.TASK_SUBMIT, name)
    }

    val size: Int
        get() = synchronized(lock) { queue.size }

    val isEmpty: Boolean
        get() = size == 0

    fun single(): QueuedTask {
        val current = synchronized(lock) { queue.toList() }
        assertEquals(1, current.size)
        return current.single()
    }

    fun removeFirst(): QueuedTask = synchronized(lock) { queue.removeFirst() }

    fun clear() {
        synchronized(lock) { queue.clear() }
    }
}

private class QueuedTask(
    val name: String,
    val body: () -> Unit,
)

private class Worker(
    body: () -> Unit,
) {
    val thread = Thread({ run(body) }, "teardown-coordination-worker")
    val error = AtomicReference<Throwable>()
    val interruptedOnCatch = AtomicBoolean()

    private fun run(body: () -> Unit) {
        try {
            body()
        } catch (caught: Throwable) {
            error.compareAndSet(null, caught)
            interruptedOnCatch.set(Thread.currentThread().isInterrupted)
        }
    }

    fun start() {
        thread.start()
    }

    fun joinOrThrow() {
        thread.join(5_000)
        assertFalse("worker did not finish: $thread", thread.isAlive)
        error.get()?.let { throw it }
    }
}

private interface TestGate {
    fun release()
}

private open class SuccessPauseLatch(
    private val monitor: Any,
    private val events: EventTable,
    identity: String,
) : CountDownLatch(1),
    TestGate {
    private val identity = identity
    val superAwaitEntered = CountDownLatch(1)
    val superAwaitSucceeded = CountDownLatch(1)
    private val afterSuccess = Semaphore(0)

    fun releaseAfterSuccess() {
        afterSuccess.release()
    }

    override fun await(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        events.record(Phase.AWAIT_CALL, identity, Thread.holdsLock(monitor))
        superAwaitEntered.countDown()
        val succeeded = super.await(timeout, unit)
        events.record(if (succeeded) Phase.AWAIT_SUCCESS else Phase.AWAIT_FALSE, identity)
        if (succeeded) {
            superAwaitSucceeded.countDown()
            afterSuccess.acquireUninterruptibly()
            events.record(Phase.AWAIT_RESUMED, identity)
        }
        return succeeded
    }

    override fun release() {
        afterSuccess.release()
    }
}

private class SignalPauseLatch(
    private val monitor: Any,
    private val events: EventTable,
    identity: String,
) : CountDownLatch(1),
    TestGate {
    private val identity = identity
    val superAwaitEntered = CountDownLatch(1)
    val signalEntered = CountDownLatch(1)
    private val signalGate = Semaphore(0)

    fun releaseSignal() {
        signalGate.release()
    }

    override fun await(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        events.record(Phase.AWAIT_CALL, identity, Thread.holdsLock(monitor))
        superAwaitEntered.countDown()
        val succeeded = super.await(timeout, unit)
        events.record(if (succeeded) Phase.AWAIT_SUCCESS else Phase.AWAIT_FALSE, identity)
        return succeeded
    }

    override fun countDown() {
        events.record(Phase.SIGNAL_ENTER, identity, Thread.holdsLock(monitor))
        signalEntered.countDown()
        signalGate.acquireUninterruptibly()
        events.record(Phase.SIGNAL_REAL, identity, Thread.holdsLock(monitor))
        super.countDown()
    }

    override fun release() {
        signalGate.release()
    }
}

private class SharedSuccessLatch(
    monitor: Any,
    events: EventTable,
    identity: String,
) : SuccessPauseLatch(monitor, events, identity) {
    private val waitingCount = AtomicInteger()
    val waitersArrived = CountDownLatch(2)

    override fun await(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        waitingCount.incrementAndGet()
        waitersArrived.countDown()
        try {
            return super.await(timeout, unit)
        } finally {
            waitingCount.decrementAndGet()
        }
    }

    override fun countDown() {
        super.countDown()
        repeat(maxOf(waitingCount.get(), 0)) { releaseAfterSuccess() }
    }
}

private class FalseAwaitLatch(
    private val monitor: Any,
    private val events: EventTable,
    identity: String,
) : CountDownLatch(1) {
    private val identity = identity
    val awaitArguments = mutableListOf<String>()

    override fun await(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        events.record(Phase.AWAIT_CALL, identity, Thread.holdsLock(monitor))
        awaitArguments.add(timeout.toString())
        awaitArguments.add(unit.name)
        events.record(Phase.AWAIT_FALSE, identity)
        return false
    }
}

private class RealAwaitLatch(
    private val monitor: Any,
    private val events: EventTable,
    identity: String,
) : CountDownLatch(1) {
    private val identity = identity

    override fun await(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        events.record(Phase.AWAIT_CALL, identity, Thread.holdsLock(monitor))
        return super.await(timeout, unit)
    }
}

private class RecordingClient(
    private val events: EventTable,
) : RetiringClient {
    override val sessionActive: Boolean
        get() {
            events.record(Phase.LEAF, "active-read")
            return true
        }

    override fun stopLikeIphone(): Boolean {
        events.record(Phase.LEAF, "stop-like-iphone")
        return true
    }

    override fun teardownSession() {
        events.record(Phase.LEAF, "teardown")
    }

    override fun close() {
        events.record(Phase.LEAF, "close")
    }
}

private class EventTable {
    private val lock = Any()
    private val list = mutableListOf<Event>()

    val snapshot: List<Event>
        get() = synchronized(lock) { list.toList() }

    fun record(
        phase: Phase,
        identity: String? = null,
        holdsMonitor: Boolean? = null,
    ) {
        synchronized(lock) { list.add(Event(phase, identity, holdsMonitor)) }
    }

    fun count(
        phase: Phase,
        identity: String? = null,
    ): Int =
        synchronized(lock) {
            list.count { it.phase == phase && (identity == null || it.identity == identity) }
        }

    fun first(phase: Phase): Event = synchronized(lock) { list.first { it.phase == phase } }

    fun assertOrder(vararg expected: Event) {
        val actual = snapshot
        val positions =
            expected.map { event ->
                actual.indexOfFirst { actualEvent ->
                    actualEvent.phase == event.phase &&
                        (event.identity == null || actualEvent.identity == event.identity)
                }
            }
        assertTrue("missing events: $expected in $actual", positions.all { it >= 0 })
        assertTrue(
            "events out of order: expected=${expected.toList()}, actual=$actual",
            positions.zipWithNext().all { (first, second) -> first < second },
        )
    }
}

private data class Event(
    val phase: Phase,
    val identity: String? = null,
    val holdsMonitor: Boolean? = null,
)

private enum class Phase {
    ALLOW,
    AWAIT_CALL,
    AWAIT_FALSE,
    AWAIT_RESUMED,
    AWAIT_SUCCESS,
    COMPLETION_CLEAR,
    COMPLETION_PUBLISH,
    COMPLETION_READ,
    GATE_SET,
    GENERATION_READ,
    GENERATION_SET,
    LEAF,
    LOG_DONE,
    REENTRANT,
    RETRY,
    SIGNAL_ENTER,
    SIGNAL_REAL,
    TASK_SUBMIT,
    TIMEOUT_LOG,
}

private class SubmitException(
    override val message: String,
) : RuntimeException(message)

private fun identity(completed: CountDownLatch?): String = if (completed == null) "null" else "A"

private class RetryException(
    override val message: String,
) : RuntimeException(message)

private class AllowException(
    override val message: String,
) : RuntimeException(message)

private class LogDoneException(
    override val message: String,
) : RuntimeException(message)
