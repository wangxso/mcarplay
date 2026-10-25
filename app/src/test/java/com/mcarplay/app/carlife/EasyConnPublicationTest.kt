package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class EasyConnPublicationTest {
    @Test
    fun repeatedIdentityKeepsOneRegistrationAndOneMulticastLease() {
        val fixture = Fixture()
        assertTrue(fixture.publication.start(identity()))
        assertTrue(fixture.publication.start(identity()))
        assertEquals(listOf("acquire", "open", "register"), fixture.events)
        fixture.publication.stop()
        fixture.publication.stop()
        assertEquals(listOf("acquire", "open", "register", "close", "release"), fixture.events)
        assertFalse(fixture.publication.published)
    }

    @Test
    fun changedAddressClosesOldRegistrationBeforeOpeningNewOne() {
        val fixture = Fixture()
        fixture.publication.start(identity())
        assertTrue(fixture.publication.start(identity("192.168.49.2")))
        assertEquals(listOf("acquire", "open", "register", "close", "release", "acquire", "open", "register"), fixture.events)
        fixture.publication.stop()
    }

    @Test
    fun registerFailureClosesPartiallyOpenedMdnsAndMulticastLease() {
        val fixture = Fixture()
        fixture.failRegister = true
        assertFalse(fixture.publication.start(identity()))
        assertFalse(fixture.publication.published)
        assertEquals(listOf("acquire", "open", "register", "close", "release"), fixture.events)
        fixture.failRegister = false
        assertTrue(fixture.publication.start(identity()))
        fixture.publication.stop()
    }

    @Test
    fun openFailureReleasesMulticastLease() {
        val fixture = Fixture()
        fixture.failOpen = true
        assertFalse(fixture.publication.start(identity()))
        assertEquals(listOf("acquire", "open", "release"), fixture.events)
    }

    @Test
    fun closeFailureStillReleasesMulticastLease() {
        val fixture = Fixture()
        fixture.publication.start(identity())
        fixture.failClose = true
        fixture.publication.stop()
        assertFalse(fixture.publication.published)
        assertEquals(listOf("acquire", "open", "register", "close", "release"), fixture.events)
    }

    @Test
    fun losingIdentityWithdrawsOldPublication() {
        val fixture = Fixture()
        fixture.publication.start(identity())
        assertFalse(fixture.publication.start(identity().copy(ipv4Address = null)))
        assertFalse(fixture.publication.published)
        assertEquals(listOf("acquire", "open", "register", "close", "release"), fixture.events)
    }

    @Test
    fun txtChangeAtSameAddressReplacesRegistration() {
        val fixture = Fixture()
        fixture.publication.start(identity())
        assertTrue(fixture.publication.start(identity().copy(deviceName = "new-name")))
        assertEquals(2, fixture.events.count { it == "register" })
        assertEquals(1, fixture.events.count { it == "close" })
        fixture.publication.stop()
    }

    private class Fixture {
        val events = mutableListOf<String>()
        var failOpen = false
        var failRegister = false
        var failClose = false
        val publication =
            EasyConnPublication(
                acquireMulticast = {
                    events += "acquire"
                    AutoCloseable { events += "release" }
                },
                openRegistration = {
                    events += "open"
                    check(!failOpen) { "open failed" }
                    object : EasyConnPublication.Registration {
                        override fun register() {
                            events += "register"
                            check(!failRegister) { "register failed" }
                        }

                        override fun close() {
                            events += "close"
                            check(!failClose) { "close failed" }
                        }
                    }
                },
                log = {},
            )
    }

    private fun identity(address: String = "192.168.49.1") =
        ExternalHuDiscoveryProtocol.ServiceIdentity(
            instanceName = "EasyConn-test",
            deviceName = "HU-test",
            macAddress = "02:11:22:33:44:55",
            ipv4Address = InetAddress.getByName(address) as Inet4Address,
        )
}
