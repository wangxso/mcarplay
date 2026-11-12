package com.mcarplay.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WifiLowLatencyReconcilerTest {
    @Test
    fun disconnectDuringEnableRestoresAfterEnableCompletes() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val commands = Collections.synchronizedList(mutableListOf<Boolean>())
        var marked = false
        val controller =
            WifiLowLatencyReconciler(
                executor,
                isReady = { true },
                isMarked = { marked },
                setMarker = {
                    marked = it
                    true
                },
                setForced = {
                    commands.add(it)
                    if (it) {
                        assertTrue("marker must precede the enable command", marked)
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    true
                },
            )
        try {
            controller.setDesired(true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.setDesired(false)
            release.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(true, false), commands)
            assertFalse(marked)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun reconnectDuringDisableReenablesAfterOldCommandCompletes() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val commands = Collections.synchronizedList(mutableListOf<Boolean>())
        var marked = false
        val controller =
            WifiLowLatencyReconciler(
                executor,
                isReady = { true },
                isMarked = { marked },
                setMarker = {
                    marked = it
                    true
                },
                setForced = {
                    commands.add(it)
                    if (!it) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    true
                },
            )
        try {
            controller.setDesired(true)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            controller.setDesired(false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.setDesired(true)
            release.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(true, false, true), commands)
            assertTrue(marked)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun lateShizukuReadinessRetriesCrashRecovery() {
        val queued = mutableListOf<Runnable>()
        val commands = mutableListOf<Boolean>()
        var ready = false
        var marked = true
        val controller =
            WifiLowLatencyReconciler(
                Executor { queued.add(it) },
                isReady = { ready },
                isMarked = { marked },
                setMarker = {
                    marked = it
                    true
                },
                setForced = {
                    commands.add(it)
                    true
                },
            )
        controller.retry()
        queued.removeAt(0).run()
        assertTrue(marked)
        assertTrue(commands.isEmpty())

        ready = true
        controller.retry()
        queued.removeAt(0).run()
        assertEquals(listOf(false), commands)
        assertFalse(marked)
    }

    @Test
    fun pendingCrashRecoveryDoesNotDisableANewActiveSession() {
        val queued = mutableListOf<Runnable>()
        val commands = mutableListOf<Boolean>()
        var marked = true
        val controller =
            WifiLowLatencyReconciler(
                Executor { queued.add(it) },
                isReady = { true },
                isMarked = { marked },
                setMarker = {
                    marked = it
                    true
                },
                setForced = {
                    commands.add(it)
                    true
                },
            )
        controller.retry()
        controller.setDesired(true)
        while (queued.isNotEmpty()) queued.removeAt(0).run()
        assertEquals(listOf(true), commands)
        assertTrue(marked)

        controller.setDesired(false)
        queued.removeAt(0).run()
        assertEquals(listOf(true, false), commands)
        assertFalse(marked)
    }

    @Test
    fun failedEnableKeepsRecoveryMarkerAndFailedMarkerPreventsEnable() {
        val queued = mutableListOf<Runnable>()
        val commands = mutableListOf<Boolean>()
        var marked = false
        var saveSucceeds = false
        val controller =
            WifiLowLatencyReconciler(
                Executor { queued.add(it) },
                isReady = { true },
                isMarked = { marked },
                setMarker = {
                    if (saveSucceeds) marked = it
                    saveSucceeds
                },
                setForced = {
                    commands.add(it)
                    !it
                },
            )
        controller.setDesired(true)
        queued.removeAt(0).run()
        assertTrue(commands.isEmpty())

        saveSucceeds = true
        controller.retry()
        queued.removeAt(0).run()
        assertEquals(listOf(true), commands)
        assertTrue(marked)

        controller.setDesired(false)
        queued.removeAt(0).run()
        assertEquals(listOf(true, false), commands)
        assertFalse(marked)
    }
}
