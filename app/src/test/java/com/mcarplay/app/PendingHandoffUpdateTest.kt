package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PendingHandoffUpdateTest {
    @Test
    fun completeEndpointReceivedBeforeFailureTakesOverWithoutReleasing() {
        val active = AtomicBoolean(true)
        val pending = PendingHandoffUpdate<String>({ active.get() })
        assertTrue(pending.routeIfActive("complete") { false })
        val replacement = pending.takeIfOrRelease({ it == "complete" }) { active.set(false) }
        assertEquals("complete", replacement)
        assertTrue(active.get())
        assertNull(pending.take())
    }

    @Test
    fun endpointArrivingDuringReleaseStartsAfterOwnershipWasReleased() {
        val active = AtomicBoolean(true)
        val pending = PendingHandoffUpdate<String>({ active.get() })
        val releaseEntered = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val offerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val failed =
                executor.submit<String?> {
                    pending.takeIfOrRelease({ true }) {
                        releaseEntered.countDown()
                        check(finishRelease.await(5, TimeUnit.SECONDS))
                        active.set(false)
                    }
                }
            assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
            val late =
                executor.submit<Boolean> {
                    offerStarted.countDown()
                    pending.routeIfActive("complete") { false }
                }
            assertTrue(offerStarted.await(5, TimeUnit.SECONDS))
            finishRelease.countDown()
            assertNull(failed.get(5, TimeUnit.SECONDS))
            assertFalse(late.get(5, TimeUnit.SECONDS))
            assertNull(pending.take())
        } finally {
            finishRelease.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun incompleteOrDifferentObservationDoesNotTakeOver() {
        val active = AtomicBoolean(true)
        val pending = PendingHandoffUpdate<String>({ active.get() })
        assertTrue(pending.routeIfActive("old observation") { false })
        assertNull(pending.takeIfOrRelease({ it == "current complete" }) { active.set(false) })
        assertFalse(active.get())
        pending.retainIf { it == "current complete" }
        assertNull(pending.take())
    }

    @Test
    fun successiveUpdatesCanPreservePreviouslyReceivedInformation() {
        val pending = PendingHandoffUpdate<Pair<String?, String?>>({ true })
        val merge = { previous: Pair<String?, String?>, latest: Pair<String?, String?> ->
            (latest.first ?: previous.first) to (latest.second ?: previous.second)
        }
        assertTrue(pending.routeIfActive("wifi credentials" to null, merge) { false })
        assertTrue(pending.routeIfActive(null to "airplay address", merge) { false })
        assertEquals("wifi credentials" to "airplay address", pending.take())
    }

    private data class Observation(
        val name: String,
    )

    private data class Endpoint(
        val observation: Observation,
        val address: String,
    )

    @Test
    fun otherObservationEndpointIsNotMergedIntoTheActiveHandoff() {
        val a = Observation("A")
        val b = Observation("B")
        val active = AtomicBoolean(true)
        val pending = PendingHandoffUpdate<Endpoint>({ active.get() }) { it.observation }
        // A 的交接尚未结束，B 的 0x4301 先到：必须留在 B 自己的槽位。
        assertTrue(pending.routeIfActive(Endpoint(b, "b-address")) { false })
        // A 的配对客户端 drain 时不能把 B 的地址/observation 写进 A。
        assertNull(pending.takeIf { it.observation === a })
        // A 释放后 B 仍可被单独取走，不会无人消费。
        assertEquals(listOf(Endpoint(b, "b-address")), pending.takeAll { it.observation === b })
        assertNull(pending.take())
    }

    @Test
    fun differentObservationsStaySeparateWhileTheSameObservationMerges() {
        val a = Observation("A")
        val b = Observation("B")
        val pending = PendingHandoffUpdate<Endpoint>({ true }) { it.observation }
        assertTrue(pending.routeIfActive(Endpoint(a, "a-old"), { _, latest -> latest }) { false })
        assertTrue(pending.routeIfActive(Endpoint(a, "a-new"), { _, latest -> latest }) { false })
        assertTrue(pending.routeIfActive(Endpoint(b, "b-only"), { _, latest -> latest }) { false })
        assertEquals(Endpoint(a, "a-new"), pending.takeIf { it.observation === a })
        assertEquals(Endpoint(b, "b-only"), pending.takeIf { it.observation === b })
        assertNull(pending.take())
    }

    @Test
    fun onlyStaleObservationsAreDiscardedWhenANewHandoffStarts() {
        val stale = Observation("stale")
        val current = Observation("current")
        val pending = PendingHandoffUpdate<Endpoint>({ true }) { it.observation }
        assertTrue(pending.routeIfActive(Endpoint(stale, "stale-address")) { false })
        assertTrue(pending.routeIfActive(Endpoint(current, "current-address")) { false })
        // 新一轮交接开始时清理失效轮次，但保留仍在 current 的另一条 observation。
        pending.retainIf { it.observation !== stale }
        assertNull(pending.takeIf { it.observation === stale })
        assertEquals(Endpoint(current, "current-address"), pending.takeIf { it.observation === current })
    }

    @Test
    fun directDeliveryRequiresTheSameObservation() {
        val a = Observation("A")
        val b = Observation("B")
        val pending = PendingHandoffUpdate<Endpoint>({ true }) { it.observation }
        assertTrue(pending.sameSlot(Endpoint(a, "a-address"), Endpoint(a, "a-new")))
        assertFalse(pending.sameSlot(Endpoint(a, "a-address"), Endpoint(b, "b-address")))
        assertFalse(pending.sameSlot(null, Endpoint(b, "b-address")))
        // 单槽位模式（无 observation 键）保持旧的“任意更新”语义。
        assertTrue(PendingHandoffUpdate<String>({ true }).sameSlot("old", "new"))
    }

    @Test
    fun lateEndpointFromAnotherObservationStaysPendingWhileAPairingClientIsActive() {
        val a = Observation("A")
        val b = Observation("B")
        val active = AtomicBoolean(true)
        val pending = PendingHandoffUpdate<Endpoint>({ active.get() }) { it.observation }
        val pairingClient = Endpoint(a, "a-address")
        // A 的配对进行中收到 B 的 0x4301：直接投递必须被 observation 校验拒绝。
        assertTrue(
            pending.routeIfActive(Endpoint(b, "b-address")) {
                pending.sameSlot(pairingClient, Endpoint(b, "b-address"))
            },
        )
        // B 留在自己的槽位，A 的热更新路径不能把它写进 A 的客户端。
        assertNull(pending.takeIf { it.observation === a })
        assertEquals(Endpoint(b, "b-address"), pending.takeIf { it.observation === b })
    }
}
