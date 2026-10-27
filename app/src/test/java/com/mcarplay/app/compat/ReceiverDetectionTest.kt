package com.mcarplay.app.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverDetectionTest {
    @Test
    fun replacementStreamFreezesPreviousTrialInsteadOfMixingItsConfirmation() {
        streaming()
        assertFalse(detection.attach(observation.copy(session = 2), "replacement"))
        assertEquals(ReceiverDetection.State.STOPPED, detection.snapshot()!!.state)
        assertEquals("SUPERSEDED", detection.snapshot()!!.issue)
        detection.progress("replacement", "STREAMING", null, 100, 100, 100, 100)
        assertEquals(25L, detection.snapshot()!!.videoFrames)
        assertEquals("attempt-a", detection.snapshot()!!.attempt)
    }

    @Test
    fun cancellingStoppedTrialAlsoDiscardsItsUncommittedConfirmation() {
        streaming()
        detection.stop("VIDEO_CLOSED")
        detection.stop("USER_STOP", cancelled = true)
        assertEquals(ReceiverDetection.State.CANCELLED, detection.snapshot()!!.state)
        assertNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
    }

    @Test
    fun componentRefreshBeforeMediaUpdatesDraftEnvironment() {
        started()
        detection.environmentUpdated("updated-components")
        assertEquals("updated-components", detection.snapshot()!!.environment)
        assertEquals(ReceiverDetection.State.COLLECTING, detection.snapshot()!!.state)
    }

    @Test
    fun environmentChangeAfterMediaRequiresANewDetectionBeforeSaving() {
        streaming()
        detection.environmentUpdated("updated-components")
        assertEquals(ReceiverDetection.State.STOPPED, detection.snapshot()!!.state)
        assertEquals("ENVIRONMENT_CHANGED", detection.snapshot()!!.issue)
        assertNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
    }

    @Test
    fun bluetoothDisconnectIsRememberedOnlyWithPositiveTrialEvidence() {
        for (known in listOf(false, true)) {
            for (hadProfiles in listOf(false, true)) {
                val detector = ReceiverDetection()
                detector.begin("receiver", "environment")
                detector.connectionStarted(1, "attempt")
                detector.retryBluetooth()
                detector.profileTrialCompleted(known, disconnected = true, hadProfiles = hadProfiles)
                val token = ReceiverObservation("receiver", 1, 1)
                detector.attach(token, "attempt")
                detector.capabilities(token, "capabilities", InfoRequest.GET_EMPTY_PLIST)
                detector.progress("attempt", "STREAMING", null, 30, 30, 0, 0)
                val saved = detector.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 1, "test")!!
                assertEquals(
                    if (known && hadProfiles) BluetoothBringup.DISCONNECT_PROFILES else BluetoothBringup.KEEP_PROFILES,
                    saved.bluetooth,
                )
            }
        }
    }

    @Test
    fun absentAudioAndTouchCanBeRecordedAsProblemsWithoutPositiveClaims() {
        started()
        detection.attach(observation, "attempt-a")
        detection.capabilities(observation, "caps", InfoRequest.GET_EMPTY_PLIST)
        detection.progress("attempt-a", "STREAMING", null, 30, 25, 0, 0)
        val saved = detectorSaveWithProblems()
        assertEquals(ReceiverCheck.FAILED, saved.verification.audio)
        assertEquals(ReceiverCheck.FAILED, saved.verification.touch)
        assertNull(saved.audioProfile)
    }

    private fun detectorSaveWithProblems(): LocalReceiverConfiguration =
        detection.prepareSave(ReceiverVerification(ReceiverCheck.CONFIRMED, ReceiverCheck.FAILED, ReceiverCheck.FAILED), 1, "test")!!

    private var clock = 1000L
    private val detection = ReceiverDetection { clock }
    private val observation = ReceiverObservation("target-a", 1, 1)

    private fun started(): ReceiverDetection {
        assertTrue(detection.begin("target-a", "phone-environment"))
        detection.connectionStarted(1, "attempt-a")
        return detection
    }

    private fun streaming() {
        started()
        assertTrue(detection.attach(observation, "attempt-a"))
        detection.capabilities(observation, "capabilities-a", InfoRequest.GET_EMPTY_PLIST)
        detection.audioSelected(observation, "102|media|8388608|AAC_LC")
        detection.progress("attempt-a", "STREAMING", null, 30, 25, 10, 9)
        detection.touch(observation)
    }

    @Test
    fun transportCountersRequireUserConfirmationBeforeSaving() {
        streaming()
        assertNull(detection.prepareSave(ReceiverVerification(), 2000, "test"))
        assertEquals(ReceiverDetection.State.COLLECTING, detection.snapshot()!!.state)
        val saved = detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test")!!
        assertNull("未听到声音确认时，不把协商成功的格式自动固化", saved.audioProfile)
        assertEquals(ReceiverCheck.UNVERIFIED, saved.verification.audio)
        assertEquals(ReceiverCheck.UNVERIFIED, saved.verification.touch)
    }

    @Test
    fun sourceOnlyAndSenderOnlyCannotEnableVideoConfirmation() {
        started()
        detection.attach(observation, "attempt-a")
        detection.capabilities(observation, "caps", InfoRequest.GET_EMPTY_PLIST)
        for ((source, sent) in listOf(5L to 0L, 0L to 5L)) {
            detection.progress("attempt-a", "CARWITH", null, source, sent, 0, 0)
            assertFalse(detection.snapshot()!!.canConfirmVideo)
            assertNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
        }
    }

    @Test
    fun audioAndTouchNeedTheirOwnEvidence() {
        streaming()
        detection.progress("attempt-a", "STREAMING", null, 30, 25, 0, 0)
        assertNull(
            detection.prepareSave(ReceiverVerification(ReceiverCheck.CONFIRMED, ReceiverCheck.CONFIRMED), 2000, "test"),
        )
        detection.progress("attempt-a", "STREAMING", null, 30, 25, 10, 9)
        val saved =
            detection.prepareSave(
                ReceiverVerification(ReceiverCheck.CONFIRMED, ReceiverCheck.CONFIRMED, ReceiverCheck.CONFIRMED),
                2000,
                "test",
            )!!
        assertEquals("102|media|8388608|AAC_LC", saved.audioProfile)
        assertEquals(ReceiverCheck.CONFIRMED, saved.verification.touch)
    }

    @Test
    fun knownCapabilitiesAreRequiredToSave() {
        started()
        detection.attach(observation, "attempt-a")
        detection.progress("attempt-a", "STREAMING", null, 20, 20, 0, 0)
        assertNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
    }

    @Test
    fun cancelledTrialCannotCommitOrAcceptLateEvidence() {
        streaming()
        detection.stop("USER_STOP", cancelled = true)
        detection.capabilities(observation, "late-caps", InfoRequest.POST_EMPTY_PLIST)
        detection.audioSelected(observation, "late-audio")
        detection.touch(observation)
        assertEquals("capabilities-a", detection.snapshot()!!.capabilities)
        assertEquals(1L, detection.snapshot()!!.touches)
        assertNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
    }

    @Test
    fun previousEpochAndDifferentReceiverCannotAttach() {
        started()
        assertFalse(detection.attach(observation.copy(binding = "target-b"), "b"))
        assertFalse(detection.attach(observation.copy(epoch = 0), "old"))
        assertNull(detection.snapshot()!!.observation)
        assertTrue(detection.attach(observation, "attempt-a"))
        detection.connectionStarted(2, "attempt-new")
        detection.capabilities(observation, "old", InfoRequest.POST_EMPTY_PLIST)
        assertNull(detection.snapshot()!!.capabilities)
    }

    @Test
    fun duplicateEndpointDoesNotResetCollectedEvidenceOrAdoptAnOlderSession() {
        streaming()
        assertTrue(detection.attach(observation, "attempt-a"))
        assertEquals(25L, detection.snapshot()!!.videoFrames)
        assertFalse(detection.attach(observation.copy(session = 0), "attempt-old"))
        assertEquals("attempt-a", detection.snapshot()!!.attempt)
    }

    @Test
    fun replacedAttemptDoesNotAccumulateOldMediaCounts() {
        started()
        detection.attach(observation, "attempt-a")
        detection.progress("attempt-a", "SETUP", null, 2, 0, 0, 0)
        val next = observation.copy(session = 2)
        detection.attach(next, "attempt-b")
        detection.progress("attempt-a", "STREAMING", null, 1000, 1000, 1000, 1000)
        detection.capabilities(observation, "stale", InfoRequest.POST_EMPTY_PLIST)
        assertEquals(0L, detection.snapshot()!!.sourceFrames)
        assertNull(detection.snapshot()!!.capabilities)
    }

    @Test
    fun bluetoothTrialIsExplicitAndBoundedAndUnavailableAfterEndpoint() {
        started()
        assertEquals(BluetoothBringup.KEEP_PROFILES, detection.snapshot()!!.bluetooth)
        assertTrue(detection.retryBluetooth())
        assertEquals(BluetoothBringup.DISCONNECT_PROFILES, detection.snapshot()!!.bluetooth)
        assertFalse(detection.retryBluetooth())
        detection.stop("USER_STOP", cancelled = true)
        assertTrue(detection.begin("target-a", "env"))
        detection.connectionStarted(2)
        detection.attach(observation.copy(epoch = 2), "attempt-c")
        assertFalse(detection.retryBluetooth())
    }

    @Test
    fun budgetExhaustionIsUnknownAndDoesNotTrainConfiguration() {
        started()
        clock += ReceiverDetection.BUDGET_MS
        assertTrue(detection.timedOut())
        assertFalse(detection.retryBluetooth())
        detection.stop("BUDGET_EXHAUSTED")
        assertNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
        assertEquals("BUDGET_EXHAUSTED", detection.snapshot()!!.issue)
    }

    @Test
    fun healthyMediaIsNotStoppedByProbeBudgetAndDurationStartsAtMedia() {
        streaming()
        clock += 10 * 60_000
        assertFalse(detection.timedOut())
        val saved = detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test")!!
        assertEquals(600L, saved.observedSeconds)
    }

    @Test
    fun saveFailureCanBeRetriedAndOldCompletionCannotFinishNewDraft() {
        streaming()
        val id = detection.snapshot()!!.id
        assertNotNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
        assertFalse(detection.begin("target-b", "env"))
        detection.saved(id, false)
        assertEquals("SAVE_FAILED", detection.snapshot()!!.issue)
        assertNotNull(detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test"))
        detection.saved(id, true)
        assertTrue(detection.begin("target-b", "env"))
        detection.saved(id, true)
        assertEquals(ReceiverDetection.State.COLLECTING, detection.snapshot()!!.state)
    }

    @Test
    fun stoppingConnectionFreezesEvidenceForExplicitPartialSave() {
        streaming()
        clock += 30_000
        detection.stop("WIFI_LOST")
        clock += 60_000
        val saved = detection.prepareSave(ReceiverVerification(video = ReceiverCheck.CONFIRMED), 2000, "test")!!
        assertEquals(30L, saved.observedSeconds)
        assertNull(saved.audioProfile)
    }
}
