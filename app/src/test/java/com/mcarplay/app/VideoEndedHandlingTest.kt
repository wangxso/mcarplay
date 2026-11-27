package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VideoEndedHandlingTest {
    private class Recorder {
        val events = java.util.concurrent.ConcurrentLinkedQueue<String>()

        fun record(event: String) {
            events.add(event)
        }
    }

    private class Fixture {
        val recorder = Recorder()
        val currentSenderState = AtomicReference<String?>("sender")
        val outputSenderState = AtomicReference<String?>("sender")
        val bridgeState = AtomicReference<String?>("bridge")
        val btClientState = AtomicReference<String?>("bt")
        val contextState = AtomicReference<String?>("context")
        var forceKeyFramesValue = 0
        var requestUiValue = 0
        var touchesValue = 0
        var consumerReleasesValue = 0
        var environmentSampleAtValue = 1L
        var carLifeOutputCleared = false
        var nativeBridgeCleared = false
        var sessionInactive = false
        var dimmerInactive = false
        var outcome = false
        var failingAction: String? = null
        val failure = RuntimeException("leaf failure")
        val failureLatch = CountDownLatch(1)

        val slots =
            object : VideoEndSlots<String, String, String, String> {
                override val currentSender: String?
                    get() = currentSenderState.get()

                override val outputSender: String?
                    get() = outputSenderState.get()

                override val nativeBridge: String?
                    get() = bridgeState.get()

                override val btClient: String?
                    get() = btClientState.get()

                override val context: String?
                    get() = contextState.get()

                override val forceKeyFrames: Int
                    get() = forceKeyFramesValue

                override val requestUi: Int
                    get() = requestUiValue

                override val touches: Int
                    get() = touchesValue

                override val consumerReleases: Int
                    get() = consumerReleasesValue

                override fun clearCarLifeOutput() {
                    carLifeOutputCleared = true
                    recorder.record("clear-car-life-output")
                }

                override fun clearNativeBridge() {
                    nativeBridgeCleared = true
                    recorder.record("clear-native-bridge")
                }

                override fun setSessionInactive() {
                    sessionInactive = true
                    recorder.record("session-inactive")
                }

                override fun setDimmerInactive() {
                    dimmerInactive = true
                    recorder.record("dimmer-inactive")
                }

                override fun resetEnvironmentSampleAt() {
                    environmentSampleAtValue = 0L
                    recorder.record("reset-sample")
                }
            }

        val actions =
            object : VideoEndActions<String, String, String, String, String> {
                override fun log(message: String) {
                    recorder.record(if (message.startsWith("SESSION:")) "log-stats" else "log-end")
                    if (failingAction == "stats-log") throw failure
                }

                override fun videoEndedTimeline(attempt: String) {
                    recorder.record("timeline:$attempt")
                }

                override fun mediaEnded(observation: Any?) {
                    recorder.record("media-ended:$observation")
                }

                override fun stopCarLife(carLife: String) {
                    recorder.record("stop-car-life:$carLife")
                    if (failingAction == "car-life-stop") throw failure
                }

                override fun releaseCarLife(carLife: String) {
                    recorder.record("release-car-life:$carLife")
                    if (failingAction == "car-life-release") throw failure
                }

                override fun releaseWifiLock() {
                    recorder.record("release-wifi")
                }

                override fun stopScreenMonitor(context: String) {
                    recorder.record("stop-monitor:$context")
                }

                override fun stopNativeBridge(bridge: String) {
                    recorder.record("stop-bridge:$bridge")
                    if (failingAction == "bridge-stop") throw failure
                }

                override fun armBluetoothAfterSessionEnd(btClient: String) {
                    recorder.record("arm-bt:$btClient")
                    if (failingAction == "arm-bt") throw failure
                }

                override fun restorePhoneScreen(sleepIfCarSide: Boolean) {
                    recorder.record("restore:$sleepIfCarSide")
                }

                override fun cleanupShellFallbackNetwork() {
                    recorder.record("cleanup-shell")
                }

                override fun attributeAudioTeardown() {
                    recorder.record("attribute-audio")
                    if (failingAction == "attribute-audio") throw failure
                }

                override fun recordAutoReconnectOutcome(
                    attempt: String?,
                    peer: String?,
                ): Boolean {
                    recorder.record("record:$attempt:$peer")
                    return outcome
                }

                override fun disconnect(context: String) {
                    recorder.record("disconnect:$context")
                }
            }

        val handling = VideoEndedHandling(slots, actions)

        fun capture(
            senderIdentity: String = "sender",
            carLife: String? = null,
            mediaObservation: String? = null,
            attempt: String = "attempt",
            peer: String? = null,
            onCloseDashboard: () -> Unit = {},
        ): VideoEndCapture<String, String> =
            object : VideoEndCapture<String, String> {
                override val senderIdentity: () -> String = { senderIdentity }

                override val carLifeBinding: () -> String? = { carLife }

                override val mediaObservation: () -> Any? = { mediaObservation }

                override val attempt: String = attempt

                override val peerAddress: String? = peer

                override fun closeDashboard() = onCloseDashboard()
            }

        fun invoke(action: () -> Unit) {
            try {
                action()
            } catch (caught: Throwable) {
                recorder.record("throw:${caught.javaClass.simpleName}")
                failureLatch.countDown()
                assertSame(failure, caught)
            }
        }

        fun run(capture: VideoEndCapture<String, String>) {
            invoke { handling.handle(capture) }
        }
    }

    @Test
    fun staleVideoEndCannotCloseTheCurrentDashboard() {
        val fixture = Fixture()
        val close = { fixture.recorder.record("dashboard.close") }
        fixture.run(fixture.capture(senderIdentity = "replaced", onCloseDashboard = close))
        assertTrue(fixture.recorder.events.isEmpty())

        fixture.run(fixture.capture(onCloseDashboard = close))
        val events = fixture.recorder.events.toList()
        assertTrue(events.indexOf("dashboard.close") in 0 until events.indexOf("release-wifi"))
        assertEquals(1, events.count { it == "dashboard.close" })
    }

    private fun completeEvents(
        fixture: Fixture,
        carLife: String? = null,
        observation: String = "observation",
        outcome: Boolean = false,
    ): List<String> {
        val events =
            mutableListOf("log-stats", "reset-sample", "timeline:attempt", "media-ended:$observation")
        if (carLife != null) events += listOf("stop-car-life:$carLife", "release-car-life:$carLife")
        if (fixture.outputSenderState.get() == "sender") events += "clear-car-life-output"
        events += listOf("release-wifi", "stop-monitor:context")
        if (fixture.bridgeState.get() != null) events += "stop-bridge:${fixture.bridgeState.get()}"
        events += listOf("clear-native-bridge", "log-end")
        if (fixture.btClientState.get() != null) events += "arm-bt:${fixture.btClientState.get()}"
        events += listOf("session-inactive", "dimmer-inactive", "restore:true", "cleanup-shell", "attribute-audio", "record:attempt:peer")
        if (outcome) events += "disconnect:context"
        return events
    }

    @Test
    fun normalRunMatchesOriginalTeardownOrder() {
        val fixture = Fixture()
        fixture.run(fixture.capture(carLife = "car-life", mediaObservation = "observation", peer = "peer"))

        assertEquals(completeEvents(fixture, carLife = "car-life"), fixture.recorder.events.toList())
        assertTrue(fixture.carLifeOutputCleared)
        assertTrue(fixture.nativeBridgeCleared)
        assertTrue(fixture.sessionInactive)
        assertTrue(fixture.dimmerInactive)
        assertEquals(0L, fixture.environmentSampleAtValue)
    }

    @Test
    fun staleSenderReturnsWithoutSideEffects() {
        val fixture = Fixture()
        fixture.run(fixture.capture(senderIdentity = "old"))

        assertTrue(fixture.recorder.events.isEmpty())
        assertEquals(1L, fixture.environmentSampleAtValue)
        assertFalse(fixture.carLifeOutputCleared)
        assertFalse(fixture.nativeBridgeCleared)
        assertFalse(fixture.sessionInactive)
        assertFalse(fixture.dimmerInactive)
    }

    @Test
    fun outcomeTripsDisconnectAndNullContextSkipsIt() {
        val tripped = Fixture()
        tripped.outcome = true
        tripped.run(tripped.capture(mediaObservation = "observation", peer = "peer"))

        assertEquals(completeEvents(tripped, outcome = true), tripped.recorder.events.toList())

        val noContext = Fixture()
        noContext.outcome = true
        noContext.contextState.set(null)
        noContext.run(noContext.capture(mediaObservation = "observation", peer = "peer"))

        val expected =
            completeEvents(noContext).filterNot { it.startsWith("stop-monitor") || it == "disconnect:context" }
        assertEquals(expected, noContext.recorder.events.toList())
    }

    @Test
    fun deferredCarLifeObservationAndOutputReadAtCallTime() {
        val fixture = Fixture()
        var carLife: String? = null
        var observation = "late-observation"
        var senderIdentity = "old"
        fixture.run(
            object : VideoEndCapture<String, String> {
                override val senderIdentity: () -> String = { senderIdentity }

                override val carLifeBinding: () -> String? = { carLife }

                override val mediaObservation: () -> Any? = { observation }

                override val attempt: String = "attempt"

                override val peerAddress: String? = "peer"
            },
        )
        assertTrue(fixture.recorder.events.isEmpty())

        carLife = "late-car-life"
        observation = "now-observation"
        senderIdentity = "sender"
        fixture.outputSenderState.set("sender")
        fixture.run(
            object : VideoEndCapture<String, String> {
                override val senderIdentity: () -> String = { senderIdentity }

                override val carLifeBinding: () -> String? = { carLife }

                override val mediaObservation: () -> Any? = { observation }

                override val attempt: String = "attempt"

                override val peerAddress: String? = "peer"
            },
        )
        assertEquals(completeEvents(fixture, carLife = "late-car-life", observation = "now-observation"), fixture.recorder.events.toList())
        assertTrue(fixture.carLifeOutputCleared)
    }

    @Test
    fun outputMismatchDoesNotClearOutput() {
        val fixture = Fixture()
        fixture.outputSenderState.set("other")
        fixture.run(fixture.capture(mediaObservation = "observation", peer = "peer"))

        assertFalse(fixture.carLifeOutputCleared)
        assertEquals(completeEvents(fixture).filterNot { it == "clear-car-life-output" }, fixture.recorder.events.toList())
    }

    @Test
    fun nullableSlotsShortCircuitWithoutBlockingLaterSteps() {
        val fixture = Fixture()
        fixture.contextState.set(null)
        fixture.bridgeState.set(null)
        fixture.btClientState.set(null)
        fixture.run(fixture.capture(mediaObservation = "observation", peer = "peer"))

        val events = fixture.recorder.events.toList()
        assertFalse(events.contains("stop-monitor:context"))
        assertFalse(events.contains("stop-bridge:bridge"))
        assertFalse(events.contains("arm-bt:bt"))
        assertEquals(
            completeEvents(fixture).filterNot { it.startsWith("stop-monitor") || it.startsWith("stop-bridge") || it.startsWith("arm-bt") },
            events,
        )
    }

    @Test
    fun counterReadsHappenDuringLog() {
        val fixture = Fixture()
        fixture.forceKeyFramesValue = 3
        fixture.requestUiValue = 4
        fixture.touchesValue = 5
        fixture.consumerReleasesValue = 6
        fixture.run(fixture.capture())

        assertTrue(fixture.recorder.events.contains("log-stats"))
        assertEquals(3, fixture.forceKeyFramesValue)
        assertEquals(4, fixture.requestUiValue)
        assertEquals(5, fixture.touchesValue)
        assertEquals(6, fixture.consumerReleasesValue)
    }

    @Test
    fun leafFailureStopsTheTraceAtThatLeaf() {
        for (failing in listOf("stats-log", "car-life-stop", "car-life-release", "bridge-stop", "arm-bt")) {
            val fixture = Fixture()
            when (failing) {
                "stats-log" -> fixture.failingAction = "stats-log"
                "car-life-stop" -> fixture.failingAction = "car-life-stop"
                "car-life-release" -> fixture.failingAction = "car-life-release"
                "bridge-stop" -> fixture.failingAction = "bridge-stop"
                "arm-bt" -> fixture.failingAction = "arm-bt"
            }
            fixture.run(fixture.capture(carLife = "car-life"))
            assertTrue(fixture.recorder.events.contains("throw:RuntimeException"))
            assertTrue(fixture.failureLatch.await(0, TimeUnit.MILLISECONDS))
            val thrownAt = fixture.recorder.events.indexOf("throw:RuntimeException")
            assertTrue(thrownAt >= 0)
            assertFalse(
                fixture.recorder.events
                    .drop(thrownAt + 1)
                    .any { it.startsWith("record:") },
            )
        }
    }

    @Test
    fun concurrentStaleSenderCallsOnlyCurrentSenderRuns() {
        val fixture = Fixture()
        val currentLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val staleWorker =
            Thread({
                try {
                    currentLatch.await(5, TimeUnit.SECONDS)
                    releaseLatch.countDown()
                    fixture.run(fixture.capture(senderIdentity = "old"))
                } catch (caught: Throwable) {
                    error.compareAndSet(null, caught)
                }
            })
        val currentWorker =
            Thread({
                try {
                    currentLatch.countDown()
                    releaseLatch.await(5, TimeUnit.SECONDS)
                    fixture.run(fixture.capture(mediaObservation = "observation", peer = "peer"))
                } catch (caught: Throwable) {
                    error.compareAndSet(null, caught)
                }
            })
        staleWorker.start()
        currentWorker.start()
        currentLatch.await(5, TimeUnit.SECONDS)
        releaseLatch.countDown()
        staleWorker.join(5_000)
        currentWorker.join(5_000)
        assertFalse(staleWorker.isAlive)
        assertFalse(currentWorker.isAlive)
        error.get()?.let { throw it }

        assertEquals(1, fixture.recorder.events.count { it == "log-stats" })
        assertTrue(fixture.recorder.events.contains("record:attempt:peer"))
    }

    @Test
    fun secondExitAfterHelperExceptionCanRunAgain() {
        val fixture = Fixture()
        fixture.failingAction = "attribute-audio"
        fixture.run(fixture.capture(mediaObservation = "observation", peer = "peer"))
        assertTrue(fixture.recorder.events.contains("throw:RuntimeException"))

        fixture.failingAction = null
        fixture.run(fixture.capture(mediaObservation = "observation", peer = "peer"))
        val firstTrace = completeEvents(fixture).dropLast(1)
        val secondTrace = completeEvents(fixture)
        assertEquals(firstTrace + listOf("throw:RuntimeException") + secondTrace, fixture.recorder.events.toList())
    }
}
