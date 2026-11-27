package com.mcarplay.app.airplay

import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.compat.VehiclePreset
import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitStartScreenClaimTest {
    private fun policy(preset: VehiclePreset): ReceiverCompatibility.Policy =
        ReceiverCompatibility.resolve(ReceiverProfile.Signals(), preset = preset)

    private fun entities(arbiter: ModeArbiter) = arbiter.resourcesPayload().associate { it["resourceID"] to it["entity"] }

    private fun initialModes(
        screenConstraint: Long,
        audioTransferType: Long = ModeArbiter.TRANSFER_TAKE.toLong(),
    ) = mapOf(
        "resources" to
            listOf(
                mapOf(
                    "resourceID" to ModeArbiter.SCREEN,
                    "transferType" to ModeArbiter.TRANSFER_TAKE.toLong(),
                    "transferPriority" to ModeArbiter.PRIORITY_USER_INITIATED.toLong(),
                    "takeConstraint" to screenConstraint,
                    "borrowConstraint" to 100L,
                ),
                mapOf(
                    "resourceID" to ModeArbiter.MAIN_AUDIO,
                    "transferType" to audioTransferType,
                    "transferPriority" to ModeArbiter.PRIORITY_USER_INITIATED.toLong(),
                    "takeConstraint" to ModeArbiter.CONSTRAINT_NEVER.toLong(),
                    "borrowConstraint" to 100L,
                ),
            ),
    )

    @Test
    fun onlyGs3PresetEnablesThePolicy() {
        assertTrue(policy(VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW).claimScreenOnExplicitStart)
        VehiclePreset.entries
            .filter { it != VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW }
            .forEach { preset ->
                assertFalse("${preset.name} must not claim the screen on start", policy(preset).claimScreenOnExplicitStart)
            }
    }

    @Test
    fun explicitGs3StartReclaimsA500ScreenWithoutTakingAudio() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val claim = requireNotNull(intent.lease())
        val arbiter = ModeArbiter()
        arbiter.applyInitial(initialModes(500))
        assertFalse(arbiter.phoneOwnsScreen)
        assertFalse("The head unit owns the tested audio resource", arbiter.phoneOwnsAudio)

        val explicitStart = claim.consume()
        assertTrue(explicitStart)
        if (needsExplicitStartScreenClaim(explicitStart, policy(VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW), arbiter.phoneOwnsScreen)) {
            arbiter.onUserRequestedUi(takeAudio = false)
        }

        assertTrue(arbiter.phoneOwnsScreen)
        assertFalse("Explicit start must not take main audio", arbiter.phoneOwnsAudio)
        assertEquals(
            mapOf(ModeArbiter.SCREEN to ModeArbiter.Entity.CONTROLLER.code, ModeArbiter.MAIN_AUDIO to ModeArbiter.Entity.ACCESSORY.code),
            entities(arbiter),
        )
    }

    @Test
    fun constraintsAboveUserInitiatedRemainWithTheHeadUnit() {
        listOf(501L, 1_000L).forEach { constraint ->
            val intent = ExplicitStartScreenClaim()
            intent.manualStart()
            val lease = requireNotNull(intent.lease())
            val arbiter = ModeArbiter()
            arbiter.applyInitial(initialModes(constraint))

            val explicitStart = lease.consume()
            val mustClaim =
                needsExplicitStartScreenClaim(
                    explicitStart,
                    policy(VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW),
                    arbiter.phoneOwnsScreen,
                )
            val claim = if (mustClaim) arbiter.onUserRequestedUi(takeAudio = false) else null

            assertTrue("The explicit-start intent must reach the gauntlet", mustClaim)
            assertFalse("Constraint $constraint must remain with the head unit", claim?.allowed == true)
            assertFalse(arbiter.phoneOwnsScreen)
        }
    }

    @Test
    fun automaticOrNonGs3StartDoesNotClaim() {
        val intent = ExplicitStartScreenClaim()
        assertFalse(intent.lease()?.consume() == true)

        intent.manualStart()
        val genericExplicitStart = requireNotNull(intent.lease()).consume()
        val autoIntent = ExplicitStartScreenClaim()
        val autoExplicitStart = autoIntent.lease()?.consume() == true
        assertFalse(needsExplicitStartScreenClaim(genericExplicitStart, policy(VehiclePreset.GENERIC), phoneOwnsScreen = false))
        assertFalse(
            needsExplicitStartScreenClaim(autoExplicitStart, policy(VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW), phoneOwnsScreen = false),
        )
    }

    @Test
    fun alreadyPhoneOwnedScreenDoesNotTriggerAReleaseRetake() {
        assertFalse(
            needsExplicitStartScreenClaim(
                explicitStart = true,
                policy = policy(VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW),
                phoneOwnsScreen = true,
            ),
        )
    }

    @Test
    fun intentIsConsumedOnceAndClearedByTerminalDisconnect() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val first = requireNotNull(intent.lease())
        assertTrue(first.isPending())
        assertTrue(first.consume())
        assertFalse(first.isPending())
        assertFalse(first.consume())

        intent.manualStart()
        val second = requireNotNull(intent.lease())
        intent.terminalDisconnect()
        assertFalse(second.consume())
    }

    @Test
    fun endedManualTripCannotLeakIntoANewAutomaticStart() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val stale = requireNotNull(intent.lease())

        intent.beginAutomatic(
            automaticStartContinuesManualTrip(
                previousStatus = "failed",
                sessionStarting = false,
                claimRetryInFlight = false,
            ),
        )

        assertFalse(stale.consume())
    }

    @Test
    fun activeManualTripSurvivesAnAutomaticReconnect() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val live = requireNotNull(intent.lease())

        intent.beginAutomatic(
            automaticStartContinuesManualTrip(
                previousStatus = "waiting",
                sessionStarting = false,
                claimRetryInFlight = false,
            ),
        )

        assertFalse(intent.isBluetoothRetryDeferred(live))
        assertTrue(live.consume())
    }

    @Test
    fun retryablePreEndpointBluetoothLossKeepsTheManualClaim() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val live = requireNotNull(intent.lease())

        intent.deferBluetoothRetry(live)
        intent.beginAutomatic(
            automaticStartContinuesManualTrip(
                previousStatus = "failed",
                sessionStarting = false,
                claimRetryInFlight = false,
            ),
        )

        assertTrue(live.consume())
    }

    @Test
    fun exactOwnerBluetoothLossSurvivesAutomaticStartAfterTheTimelineAlreadyFailed() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val live = requireNotNull(intent.lease())
        intent.adopt(live)
        intent.deferBluetoothRetry(live)

        intent.beginAutomatic(
            automaticStartContinuesManualTrip(
                previousStatus = "failed",
                sessionStarting = false,
                claimRetryInFlight = false,
            ),
        )

        assertTrue(intent.isBluetoothRetryDeferred(live))
        assertTrue(live.consume())
    }

    @Test
    fun historicalBluetoothFailureAloneDoesNotLeakTheManualClaim() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val stale = requireNotNull(intent.lease())

        intent.beginAutomatic(
            automaticStartContinuesManualTrip(
                previousStatus = "failed",
                sessionStarting = false,
                claimRetryInFlight = false,
            ),
        )

        assertFalse(stale.consume())
    }

    @Test
    fun deferredBluetoothRetryStaysBoundToTheCanceledAttempt() {
        var now = 0L
        val intent = ExplicitStartScreenClaim { now }
        intent.manualStart()
        val canceledAttempt = requireNotNull(intent.lease())
        intent.deferBluetoothRetry(canceledAttempt)

        now = ExplicitStartScreenClaim.BLUETOOTH_RETRY_GRACE_MILLIS - 1
        assertTrue(intent.isBluetoothRetryDeferred(canceledAttempt))
        intent.beginAutomatic(continuesManualAttempt = false)
        val newAutomaticAttempt = requireNotNull(intent.lease())

        assertTrue(canceledAttempt.isPending())
        assertTrue(intent.isBluetoothRetryDeferred(canceledAttempt))
        assertFalse(
            "A new attempt must not inherit the canceled worker's cleanup fence",
            intent.isBluetoothRetryDeferred(newAutomaticAttempt),
        )

        now = ExplicitStartScreenClaim.BLUETOOTH_RETRY_GRACE_MILLIS
        intent.beginAutomatic(continuesManualAttempt = false)
        assertFalse(canceledAttempt.consume())
    }

    @Test
    fun deferredBluetoothRetryExpiresAtTheDeadline() {
        var now = 0L
        val intent = ExplicitStartScreenClaim { now }
        intent.manualStart()
        val stale = requireNotNull(intent.lease())
        intent.deferBluetoothRetry(stale)

        now = ExplicitStartScreenClaim.BLUETOOTH_RETRY_GRACE_MILLIS
        intent.beginAutomatic(continuesManualAttempt = false)

        assertFalse(stale.consume())
    }

    @Test
    fun canceledPairingCleanupKeepsTheClaimAfterTheAutomaticAttemptStarts() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val canceledAttempt = requireNotNull(intent.lease())
        intent.deferBluetoothRetry(canceledAttempt)
        intent.beginAutomatic(continuesManualAttempt = false)
        val automaticAttempt = requireNotNull(intent.lease())

        val cancelFromOldWorker =
            shouldCancelExplicitStartScreenClaim(
                recovered = false,
                pendingEndpoint = false,
                retryScheduled = false,
                bluetoothRetryDeferred = intent.isBluetoothRetryDeferred(canceledAttempt),
            )
        val cancelFromFailedAutomaticAttempt =
            shouldCancelExplicitStartScreenClaim(
                recovered = false,
                pendingEndpoint = false,
                retryScheduled = false,
                bluetoothRetryDeferred = intent.isBluetoothRetryDeferred(automaticAttempt),
            )

        assertFalse(cancelFromOldWorker)
        assertTrue(cancelFromFailedAutomaticAttempt)
        assertTrue(automaticAttempt.isPending())
    }

    @Test
    fun wildcardPreEndpointDeferIsConsumedBeforeTheAutomaticAttemptCanFail() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        intent.deferBluetoothRetry(null)
        intent.beginAutomatic(continuesManualAttempt = false)
        val automaticAttempt = requireNotNull(intent.lease())

        assertFalse(intent.isBluetoothRetryDeferred(automaticAttempt))
        assertTrue(
            shouldCancelExplicitStartScreenClaim(
                recovered = false,
                pendingEndpoint = false,
                retryScheduled = false,
                bluetoothRetryDeferred = intent.isBluetoothRetryDeferred(automaticAttempt),
            ),
        )
    }

    @Test
    fun oldAttemptCleanupCannotCancelTheAdoptedReplacementAttempt() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val oldAttempt = requireNotNull(intent.lease())
        intent.adopt(oldAttempt)
        intent.deferBluetoothRetry(oldAttempt)
        intent.beginAutomatic(continuesManualAttempt = false)
        val replacementAttempt = requireNotNull(intent.lease())
        intent.adopt(replacementAttempt)

        intent.cancel(oldAttempt)
        intent.adopt(oldAttempt)

        assertFalse(oldAttempt.isPending())
        assertTrue(replacementAttempt.isPending())
        assertTrue(replacementAttempt.consume())
    }

    @Test
    fun newerLeaseSurvivesOldActiveCleanupBeforeItIsAdopted() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val oldAttempt = requireNotNull(intent.lease())
        intent.adopt(oldAttempt)
        val queuedAttempt = requireNotNull(intent.lease())

        assertFalse("A lease that is only queued must not supersede the active attempt", queuedAttempt.isPending())
        assertTrue("A queued lease must remain available for adoption", queuedAttempt.isRetained())
        intent.cancel(oldAttempt)

        assertTrue("The queued attempt must survive the old worker's cleanup", queuedAttempt.isPending())
        intent.adopt(queuedAttempt)
        assertTrue(queuedAttempt.consume())
    }

    @Test
    fun newestAdoptionFencesOlderQueuedCandidates() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val oldAttempt = requireNotNull(intent.lease())
        intent.adopt(oldAttempt)
        val newerAttempt = requireNotNull(intent.lease())
        intent.adopt(newerAttempt)

        intent.cancel(newerAttempt)

        assertFalse("An older queued candidate must not revive after the newer attempt ends", oldAttempt.isPending())
        assertFalse(oldAttempt.consume())
    }

    @Test
    fun overlappingExplicitStartRetriesOnlyCleanUpAfterTheLastOne() {
        val intent = ExplicitStartScreenClaim().apply { manualStart() }
        val lease = requireNotNull(intent.lease())
        val retries = ExplicitStartClaimRetryTracker()
        var idleCleanup: Collection<ExplicitStartScreenClaim.Lease>? = null
        assertTrue(retries.begin(lease))
        assertTrue(retries.begin(lease))

        retries.finish(handedOffNow = false, lease) { idleCleanup = it }
        assertNull(idleCleanup)
        assertTrue(retries.isActive(lease.generation))

        retries.finish(handedOffNow = false, lease) { idleCleanup = it }
        assertEquals(listOf(lease), idleCleanup)
        assertFalse(retries.isActive(lease.generation))
    }

    @Test
    fun overlappingRetrySuccessPreventsFailureCleanupOfTheSameLease() {
        val intent = ExplicitStartScreenClaim().apply { manualStart() }
        val lease = requireNotNull(intent.lease())
        intent.adopt(lease)
        val retries = ExplicitStartClaimRetryTracker()
        var failedLeases: Collection<ExplicitStartScreenClaim.Lease> = emptyList()
        assertTrue(retries.begin(lease))
        assertTrue(retries.begin(lease))

        retries.finish(handedOffNow = false, lease) {}
        retries.finish(handedOffNow = true, lease) { failedLeases = it }

        assertTrue(failedLeases.isEmpty())
        assertTrue(lease.consume())
    }

    @Test
    fun lastFailedRetryCleansUpLeasesRecordedByEarlierOverlappingRetries() {
        val intent = ExplicitStartScreenClaim().apply { manualStart() }
        val inviteLease = requireNotNull(intent.lease())
        val lateEndpointLease = requireNotNull(intent.lease())
        val retries = ExplicitStartClaimRetryTracker()
        var failedLeases: Collection<ExplicitStartScreenClaim.Lease> = emptyList()
        assertTrue(retries.begin(inviteLease))
        assertTrue(retries.begin(lateEndpointLease))

        retries.finish(handedOffNow = false, inviteLease) {}
        retries.finish(handedOffNow = false, lateEndpointLease) { failedLeases = it }
        failedLeases.forEach(intent::cancel)

        assertEquals(setOf(inviteLease, lateEndpointLease), failedLeases.toSet())
        assertFalse(inviteLease.isPending())
        assertFalse(lateEndpointLease.isPending())
    }

    @Test
    fun retryFromAnOldManualGenerationDoesNotKeepTheNewClaimAlive() {
        val intent = ExplicitStartScreenClaim().apply { manualStart() }
        val oldLease = requireNotNull(intent.lease())
        val retries = ExplicitStartClaimRetryTracker()
        assertTrue(retries.begin(oldLease))

        intent.manualStart()
        val newLease = requireNotNull(intent.lease())

        assertTrue(retries.isActive(oldLease.generation))
        assertFalse(retries.isActive(newLease.generation))
    }

    @Test
    fun automaticContinuationLeaseSurvivesTheLastRetryFailure() {
        val intent = ExplicitStartScreenClaim().apply { manualStart() }
        val retryLease = requireNotNull(intent.lease())
        val retries = ExplicitStartClaimRetryTracker()
        assertTrue(retries.begin(retryLease))

        val automaticLease =
            requireNotNull(
                retries.leaseForAutomaticContinuation(
                    generation = intent.generation(),
                    claim = intent,
                    existingLease = null,
                    ordinaryContinuation = false,
                ),
            )
        retries.finish(handedOffNow = false, retryLease) { failedLeases ->
            failedLeases.forEach(intent::cancel)
        }

        assertTrue(automaticLease.isPending())
        intent.adopt(automaticLease)
        assertTrue(automaticLease.consume())
    }

    @Test
    fun queuedAutomaticContinuationSurvivesNormalStartWhileTheOldAttemptIsActive() {
        val intent = ExplicitStartScreenClaim().apply { manualStart() }
        val oldAttempt = requireNotNull(intent.lease())
        intent.adopt(oldAttempt)
        val retries = ExplicitStartClaimRetryTracker()
        assertTrue(retries.begin(oldAttempt))

        val automaticLease =
            requireNotNull(
                retries.leaseForAutomaticContinuation(
                    generation = intent.generation(),
                    claim = intent,
                    existingLease = null,
                    ordinaryContinuation = false,
                ),
            )

        assertFalse(automaticLease.isPending())
        assertTrue(automaticLease.isRetained())
        intent.beginAutomatic(continuesManualAttempt = automaticLease.isRetained())
        assertTrue(automaticLease.isRetained())

        retries.finish(handedOffNow = false, oldAttempt) { failedLeases ->
            failedLeases.forEach(intent::cancel)
        }

        assertTrue(automaticLease.isPending())
        intent.adopt(automaticLease)
        assertTrue(automaticLease.consume())
    }

    @Test
    fun staleAutomaticContinuationIsDroppedWhenNoRetryOrBluetoothWindowRemains() {
        val intent = ExplicitStartScreenClaim().apply { manualStart() }
        val retryLease = requireNotNull(intent.lease())
        val retries = ExplicitStartClaimRetryTracker()
        assertTrue(retries.begin(retryLease))
        val automaticLease =
            requireNotNull(
                retries.leaseForAutomaticContinuation(
                    generation = intent.generation(),
                    claim = intent,
                    existingLease = null,
                    ordinaryContinuation = false,
                ),
            )
        retries.finish(handedOffNow = false, retryLease) { failedLeases ->
            failedLeases.forEach(intent::cancel)
        }

        val stale =
            retries.leaseForAutomaticContinuation(
                generation = intent.generation(),
                claim = intent,
                existingLease = automaticLease,
                ordinaryContinuation = false,
            )

        assertNull(stale)
        assertFalse(automaticLease.isPending())
    }

    @Test
    fun scheduledClaimRetrySurvivesANormalAutomaticStart() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val live = requireNotNull(intent.lease())

        intent.beginAutomatic(
            automaticStartContinuesManualTrip(
                previousStatus = "failed",
                sessionStarting = false,
                claimRetryInFlight = true,
            ),
        )

        assertTrue(live.consume())
    }

    @Test
    fun pairedRetryGovernsWhenTheManualClaimIsCancelled() {
        assertFalse(
            shouldCancelExplicitStartScreenClaim(
                recovered = true,
                pendingEndpoint = false,
                retryScheduled = false,
                bluetoothRetryDeferred = false,
            ),
        )
        assertFalse(
            shouldCancelExplicitStartScreenClaim(
                recovered = false,
                pendingEndpoint = true,
                retryScheduled = false,
                bluetoothRetryDeferred = false,
            ),
        )
        assertFalse(
            shouldCancelExplicitStartScreenClaim(
                recovered = false,
                pendingEndpoint = false,
                retryScheduled = true,
                bluetoothRetryDeferred = false,
            ),
        )
        assertFalse(
            shouldCancelExplicitStartScreenClaim(
                recovered = false,
                pendingEndpoint = false,
                retryScheduled = false,
                bluetoothRetryDeferred = true,
            ),
        )
        assertTrue(
            shouldCancelExplicitStartScreenClaim(
                recovered = false,
                pendingEndpoint = false,
                retryScheduled = false,
                bluetoothRetryDeferred = false,
            ),
        )
    }

    @Test
    fun oldLeaseCannotConsumeASubsequentManualStart() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val old = requireNotNull(intent.lease())
        intent.manualStart()
        val latest = requireNotNull(intent.lease())

        assertFalse(old.consume())
        assertTrue(latest.consume())
    }

    @Test
    fun oldRetryCannotDeferOrPreserveASubsequentManualStart() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val old = requireNotNull(intent.lease())
        intent.manualStart()
        val latest = requireNotNull(intent.lease())

        intent.deferBluetoothRetry(old)
        intent.beginAutomatic(continuesManualAttempt = false)

        assertFalse(latest.consume())
    }

    @Test
    fun expiredBluetoothRetryCannotBeRevivedByADirectEndpoint() {
        var now = 0L
        val intent = ExplicitStartScreenClaim { now }
        intent.manualStart()
        val stale = requireNotNull(intent.lease())
        intent.deferBluetoothRetry(stale)

        now = ExplicitStartScreenClaim.BLUETOOTH_RETRY_GRACE_MILLIS

        intent.adopt(stale)
        assertFalse(stale.consume())
        assertNull(intent.lease())
    }

    @Test
    fun aLateNotificationFromAnOldAttemptCannotReplaceThePreciseDeferForItsReplacement() {
        var now = 0L
        val intent = ExplicitStartScreenClaim { now }
        intent.manualStart()
        val oldAttempt = requireNotNull(intent.lease())
        intent.adopt(oldAttempt)
        intent.deferBluetoothRetry(oldAttempt)

        val replacementAttempt = requireNotNull(intent.lease())
        intent.adopt(replacementAttempt)
        intent.deferBluetoothRetry(replacementAttempt)
        now = 1
        intent.deferBluetoothRetry(oldAttempt)

        assertFalse(intent.isBluetoothRetryDeferred(oldAttempt))
        assertTrue(intent.isBluetoothRetryDeferred(replacementAttempt))
    }

    @Test
    fun staleGenericRetryCannotReplaceAnAlreadyAdoptedAttempt() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val oldAttempt = requireNotNull(intent.lease())
        intent.adopt(oldAttempt)
        intent.deferBluetoothRetry(oldAttempt)

        val replacementAttempt = requireNotNull(intent.lease())
        intent.adopt(replacementAttempt)
        intent.deferBluetoothRetry()

        assertFalse(intent.isBluetoothRetryDeferred(replacementAttempt))
    }

    @Test
    fun genericRetryWrittenBeforeAdoptionIsClearedByAdoption() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val replacementAttempt = requireNotNull(intent.lease())

        intent.deferBluetoothRetry()
        intent.adopt(replacementAttempt)

        assertFalse(intent.isBluetoothRetryDeferred(replacementAttempt))
    }

    @Test
    fun replacementAttemptDoesNotInheritTheOlderAttemptsBluetoothDeadline() {
        var now = 0L
        val intent = ExplicitStartScreenClaim { now }
        intent.manualStart()
        val oldAttempt = requireNotNull(intent.lease())
        intent.adopt(oldAttempt)
        intent.deferBluetoothRetry(oldAttempt)

        val replacementAttempt = requireNotNull(intent.lease())
        intent.adopt(replacementAttempt)
        now = ExplicitStartScreenClaim.BLUETOOTH_RETRY_GRACE_MILLIS

        assertTrue(replacementAttempt.isPending())
        assertTrue(replacementAttempt.consume())
    }

    @Test
    fun replacementAttemptDoesNotInheritAGenericBluetoothDeadline() {
        var now = 0L
        val intent = ExplicitStartScreenClaim { now }
        intent.manualStart()
        intent.deferBluetoothRetry()

        val replacementAttempt = requireNotNull(intent.lease())
        intent.adopt(replacementAttempt)
        now = ExplicitStartScreenClaim.BLUETOOTH_RETRY_GRACE_MILLIS

        assertTrue(replacementAttempt.isPending())
        assertTrue(replacementAttempt.consume())
    }

    @Test
    fun reclaimedScreenLetsTheNextIdrThroughTheGate() {
        val intent = ExplicitStartScreenClaim()
        intent.manualStart()
        val claim = requireNotNull(intent.lease())
        val arbiter = ModeArbiter()
        arbiter.applyInitial(initialModes(500))
        val gate =
            com.mcarplay.app.video
                .ScreenOutputGate()
        assertFalse(gate.admit(arbiter.phoneOwnsScreen, keyFrame = true, nowMs = 0).send)

        val explicitStart = claim.consume()
        if (needsExplicitStartScreenClaim(explicitStart, policy(VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW), arbiter.phoneOwnsScreen)) {
            arbiter.onUserRequestedUi(takeAudio = false)
        }

        assertTrue(gate.admit(arbiter.phoneOwnsScreen, keyFrame = true, nowMs = 16).send)
    }
}
