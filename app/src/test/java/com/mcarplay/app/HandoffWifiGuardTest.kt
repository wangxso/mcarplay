package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HandoffWifiGuardTest {
    @Test
    fun cancellationAfterVerificationPreventsBindingAndDiscovery() {
        val guard = HandoffWifiGuard()
        val cancelled = AtomicBoolean(true)
        val operations = mutableListOf<String>()
        val result =
            guard.bind(
                isCancelled = cancelled::get,
                connect = {
                    operations += "bind"
                    true
                },
                release = { operations += "release" },
                onBound = { operations += "advertise" },
            )
        assertFalse(result)
        assertTrue(operations.isEmpty())
        assertFalse(guard.runIfActive(cancelled::get) { operations += "discover" })
        assertTrue(operations.isEmpty())
    }

    @Test
    fun cancellationDuringBindingReleasesBeforeCleanupAndNeverAdvertises() {
        val guard = HandoffWifiGuard()
        val cancelled = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val finishBind = CountDownLatch(1)
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val binding =
                executor.submit<Boolean> {
                    guard.bind(
                        isCancelled = cancelled::get,
                        connect = {
                            operations += "bind"
                            entered.countDown()
                            check(finishBind.await(5, TimeUnit.SECONDS))
                            true
                        },
                        release = { operations += "release" },
                        onBound = { operations += "advertise" },
                    )
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            val cleanup = executor.submit { guard.cleanup { operations += "cleanup" } }
            finishBind.countDown()
            assertFalse(binding.get(5, TimeUnit.SECONDS))
            cleanup.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("bind", "release", "cleanup"), operations)
        } finally {
            finishBind.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun invitedNetworkCannotBeReplacedByAnotherWifi() {
        val guard = HandoffWifiGuard()
        assertTrue(guard.matchesRequiredNetwork("wifi-a", "wifi-a"))
        assertFalse(guard.matchesRequiredNetwork("wifi-a", "wifi-b"))
        assertFalse(guard.matchesRequiredNetwork("wifi-a", null))
        assertTrue(guard.matchesRequiredNetwork(null, "wifi-b"))
    }
}
