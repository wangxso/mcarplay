package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingEndpointWakeTest {
    private data class Observation(
        val name: String,
    )

    private data class Endpoint(
        val observation: Observation,
        val address: String,
    )

    private val a = Observation("A")
    private val b = Observation("B")
    private val c = Observation("C")

    private fun staged(vararg endpoints: Endpoint): PendingHandoffUpdate<Endpoint> =
        PendingHandoffUpdate<Endpoint>({ true }) { it.observation }.also { pending ->
            endpoints.forEach { pending.routeIfActive(it) { false } }
        }

    @Test
    fun deliverCanPutTheCandidateBackAndReportOwnershipOfIt() {
        val pending = staged(Endpoint(b, "b-address"), Endpoint(c, "c-address"))
        var deliveries = 0
        val wake =
            PendingEndpointWake(pending, isActive = { true }, deliver = { candidate ->
                deliveries++
                pending.requeue(candidate)
                true
            })
        assertTrue(wake.wake(prefer = { false }))
        assertEquals(1, deliveries)
        assertEquals(Endpoint(b, "b-address"), pending.takeIf { it.observation === b })
        assertEquals(Endpoint(c, "c-address"), pending.takeIf { it.observation === c })
    }

    @Test
    fun successfulWakeRemovesOnlyTheDeliveredEndpoint() {
        val pending = staged(Endpoint(b, "b-address"), Endpoint(c, "c-address"))
        val delivered = mutableListOf<Endpoint>()
        val wake =
            PendingEndpointWake(pending, isActive = { true }, deliver = {
                delivered += it
                true
            })
        assertTrue(wake.wake(prefer = { false }))
        assertEquals(listOf(Endpoint(b, "b-address")), delivered)
        // C 没有被 takeAll 一并取走：下一次所有权释放还能唤醒它。
        assertEquals(Endpoint(c, "c-address"), pending.takeIf { it.observation === c })
    }

    @Test
    fun rejectedEndpointIsRequeuedForTheNextRelease() {
        val pending = staged(Endpoint(b, "b-address"))
        val wake = PendingEndpointWake(pending, isActive = { true }, deliver = { false })
        assertFalse(wake.wake(prefer = { false }))
        assertEquals(Endpoint(b, "b-address"), pending.takeIf { it.observation === b })
    }

    @Test
    fun closedLinkIsPrunedAndNeverRevived() {
        val pending = staged(Endpoint(b, "b-address"))
        val discarded = mutableListOf<Endpoint>()
        val wake =
            PendingEndpointWake(
                pending,
                isActive = { it.observation !== b },
                deliver = { true },
                discard = discarded::add,
            )
        assertFalse(wake.wake(prefer = { false }))
        assertNull(pending.takeIf { it.observation === b })
        assertEquals(listOf(Endpoint(b, "b-address")), discarded)
    }

    @Test
    fun releasedObservationIsDeliveredBeforeOtherLinks() {
        val pending = staged(Endpoint(b, "b-address"), Endpoint(a, "a-address"))
        val delivered = mutableListOf<Endpoint>()
        val wake =
            PendingEndpointWake(pending, isActive = { true }, deliver = {
                delivered += it
                true
            })
        // A 刚结束交接：它的 late endpoint 必须先于 B 占用新一轮。
        assertTrue(wake.wake(prefer = { it.observation === a }))
        assertEquals(listOf(Endpoint(a, "a-address")), delivered)
        assertEquals(Endpoint(b, "b-address"), pending.takeIf { it.observation === b })
    }

    @Test
    fun requeueDoesNotOverwriteANewerUpdateForTheSameSlot() {
        val pending = staged(Endpoint(a, "old"))
        val candidate = pending.takeIf { it.observation === a }!!
        assertTrue(pending.routeIfActive(Endpoint(a, "new")) { false })
        assertFalse(pending.requeue(candidate))
        assertEquals(Endpoint(a, "new"), pending.takeIf { it.observation === a })
    }

    @Test
    fun deliverCanMergeTheCandidateBackWithoutLosingANewerUpdate() {
        val pending = staged(Endpoint(a, "old"))
        val discarded = mutableListOf<Endpoint>()
        val wake =
            PendingEndpointWake(
                pending,
                isActive = { true },
                deliver = {
                    pending.mergeOrPut(Endpoint(a, "new")) { _, latest -> latest }
                    pending.mergeOrPut(it) { previous, _ -> previous }
                    true
                },
                discard = discarded::add,
            )

        assertTrue(wake.wake(prefer = { false }))

        assertTrue(discarded.isEmpty())
        assertEquals(Endpoint(a, "new"), pending.takeIf { it.observation == a })
    }

    @Test
    fun failedDeliveryDoesNotRequeueACandidateWhoseClaimWasRevoked() {
        val pending = staged(Endpoint(a, "candidate"))
        val discarded = mutableListOf<Endpoint>()
        var claimPending = true
        val wake =
            PendingEndpointWake(
                pending,
                isActive = { claimPending },
                deliver = {
                    claimPending = false
                    false
                },
                discard = discarded::add,
            )

        assertFalse(wake.wake(prefer = { false }))

        assertEquals(listOf(Endpoint(a, "candidate")), discarded)
        assertNull(pending.take())
    }
}
