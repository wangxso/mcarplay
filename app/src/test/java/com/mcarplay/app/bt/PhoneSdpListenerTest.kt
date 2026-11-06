package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhoneSdpListenerTest {
    private class Record : Closeable {
        var closes = 0

        override fun close() {
            closes++
        }
    }

    private val opened = mutableListOf<PhoneSdpRecords<Record>>()
    private var closed = 0
    private val listener =
        PhoneSdpListener(
            open = { PhoneSdpRecords.open { _, _ -> Record() } },
            onOpened = opened::add,
            onClosed = { closed++ },
        )

    @Test
    fun repeatedStartKeepsBothExistingServices() {
        assertTrue(listener.start())
        assertTrue(listener.start())
        assertEquals(1, opened.size)
        assertTrue(listener.isRunning)
        assertNull(listener.recoveryToken())
        assertEquals(0, opened.single().iap2.closes)
        assertEquals(0, opened.single().carPlay.closes)
    }

    @Test
    fun failedListenerReleasesBothRecordsAndCanRecover() {
        listener.start()
        val old = opened.single()
        val token = listener.failed(old)!!
        assertFalse(listener.isRunning)
        assertEquals(1, old.iap2.closes)
        assertEquals(1, old.carPlay.closes)
        assertEquals(1, closed)
        assertTrue(listener.recover(token))
        assertEquals(2, opened.size)
        assertTrue(listener.isCurrent(opened.last()))
    }

    @Test
    fun secondFailureFromClosedPeerCannotRetireReplacement() {
        listener.start()
        val old = opened.single()
        val token = listener.failed(old)!!
        assertTrue(listener.recover(token))
        val replacement = opened.last()
        assertNull(listener.failed(old))
        assertTrue(listener.isCurrent(replacement))
        assertEquals(0, replacement.iap2.closes)
        assertEquals(0, replacement.carPlay.closes)
        assertEquals(1, closed)
    }

    @Test
    fun adapterOffInvalidatesPendingRecoveryAndAllowsAFreshOnEvent() {
        listener.start()
        val pending = listener.failed(opened.single())!!
        listener.adapterUnavailable()
        assertFalse(listener.recover(pending))
        assertFalse(listener.isRunning)
        val enabled = listener.recoveryToken()!!
        assertTrue(listener.recover(enabled))
        assertEquals(2, opened.size)
        listener.adapterUnavailable()
        assertFalse(listener.isRunning)
        assertEquals(1, opened.last().iap2.closes)
        assertEquals(1, opened.last().carPlay.closes)
    }

    @Test
    fun userStopCancelsPendingRecoveryAndFutureAdapterEvents() {
        listener.start()
        val pending = listener.failed(opened.single())!!
        listener.stop()
        assertFalse(listener.recover(pending))
        assertNull(listener.recoveryToken())
        listener.adapterUnavailable()
        assertNull(listener.recoveryToken())
        assertEquals(1, opened.size)
    }

    @Test
    fun oldRecoveryCannotOpenServicesDuringANewUserRequest() {
        listener.start()
        val oldToken = listener.failed(opened.single())!!
        listener.stop()
        listener.start()
        listener.adapterUnavailable()
        assertFalse(listener.recover(oldToken))
        assertTrue(listener.recover(listener.recoveryToken()!!))
        assertEquals(3, opened.size)
    }

    @Test
    fun partialRegistrationFailureRollsBackAndAllowsRecovery() {
        val handles = mutableListOf<Record>()
        var failCapability = true
        val registration =
            PhoneSdpListener(
                open = {
                    PhoneSdpRecords.open { _, uuid ->
                        if (uuid == PhoneSdpRecords.IPHONE_CARPLAY_UUID && failCapability) throw IOException("SDP temporarily unavailable")
                        Record().also(handles::add)
                    }
                },
                onOpened = { _: PhoneSdpRecords<Record> -> },
                onClosed = {},
            )
        try {
            registration.start()
            fail("capability registration should fail")
        } catch (_: IOException) {
            assertFalse(registration.isRunning)
        }
        assertEquals(1, handles.single().closes)
        failCapability = false
        assertTrue(registration.recover(registration.recoveryToken()!!))
        assertEquals(3, handles.size)
        assertEquals(0, handles.last().closes)
        registration.stop()
        assertEquals(1, handles.last().closes)
    }

    @Test
    fun stoppedRecordsAndTheirLateFailureAreClosedOnlyOnce() {
        listener.start()
        val records = opened.single()
        listener.stop()
        listener.stop()
        assertNull(listener.failed(records))
        assertFalse(listener.isRunning)
        assertEquals(1, records.iap2.closes)
        assertEquals(1, records.carPlay.closes)
        assertEquals(1, closed)
    }

    @Test
    fun persistentRegistrationFailureStopsAfterThreeAutomaticAttempts() {
        var attempts = 0
        val registration =
            PhoneSdpListener<Closeable>(
                open = {
                    attempts++
                    throw IOException("Bluetooth unavailable")
                },
                onOpened = {},
                onClosed = {},
            )
        try {
            registration.start()
            fail("registration should fail")
        } catch (_: IOException) {
            // 首次用户请求失败后，允许最多三次后台恢复。
        }
        val token = registration.recoveryToken()!!
        repeat(3) {
            try {
                registration.recover(token)
                fail("recovery should fail")
            } catch (_: IOException) {
                // 注册失败也消耗恢复额度，不能永久循环。
            }
        }
        assertEquals(4, attempts)
        assertNull(registration.recoveryToken())
        assertFalse(registration.recover(token))
        assertEquals(4, attempts)
        registration.adapterUnavailable()
        assertTrue(registration.recoveryToken() != null)
        registration.stop()
        assertNull(registration.recoveryToken())
    }

    @Test
    fun immediateAcceptFailuresShareTheSameRecoveryBudget() {
        listener.start()
        repeat(3) {
            val token = listener.failed(opened.last())!!
            assertTrue(listener.recover(token))
        }
        assertNull(listener.failed(opened.last()))
        assertEquals(4, opened.size)
        assertFalse(listener.isRunning)
        assertNull(listener.recoveryToken())
        assertTrue(opened.all { it.iap2.closes == 1 && it.carPlay.closes == 1 })
        assertTrue(listener.start()) // 显式用户重试才能重新打开恢复窗口。
        assertEquals(5, opened.size)
    }

    @Test
    fun concurrentStartRequestsRegisterOnlyOnePair() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registrations = AtomicInteger()
        val starts = AtomicInteger()
        val registration =
            PhoneSdpListener(
                open = {
                    registrations.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    PhoneSdpRecords.open { _, _ -> Record() }
                },
                onOpened = { _: PhoneSdpRecords<Record> -> starts.incrementAndGet() },
                onClosed = {},
            )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit(Callable { registration.start() })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = workers.submit(Callable { registration.start() })
            release.countDown()
            assertTrue(first.get(2, TimeUnit.SECONDS))
            assertTrue(second.get(2, TimeUnit.SECONDS))
            assertEquals(1, registrations.get())
            assertEquals(1, starts.get())
            assertTrue(registration.isRunning)
        } finally {
            release.countDown()
            registration.stop()
            workers.shutdownNow()
        }
    }
}
