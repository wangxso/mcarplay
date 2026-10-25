package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ExternalHuP2pReadinessTest {
    @Test
    fun incompleteIdentityNeverPublishesAndCanBecomeReadyLater() {
        val readiness = ExternalHuP2pReadiness()
        assertNull(readiness.update(true, true, identity().copy(macAddress = "02:00:00:00:00:00"), "DIRECT-test").ready)
        assertNull(readiness.current)
        assertNull(readiness.update(true, true, identity(), " ").ready)
        assertNotNull(readiness.update(true, true, identity(), "DIRECT-test").ready)
    }

    @Test
    fun duplicateCallbacksDoNotRepublishButAddressOrSsidChangesDo() {
        val readiness = ExternalHuP2pReadiness()
        assertNotNull(readiness.update(true, true, identity(), "DIRECT-test").ready)
        assertNull(readiness.update(true, true, identity(), "DIRECT-test").ready)
        val changed = readiness.update(true, true, identity("192.168.50.1"), "DIRECT-new").ready
        assertEquals("192.168.50.1", changed?.identity?.ipv4Address?.hostAddress)
        assertEquals("DIRECT-new", changed?.advertisement?.network?.ssid)
    }

    @Test
    fun groupLossClearsReadinessAndSameAddressCanRepublish() {
        val readiness = ExternalHuP2pReadiness()
        readiness.update(true, true, identity(), "DIRECT-test")
        assertTrue(readiness.update(false, false, identity(), "DIRECT-test").lost)
        assertNull(readiness.current)
        assertFalse(readiness.update(false, false, null, "").lost)
        assertNotNull(readiness.update(true, true, identity(), "DIRECT-test").ready)
    }

    @Test
    fun losingGroupOwnershipWithdrawsAnnouncement() {
        val readiness = ExternalHuP2pReadiness()
        readiness.update(true, true, identity(), "DIRECT-test")
        val changed = readiness.update(true, false, identity(), "DIRECT-test")
        assertTrue(changed.lost)
        assertNull(readiness.current)
        assertEquals(ExternalHuDiscoveryProtocol.GroupState.NOT_OWNER, changed.evaluation.state)
    }

    @Test
    fun stoppingResetsAnnouncementAndClearIsIdempotent() {
        val readiness = ExternalHuP2pReadiness()
        readiness.update(true, true, identity(), "DIRECT-test")
        assertTrue(readiness.clear())
        assertFalse(readiness.clear())
        assertNotNull(readiness.update(true, true, identity(), "DIRECT-test").ready)
    }

    private fun identity(address: String = "192.168.49.1") =
        ExternalHuDiscoveryProtocol.Identity(
            deviceName = "HU-test",
            macAddress = "02:11:22:33:44:55",
            interfaceName = "p2p-test",
            ipv4Address = InetAddress.getByName(address) as Inet4Address,
        )
}
