package com.mcarplay.app.carlife

import com.mcarplay.app.carlife.CarLifeSessionRegistry.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CarLifeSessionRegistryTest {
    @Test
    fun diagnosticCannotReplaceRunningFormalLease() {
        val registry = CarLifeSessionRegistry()
        val formal = lease()
        val diagnostic = lease()

        assertTrue(registry.acquire(Owner.FORMAL, formal.token))
        assertFalse(registry.acquire(Owner.DIAGNOSTIC, diagnostic.token))
        assertEquals(formal.token, registry.activeTokenForTest())
        assertEquals(Owner.FORMAL, registry.ownerOf(formal.token))
    }

    @Test
    fun releasingDiagnosticCannotReleaseFormalLease() {
        val registry = CarLifeSessionRegistry()
        val formal = lease()
        val diagnostic = lease()
        registry.acquire(Owner.FORMAL, formal.token)

        assertFalse(registry.release(diagnostic.token))
        assertEquals(formal.token, registry.activeTokenForTest())
    }

    @Test
    fun explicitReleaseAllowsReplacement() {
        val registry = CarLifeSessionRegistry()
        val formal = lease()
        val diagnostic = lease()
        registry.acquire(Owner.FORMAL, formal.token)
        assertTrue(registry.release(formal.token))

        assertTrue(registry.acquire(Owner.DIAGNOSTIC, diagnostic.token))
        assertNull(registry.ownerOf(formal.token))
        assertEquals(diagnostic.token, registry.activeTokenForTest())
    }

    @Test
    fun concurrentClaimsAllowExactlyOneRunningLease() {
        val registry = CarLifeSessionRegistry()
        val leases = List(32) { lease() }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val claims =
            leases.mapIndexed { index, lease ->
                pool.submit<Boolean> {
                    val owner = if (index % 2 == 0) Owner.FORMAL else Owner.DIAGNOSTIC
                    start.await()
                    registry.acquire(owner, lease.token)
                }
            }
        start.countDown()
        val accepted = claims.count { it.get(2, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, accepted)
        assertEquals(1, leases.count { registry.ownerOf(it.token) != null })
    }

    private fun lease(): TestLease = TestLease(CarLifeSessionLease())

    private data class TestLease(
        val token: CarLifeSessionLease,
    )

    private fun CarLifeSessionRegistry.activeTokenForTest(): CarLifeSessionLease? =
        CarLifeSessionRegistry::class.java
            .getDeclaredField("active")
            .apply { isAccessible = true }
            .get(this)
            ?.let { (it as CarLifeSessionRegistry.Lease).token }
}
