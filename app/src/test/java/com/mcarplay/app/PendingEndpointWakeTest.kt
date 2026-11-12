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
    fun busyHandoffPutsTheCandidateBackInsteadOfDroppingIt() {
        val pending = staged(Endpoint(b, "b-address"), Endpoint(c, "c-address"))
        var deliveries = 0
        val wake =
            PendingEndpointWake(pending, isActive = { true }, isBusy = { true }, deliver = {
                deliveries++
                true
            })
        assertFalse(wake.wake(prefer = { false }))
        assertEquals(0, deliveries)
        assertEquals(Endpoint(b, "b-address"), pending.takeIf { it.observation === b })
        assertEquals(Endpoint(c, "c-address"), pending.takeIf { it.observation === c })
    }

    @Test
    fun successfulWakeRemovesOnlyTheDeliveredEndpoint() {
        val pending = staged(Endpoint(b, "b-address"), Endpoint(c, "c-address"))
        val delivered = mutableListOf<Endpoint>()
        val wake =
            PendingEndpointWake(pending, isActive = { true }, isBusy = { false }, deliver = {
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
        val wake = PendingEndpointWake(pending, isActive = { true }, isBusy = { false }, deliver = { false })
        assertFalse(wake.wake(prefer = { false }))
        assertEquals(Endpoint(b, "b-address"), pending.takeIf { it.observation === b })
    }

    @Test
    fun closedLinkIsPrunedAndNeverRevived() {
        val pending = staged(Endpoint(b, "b-address"))
        val wake = PendingEndpointWake(pending, isActive = { it.observation !== b }, isBusy = { false }, deliver = { true })
        assertFalse(wake.wake(prefer = { false }))
        assertNull(pending.takeIf { it.observation === b })
    }

    @Test
    fun releasedObservationIsDeliveredBeforeOtherLinks() {
        val pending = staged(Endpoint(b, "b-address"), Endpoint(a, "a-address"))
        val delivered = mutableListOf<Endpoint>()
        val wake =
            PendingEndpointWake(pending, isActive = { true }, isBusy = { false }, deliver = {
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
        pending.requeue(candidate)
        assertEquals(Endpoint(a, "new"), pending.takeIf { it.observation === a })
    }
}
