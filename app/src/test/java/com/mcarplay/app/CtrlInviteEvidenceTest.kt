package com.mcarplay.app

import com.mcarplay.app.compat.ReceiverObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CtrlInviteEvidenceTest {
    private val evidence = CtrlInviteEvidence(1_000L, "receiver", "192.0.2.1", "wifi-a")

    private fun verify(
        value: CtrlInviteEvidence<String> = evidence,
        now: Long = 2_000L,
        target: String? = "receiver",
        currentNetwork: String? = "wifi-a",
        addressNetwork: String? = "wifi-a",
    ): String? =
        value.verifiedNetwork(now, 30_000L, target, currentNetwork) { address ->
            if (address == "192.0.2.1") addressNetwork else null
        }

    @Test
    fun acceptsOnlyTheFreshInvitationOnItsActualCurrentNetworkAndTarget() {
        assertEquals("wifi-a", verify())
        assertNull(verify(now = 31_001L))
        assertNull(verify(now = 999L))
        assertNull(verify(target = "other receiver"))
        assertNull(verify(currentNetwork = "wifi-b"))
        assertNull(verify(currentNetwork = null))
        assertNull(verify(addressNetwork = "wifi-b"))
        assertNull(verify(addressNetwork = null))
    }

    @Test
    fun doesNotGuessMissingTargetAddressOrNetwork() {
        assertNull(verify(evidence.copy(target = null), target = null))
        assertNull(verify(evidence.copy(localAddress = null)))
        assertNull(verify(evidence.copy(network = null)))
    }

    @Test
    fun autoSelectedInviteMustMatchTheActualRfcommObservation() {
        val observation = ReceiverObservation("receiver", 1, 7)
        val invite = evidence.copy(observation = observation)

        fun verified(current: ReceiverObservation?) =
            invite.verifiedNetwork(2_000L, 30_000L, "receiver", "wifi-a", current, true) { "wifi-a" }
        assertEquals("wifi-a", verified(observation))
        assertNull(verified(null))
        assertNull(verified(observation.copy(session = 8)))
        assertNull(evidence.verifiedNetwork(2_000L, 30_000L, "receiver", "wifi-a", observation, true) { "wifi-a" })
    }
}
