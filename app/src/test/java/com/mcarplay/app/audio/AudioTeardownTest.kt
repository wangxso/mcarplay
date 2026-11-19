package com.mcarplay.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音频拆链归因真实控制流：MIB3 看门狗、20 秒阈值与动态读点。 */
class AudioTeardownTest {
    private val testContext = TestContext()

    private class RecordingOutput : AudioTeardownOutput {
        var linkTeardownCalls = 0
        var workingCalls = 0
        var rejectedCalls = 0
        var hintCalls = 0
        var lastWorking = -1L
        var lastRejected = -1L
        var lastDisabled = false

        override fun linkTeardown(livedMillis: Long) {
            linkTeardownCalls++
        }

        override fun working(
            profile: AudioProfile,
            livedMillis: Long,
        ) {
            workingCalls++
            lastWorking = livedMillis
        }

        override fun rejected(
            profile: AudioProfile,
            livedMillis: Long,
            allDisabled: Boolean,
        ) {
            rejectedCalls++
            lastRejected = livedMillis
            lastDisabled = allDisabled
        }

        override fun hint(allDisabled: Boolean) {
            hintCalls++
            lastDisabled = allDisabled
        }
    }

    private class Memory(
        var workingCount: Int = 0,
        var rejectedCount: Int = 0,
        var loadCount: Int = 0,
        var context: Any? = null,
    ) : AudioTeardownMemory {
        override fun recordWorking(
            context: Any?,
            receiverKey: String,
            profileKey: String,
        ) {
            workingCount++
            this.context = context
        }

        override fun recordRejected(
            context: Any?,
            receiverKey: String,
            profileKey: String,
            allCandidates: Set<String>,
        ): AudioProfileMemory.Entry {
            rejectedCount++
            this.context = context
            return AudioProfileMemory.Entry(disabled = allCandidates.isEmpty())
        }

        override fun load(
            context: Any?,
            receiverKey: String,
        ): AudioProfileMemory.Entry {
            loadCount++
            return AudioProfileMemory.Entry()
        }
    }

    @Test
    fun noProfileReturnsBeforeStateReadsOrSideEffects() {
        var stateReads = 0
        val memory = Memory()
        val output = RecordingOutput()
        val teardown =
            audioTeardown(
                state = {
                    stateReads++
                    AudioTeardownState(null, 0L, "receiver")
                },
                memory = memory,
                output = output,
                nowValues = listOf(),
                watchdogValues = listOf(),
            )
        teardown.attribute()
        assertEquals(1, stateReads)
        assertEquals(0, memory.workingCount)
        assertEquals(0, memory.rejectedCount)
        assertEquals(0, output.linkTeardownCalls)
        assertEquals(0, output.workingCalls)
        assertEquals(0, output.rejectedCalls)
    }

    @Test
    fun startedAtZeroClearsWithoutClockReadOrMemoryWrites() {
        val memory = Memory()
        val output = RecordingOutput()
        var clockReads = 0
        val teardown =
            audioTeardown(
                state = { AudioTeardownState(testProfile(), 0L, "receiver") },
                memory = memory,
                output = output,
                clockReads = { clockReads++ },
                nowValues = listOf(1_234L),
                watchdogValues = listOf(),
            )
        teardown.attribute()
        assertEquals(0, clockReads)
        assertEquals(0, memory.workingCount)
        assertEquals(0, memory.rejectedCount)
        assertEquals(0, output.workingCalls)
        assertEquals(0, output.rejectedCalls)
        assertTrue(cleared)
    }

    @Test
    fun mib3WatchdogLogsClearsAndSkipsProfileMemory() {
        val memory = Memory()
        val output = RecordingOutput()
        val teardown =
            audioTeardown(
                state = { AudioTeardownState(testProfile(), 10_000L, "receiver") },
                memory = memory,
                output = output,
                nowValues = listOf(20_000L),
                watchdogValues = listOf(Mib3LinkWatchdog(armed = true, controlSessionDone = false)),
            )
        teardown.attribute()
        assertEquals(1, output.linkTeardownCalls)
        assertEquals(0, memory.workingCount)
        assertEquals(0, memory.rejectedCount)
        assertTrue(cleared)
    }

    @Test
    fun workingRecordsAtExactlyTwentySeconds() {
        val memory = Memory()
        val output = RecordingOutput()
        val teardown =
            audioTeardown(
                state = { AudioTeardownState(testProfile(), 1_000L, "receiver") },
                memory = memory,
                output = output,
                nowValues = listOf(21_000L),
                watchdogValues = listOf(null, Mib3LinkWatchdog(false, true)),
            )
        teardown.attribute()
        assertEquals(1, memory.workingCount)
        assertEquals(0, memory.rejectedCount)
        assertEquals(1, output.workingCalls)
        assertEquals(20_000L, output.lastWorking)
        assertTrue(cleared)
    }

    @Test
    fun rejectedRecordsBelowTwentySecondsAndHintsAfterMemory() {
        val memory = Memory()
        val output = RecordingOutput()
        val teardown =
            audioTeardown(
                state = { AudioTeardownState(testProfile(), 1_000L, "receiver") },
                memory = memory,
                output = output,
                nowValues = listOf(20_999L),
                watchdogValues = listOf(null, Mib3LinkWatchdog(false, true)),
            )
        teardown.attribute()
        assertEquals(1, memory.rejectedCount)
        assertEquals(1, output.rejectedCalls)
        assertEquals(19_999L, output.lastRejected)
        assertEquals(1, output.hintCalls)
        assertFalse(output.lastDisabled)
        assertTrue(cleared)
    }

    @Test
    fun blankReceiverKeySkipsMemoryWrites() {
        val memory = Memory()
        val output = RecordingOutput()
        val teardown =
            audioTeardown(
                state = { AudioTeardownState(testProfile(), 1_000L, "") },
                memory = memory,
                output = output,
                nowValues = listOf(10_000L),
                watchdogValues = listOf(null),
            )
        teardown.attribute()
        assertEquals(0, memory.workingCount)
        assertEquals(0, memory.rejectedCount)
        assertEquals(0, output.workingCalls)
        assertEquals(0, output.rejectedCalls)
        assertTrue(cleared)
    }

    private var cleared = false

    private fun testProfile(): AudioProfile =
        AudioProfile(
            audioType = "media",
            formatBit = 1L,
            sampleRate = 48_000,
            latencyMs = 0L,
            streamType = 100L,
            codec = AudioCodec.PCM,
        )

    private fun audioTeardown(
        state: () -> AudioTeardownState,
        memory: AudioTeardownMemory,
        output: AudioTeardownOutput,
        nowValues: List<Long?>,
        watchdogValues: List<Mib3LinkWatchdog?>,
        clockReads: (() -> Unit)? = null,
    ): AudioTeardown =
        AudioTeardown(
            probeMillis = 20_000L,
            memory = memory,
            state = state,
            clearState = { cleared = true },
            watchdog = { watchdogValues.firstOrNull() },
            nowMs = {
                clockReads?.invoke()
                nowValues.firstOrNull() ?: 0L
            },
            context = { testContext },
            allCandidateKeys = { setOf("media|2048") },
            output = output,
        )
}

private class TestContext
