package com.mcarplay.app

import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class DisconnectTailTest {
    @Test
    fun enqueueOnlyStoresTaskWithNoLeaves() {
        val harness = Harness()

        harness.enqueue(client = harness.clientA)

        assertEquals(1, harness.tasks.queue.size)
        assertEquals(
            "session-disconnect",
            harness.tasks.queue
                .single()
                .name,
        )
        assertTrue(harness.events.isEmpty())
    }

    @Test
    fun activeUserStopStopSuccessHasNoFallbackOrTeardown() {
        val harness = Harness()
        harness.clientA.active = true
        harness.enqueue(client = harness.clientA)

        harness.runNext()

        harness.assertSnapshotLeaves(
            activeRead = 1,
            protocolStops = 1,
            teardowns = 0,
            videoStops = 1,
            clientCloses = 1,
            wifiReleases = 1,
            completions = 1,
        )
        assertEquals(1, harness.wifiLockObservations.get())
    }

    @Test
    fun activeUserStopFalseThrowsAndBothGoThroughWifiFallback() {
        listOf(false, true).forEach { shouldThrow ->
            val harness = Harness()
            harness.clientA.active = true
            harness.clientA.stopLikeIphoneResult = false
            if (shouldThrow) {
                harness.clientA.stopLikeIphoneException = StopLikeIphoneException("stop")
            }
            harness.enqueue(client = harness.clientA)

            harness.runNext()

            harness.assertSnapshotLeaves(
                activeRead = 1,
                protocolStops = 1,
                teardowns = 0,
                videoStops = 1,
                clientCloses = 1,
                wifiReleases = 2,
                completions = 1,
            )
            assertEquals(2, harness.wifiLockObservations.get())
            assertEquals(0, harness.clientA.teardownCount.get())
        }
    }

    @Test
    fun inactiveNonUserStopAndNullClientUseElseBranch() {
        val inactive = Harness()
        inactive.clientA.active = false
        inactive.enqueue(client = inactive.clientA)
        inactive.runNext()
        inactive.assertSnapshotLeaves(
            activeRead = 1,
            protocolStops = 0,
            teardowns = 1,
            videoStops = 1,
            clientCloses = 1,
            wifiReleases = 1,
            completions = 1,
        )

        val nonUserStop = Harness()
        nonUserStop.clientA.active = true
        nonUserStop.enqueue(client = nonUserStop.clientA, reason = DiagnosticCode.SUPERSEDED)
        nonUserStop.runNext()
        nonUserStop.assertSnapshotLeaves(
            activeRead = 0,
            protocolStops = 0,
            teardowns = 1,
            videoStops = 1,
            clientCloses = 1,
            wifiReleases = 1,
            completions = 1,
        )

        val nullClient = Harness()
        nullClient.enqueue(client = null)
        nullClient.runNext()
        nullClient.assertSnapshotLeaves(
            activeRead = 0,
            protocolStops = 0,
            teardowns = 0,
            videoStops = 1,
            clientCloses = 0,
            wifiReleases = 1,
            completions = 1,
        )
    }

    @Test
    fun activeIsReadAtExecutionNotEnqueue() {
        val harness = Harness()
        harness.clientA.active = true
        harness.enqueue(client = harness.clientA)
        harness.clientA.active = false

        harness.runNext()

        harness.assertSnapshotLeaves(
            activeRead = 1,
            protocolStops = 0,
            teardowns = 1,
            videoStops = 1,
            clientCloses = 1,
            wifiReleases = 1,
            completions = 1,
        )
    }

    @Test
    fun capturedSnapshotStaysAWhileLiveResourcesMoveToB() {
        val harness = Harness()
        harness.clientA.active = true
        harness.enqueue(client = harness.clientA)
        harness.installLiveB()

        harness.runNext()

        val snapshotCalls = harness.events.filter { it.taskId == TaskId.Outer && it.identity == "A" }
        assertEquals(1, snapshotCalls.count { it.operation == Operation.ACTIVE_READ })
        assertEquals(1, snapshotCalls.count { it.operation == Operation.STOP_LIKE_IPHONE })
        assertEquals(0, snapshotCalls.count { it.operation == Operation.TEARDOWN })
        assertEquals(1, snapshotCalls.count { it.operation == Operation.VIDEO_STOP })
        assertEquals(1, snapshotCalls.count { it.operation == Operation.FINALLY_CLOSE })
        harness.liveB.assertTrace(
            Operation.BT_DETACH,
            Operation.BT_STOP_SERVER,
            Operation.BT_STOP_CLIENT,
            Operation.BT_FINISH,
            Operation.WIFI_LOCK,
            Operation.WIFI_STOP_CTRL,
            Operation.WIFI_STOP_BROWSER,
            Operation.SHELL,
        )
        assertTrue(
            harness.events.none {
                it.taskId == TaskId.Outer &&
                    it.identity == "B" &&
                    it.operation in
                    setOf(
                        Operation.ACTIVE_READ,
                        Operation.STOP_LIKE_IPHONE,
                        Operation.TEARDOWN,
                        Operation.VIDEO_STOP,
                        Operation.FINALLY_CLOSE,
                        Operation.WIFI_RELEASE,
                    )
            },
        )
    }

    @Test
    fun eachProtectedLeafExceptionLetsRemainingCleanupContinue() {
        Leaf.entries.forEach { leaf ->
            val harness = Harness()
            harness.clientA.active = true
            harness.fail(leaf)
            harness.enqueue(client = harness.clientA)

            harness.runNext()

            harness.assertLeafContinue(leaf)
        }
    }

    @Test
    fun teardownExceptionPropagatesAfterFinally() {
        val harness = Harness()
        harness.clientA.teardownException = TeardownException("teardown")
        harness.enqueue(client = harness.clientA)

        assertThrows { harness.runNext() }

        harness.assertSnapshotLeaves(
            activeRead = 1,
            protocolStops = 0,
            teardowns = 1,
            videoStops = 1,
            clientCloses = 1,
            wifiReleases = 1,
            completions = 1,
        )
    }

    @Test
    fun guardedLeafExceptionsAbortRemainingFinally() {
        val harness = Harness()
        harness.clientA.active = true
        harness.failControl(Control.WIFI_LOCK)
        harness.enqueue(client = harness.clientA)

        assertThrows { harness.runNext() }

        assertTrue(harness.events.none { it.operation == Operation.SHELL })
        assertTrue(harness.events.none { it.operation == Operation.BT_DETACH })
        assertEquals(0, harness.completionCount.get())
    }

    @Test
    fun completionExceptionEndsCallbackWithoutDoneLog() {
        val harness = Harness()
        harness.clientA.active = true
        harness.completeException = CleanupException("complete")
        harness.enqueue(client = harness.clientA)

        assertThrows { harness.runNext() }

        assertEquals(1, harness.completionCount.get())
        assertEquals(0, harness.doneLogCount.get())
    }

    @Test
    fun submitRejectionDoesNotCompleteOuterLatch() {
        val harness = Harness()
        harness.tasks.submitException = SubmitException("submit")

        assertThrows { harness.enqueue(client = harness.clientA) }

        assertTrue(harness.tasks.queue.isEmpty())
        assertTrue(harness.events.isEmpty())
        assertEquals(0, harness.completionCount.get())
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: RuntimeException) {
            return
        }
        throw AssertionError("expected exception")
    }

    private class Harness {
        val events = mutableListOf<Event>()
        val monitor = Any()
        val clientA = RecordingClient("A", events)
        val clientB = RecordingClient("B", events)
        val liveA = RecordingLive("A")
        val liveB = RecordingLive("B")
        val tasks = ManualNamedTasks()
        val wifiLockObservations = AtomicInteger()
        val completionCount = AtomicInteger()
        val doneLogCount = AtomicInteger()
        var completeException: CleanupException? = null
        private var currentLive = liveA
        private val failingLeaves = mutableSetOf<Leaf>()
        private val failingControls = mutableSetOf<Control>()

        val tail = DisconnectTail(tasks)

        fun enqueue(
            client: RetiringClient?,
            reason: DiagnosticCode = DiagnosticCode.USER_STOP,
        ) {
            val snapshot =
                DisconnectSnapshot(
                    reason = reason,
                    client = client,
                    stopCapturedVideo = { recordLeaf(Leaf.VIDEO, "A") },
                    releaseCapturedWifi = { recordWifiRelease() },
                    completed = CountDownLatch(1),
                )
            tail.enqueue(snapshot, live)
        }

        fun installLiveB() {
            currentLive = liveB
        }

        fun fail(leaf: Leaf) {
            failingLeaves.add(leaf)
        }

        fun failControl(control: Control) {
            failingControls.add(control)
        }

        fun runNext() = tasks.runNext()

        private val live =
            object : DisconnectLive {
                override fun log(message: String) {
                }

                override fun detachBt(reason: String) {
                    checkControl(Control.DETACH)
                    currentLive.record(Operation.BT_DETACH)
                    if (reason != "连接结束 USER_STOP") {
                        throw CleanupException(reason)
                    }
                }

                override fun stopBtServer() {
                    checkControl(Control.STOP_SERVER)
                    currentLive.record(Operation.BT_STOP_SERVER)
                }

                override fun stopBtClient() {
                    checkControl(Control.STOP_CLIENT)
                    currentLive.record(Operation.BT_STOP_CLIENT)
                }

                override fun finishBtDiagnostics() {
                    checkControl(Control.DIAGNOSTICS)
                    currentLive.record(Operation.BT_FINISH)
                }

                override fun withWifiLock(body: () -> Unit) {
                    checkControl(Control.WIFI_LOCK)
                    currentLive.record(Operation.WIFI_LOCK)
                    synchronized(monitor) { body() }
                }

                override fun stopCtrlAdvertiser() {
                    checkControl(Control.STOP_CTRL)
                    currentLive.record(Operation.WIFI_STOP_CTRL)
                }

                override fun stopEndpointBrowser() {
                    checkControl(Control.STOP_BROWSER)
                    currentLive.record(Operation.WIFI_STOP_BROWSER)
                }

                override fun cleanupShellFallback() {
                    currentLive.record(Operation.SHELL)
                    recordLeaf(Leaf.SHELL, currentLive.identity)
                }

                override fun completeOuter(completed: CountDownLatch) {
                    synchronized(monitor) { completionCount.incrementAndGet() }
                    completeException?.let { throw it }
                }
            }

        private fun recordLeaf(
            leaf: Leaf,
            identity: String,
        ) {
            synchronized(monitor) {
                events.add(Event(TaskId.Outer, identity, leaf.operation))
            }
            if (leaf in failingLeaves) {
                throw CleanupException(leaf.name)
            }
        }

        private fun recordWifiRelease() {
            synchronized(monitor) {
                events.add(Event(TaskId.Outer, "A", Operation.WIFI_RELEASE))
                if (Thread.holdsLock(monitor)) wifiLockObservations.incrementAndGet()
            }
            if (Leaf.WIFI in failingLeaves) {
                throw CleanupException(Leaf.WIFI.name)
            }
        }

        private fun checkControl(control: Control) {
            if (control in failingControls) {
                throw CleanupException(control.key)
            }
        }

        fun assertSnapshotLeaves(
            activeRead: Int,
            protocolStops: Int,
            teardowns: Int,
            videoStops: Int,
            clientCloses: Int,
            wifiReleases: Int,
            completions: Int,
        ) {
            fun count(operation: Operation) = events.count { it.taskId == TaskId.Outer && it.operation == operation }
            assertEquals(activeRead, count(Operation.ACTIVE_READ))
            assertEquals(protocolStops, count(Operation.STOP_LIKE_IPHONE))
            assertEquals(teardowns, count(Operation.TEARDOWN))
            assertEquals(videoStops, count(Operation.VIDEO_STOP))
            assertEquals(clientCloses, count(Operation.FINALLY_CLOSE))
            assertEquals(wifiReleases, count(Operation.WIFI_RELEASE))
            assertEquals(completions, completionCount.get())
            assertTrue(events.none { it.taskId == TaskId.Inner })
        }

        fun assertLeafContinue(leaf: Leaf) {
            assertTrue(events.any { it.operation == leaf.operation })
            assertEquals(1, completionCount.get())
            assertTrue(events.none { it.taskId == TaskId.Inner })
        }
    }

    private class ManualNamedTasks : NamedTasks {
        val queue = ArrayDeque<QueuedTask>()
        var submitException: SubmitException? = null

        override fun submit(
            name: String,
            body: () -> Unit,
        ) {
            submitException?.let { throw it }
            queue.add(QueuedTask(name, body))
        }

        fun runNext() {
            val task = queue.removeFirst()
            task.body()
        }
    }

    private class QueuedTask(
        val name: String,
        val body: () -> Unit,
    )

    private class RecordingClient(
        private val identity: String,
        private val events: MutableList<Event>,
    ) : RetiringClient {
        var active = false
        var stopLikeIphoneResult = true
        var stopLikeIphoneException: StopLikeIphoneException? = null
        var teardownException: TeardownException? = null
        val teardownCount = AtomicInteger()

        override val sessionActive: Boolean
            get() {
                events.add(Event(TaskId.Outer, identity, Operation.ACTIVE_READ))
                return active
            }

        override fun stopLikeIphone(): Boolean {
            events.add(Event(TaskId.Outer, identity, Operation.STOP_LIKE_IPHONE))
            stopLikeIphoneException?.let { throw it }
            return stopLikeIphoneResult
        }

        override fun teardownSession() {
            teardownCount.incrementAndGet()
            events.add(Event(TaskId.Outer, identity, Operation.TEARDOWN))
            teardownException?.let { exception -> throw exception }
        }

        override fun close() {
            events.add(Event(TaskId.Outer, identity, Operation.FINALLY_CLOSE))
        }
    }

    private class RecordingLive(
        val identity: String,
    ) {
        val trace = mutableListOf<Operation>()

        fun record(operation: Operation) {
            trace.add(operation)
        }

        fun assertTrace(vararg operations: Operation) {
            assertEquals(operations.toList(), trace)
        }
    }

    private enum class TaskId { Outer, Inner }

    private enum class Operation {
        ACTIVE_READ,
        STOP_LIKE_IPHONE,
        TEARDOWN,
        VIDEO_STOP,
        FINALLY_CLOSE,
        BT_DETACH,
        BT_STOP_SERVER,
        BT_STOP_CLIENT,
        BT_FINISH,
        WIFI_STOP_CTRL,
        WIFI_STOP_BROWSER,
        WIFI_LOCK,
        WIFI_RELEASE,
        SHELL,
    }

    private enum class Bt {
        DETACH,
        STOP_SERVER,
        STOP_CLIENT,
        FINISH,
    }

    private enum class Wifi {
        STOP_CTRL,
        STOP_BROWSER,
    }

    private enum class Control(
        val key: String,
    ) {
        WIFI_LOCK("wifi-lock"),
        DETACH("detach"),
        STOP_SERVER("server-stop"),
        STOP_CLIENT("client-stop"),
        DIAGNOSTICS("diagnostics"),
        STOP_CTRL("ctrl-stop"),
        STOP_BROWSER("browser-stop"),
        WIFI_RELEASE("wifi-release"),
        SHELL("shell"),
    }

    private enum class Leaf(
        val operation: Operation,
    ) {
        VIDEO(Operation.VIDEO_STOP),
        WIFI(Operation.WIFI_RELEASE),
        SHELL(Operation.SHELL),
    }

    private data class Event(
        val taskId: TaskId,
        val identity: String?,
        val operation: Operation,
    )

    private class StopLikeIphoneException(
        override val message: String,
    ) : RuntimeException(message)

    private class TeardownException(
        override val message: String,
    ) : RuntimeException(message)

    private class CleanupException(
        override val message: String,
    ) : RuntimeException(message)

    private class SubmitException(
        override val message: String,
    ) : RuntimeException(message)
}
