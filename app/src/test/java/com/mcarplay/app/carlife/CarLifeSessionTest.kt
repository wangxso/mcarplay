package com.mcarplay.app.carlife

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CarLifeSessionTest {
    @Test
    fun stoppingSleepingHeartbeatDoesNotReachUncaughtHandler() {
        repeat(20) {
            val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
            val running = field("running").get(session) as AtomicBoolean
            running.set(true)
            CarLifeSession::class.java
                .getDeclaredMethod("startHeartbeat")
                .apply { isAccessible = true }
                .invoke(session)
            val heartbeat = field("heartbeatThread").get(session) as Thread
            val uncaught = AtomicReference<Throwable?>()
            heartbeat.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (heartbeat.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertTrue("Heartbeat must be sleeping before cancellation", heartbeat.state == Thread.State.TIMED_WAITING)
            } finally {
                session.stop()
                heartbeat.join(2_000)
            }
            assertFalse("Stopped heartbeat must terminate", heartbeat.isAlive)
            assertFalse(session.isRunning)
            assertNull("Normal stop must not produce a FATAL exception", uncaught.get())
            assertNull(field("heartbeatThread").get(session))
            session.stop()
        }
    }

    @Test
    fun heartbeatStartedAfterStopExitsWithoutSending() {
        val session = CarLifeSession(Application(), onAccessUnit = {}, onEnded = {}, log = {})
        CarLifeSession::class.java
            .getDeclaredMethod("startHeartbeat")
            .apply { isAccessible = true }
            .invoke(session)
        val heartbeat = field("heartbeatThread").get(session) as Thread
        heartbeat.join(2_000)
        assertFalse(heartbeat.isAlive)
        assertFalse(session.isRunning)
    }

    private fun field(name: String) = CarLifeSession::class.java.getDeclaredField(name).apply { isAccessible = true }
}
