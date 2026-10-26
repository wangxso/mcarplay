package com.mcarplay.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBindingTest {
    @Test
    fun lossWithRecreatedNetworkValueClearsProcessBinding() {
        val environment = Environment()
        val owner = Any()
        val available = environment.liveNetwork(111)
        val lost = TestNetwork(111)
        assertNotSame(available, lost)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, available))

        assertTrue(environment.binding.lost(owner, lost))
        assertNull(environment.binding.current())
        assertEquals(listOf(available, null), environment.bindings)
    }

    @Test
    fun deadNetworkIsNotReusedEvenWhenLossCallbackWasMissed() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))
        environment.liveIds.clear()

        assertNull(environment.binding.current())
        assertNull(environment.binding.current())
        assertEquals(listOf(network, null), environment.bindings)
    }

    @Test
    fun retiredRequestCannotBindOrClearReplacementNetwork() {
        val environment = Environment()
        val oldOwner = Any()
        val newOwner = Any()
        val oldNetwork = environment.liveNetwork(111)
        val newNetwork = environment.liveNetwork(112)
        environment.binding.replaceRequest(oldOwner)
        assertTrue(environment.binding.bind(oldOwner, oldNetwork))
        assertSame(oldOwner, environment.binding.replaceRequest(newOwner))
        assertTrue(environment.binding.bind(newOwner, newNetwork))

        assertFalse(environment.binding.bind(oldOwner, oldNetwork))
        assertFalse(environment.binding.lost(oldOwner, TestNetwork(112)))
        assertSame(newNetwork, environment.binding.current())
        assertEquals(listOf(oldNetwork, null, newNetwork), environment.bindings)
    }

    @Test
    fun failedProcessBindingIsNotReportedAsConnected() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        environment.allowBinding = false

        assertFalse(environment.binding.bind(owner, network))
        assertNull(environment.binding.current())
        assertTrue(environment.bindings.isEmpty())
    }

    @Test
    fun lossOfAnotherNetworkDoesNotClearCurrentBinding() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(112)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))

        assertFalse(environment.binding.lost(owner, TestNetwork(111)))
        assertSame(network, environment.binding.current())
    }

    @Test
    fun releaseRejectsCallbacksThatArriveAfterUnregistration() {
        val environment = Environment()
        val owner = Any()
        val network = environment.liveNetwork(111)
        environment.binding.replaceRequest(owner)
        assertTrue(environment.binding.bind(owner, network))

        assertSame(owner, environment.binding.replaceRequest(null))
        assertFalse(environment.binding.isCurrent(owner))
        assertFalse(environment.binding.bind(owner, network))
        assertNull(environment.binding.current())
        assertEquals(listOf(network, null), environment.bindings)
    }

    private data class TestNetwork(
        val id: Int,
    )

    private class Environment {
        val liveIds = mutableSetOf<Int>()
        val bindings = mutableListOf<TestNetwork?>()
        var allowBinding = true
        val binding =
            NetworkBinding<TestNetwork, Any>(
                bindProcess = { network ->
                    if (network != null && !allowBinding) {
                        false
                    } else {
                        bindings += network
                        true
                    }
                },
                isUsable = { it.id in liveIds },
            )

        fun liveNetwork(id: Int): TestNetwork = TestNetwork(id).also { liveIds += id }
    }
}
