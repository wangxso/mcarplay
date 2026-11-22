package com.mcarplay.app.bt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcarplay.app.debug.ReconnectPrefsServiceShared
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Rgp08CrossProcessPrefsTest {
    @Test
    fun verifiesColdProductionReadAfterCrossProcessCommit() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId =
            arguments.getString(ARG_RUN_ID)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing unique runId argument")
        val marker =
            arguments.getString(ARG_MARKER)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing unique marker argument")
        require(marker == "$runId-rgp08") { "RGP08 marker mismatch: marker=$marker runId=$runId" }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = ReconnectPrefsInstrumentationTestSupport.settings(context)
        val writerPid = Process.myPid()
        val events = JSONArray()

        try {
            val s0 =
                JSONObject()
                    .put("address", PEER)
                    .put("failures", 1)
                    .put("firstFailureAtMs", 1_000L)
                    .put("lastFailureAtMs", 1_000L)
                    .put("blockedUntilMs", S0_BLOCKED_UNTIL_MS)
                    .put("recordedAttempt", "$marker-s0")
            val s1 =
                JSONObject()
                    .put("address", PEER)
                    .put("failures", 2)
                    .put("firstFailureAtMs", 1_000L)
                    .put("lastFailureAtMs", 2_000L)
                    .put("blockedUntilMs", S1_BLOCKED_UNTIL_MS)
                    .put("recordedAttempt", "$marker-s1")

            assertTrue(
                "Baseline fixture commit failed",
                prefs
                    .edit()
                    .putString(ReconnectPrefsInstrumentationTestSupport.UNRELATED_KEY, SENTINEL)
                    .putString(KEY_STATES, JSONObject().put(PEER.uppercase(), s0).toString())
                    .putString(HANDOFF_KEY, marker)
                    .commit(),
            )
            events.put(
                JSONObject()
                    .put("name", "BASELINE_COMMIT")
                    .put("value", "S0")
                    .put("commit", true),
            )

            // A process can answer INFO before its first production read completes under TCG.
            ProbeConnection(context, WARM_SERVICE).use { warm ->
                val warmInfo = warm.info
                val warmReadS0 = warm.read(PEER, FIXED_NOW_MS, replyTimeoutSeconds = PROBE_STARTUP_REPLY_TIMEOUT_SECONDS)
                assertTrue(warmInfo.pid != writerPid)
                assertEquals(Process.myUid(), warmInfo.uid)
                assertEquals(
                    AutoReconnectGuard.Block(1, S0_BLOCKED_UNTIL_MS, 60_000L),
                    warmReadS0,
                )
                assertEquals(
                    AutoReconnectGuard.Block(1, S0_BLOCKED_UNTIL_MS, 60_000L),
                    warm.activeBlock(FIXED_NOW_MS),
                )
                events.put(
                    JSONObject()
                        .put("name", "WARM_READY")
                        .put("process", "warm")
                        .put("pid", warmInfo.pid)
                        .put("uid", warmInfo.uid)
                        .put("token", warmInfo.token)
                        .put("read", "S0"),
                )

                assertTrue(
                    "S1 fixture commit failed",
                    prefs
                        .edit()
                        .putString(KEY_STATES, JSONObject().put(PEER.uppercase(), s1).toString())
                        .putString(HANDOFF_KEY, marker)
                        .commit(),
                )
                val s1AfterMs = SystemClock.elapsedRealtime()
                events.put(
                    JSONObject()
                        .put("name", "S1_COMMIT")
                        .put("value", "S1")
                        .put("commit", true)
                        .put("s1AfterMs", s1AfterMs),
                )

                val warmObservation = warm.read(PEER, FIXED_NOW_MS)
                assertEquals(warmInfo.pid, warm.observedPid)
                assertEquals(warmInfo.token, warm.observedToken)
                assertEquals(warmReadS0, warmObservation)
                events.put(
                    JSONObject()
                        .put("name", "WARM_OBSERVATION")
                        .put("pid", warm.observedPid)
                        .put("token", warm.observedToken)
                        .put("read", "S0"),
                )

                ProbeConnection(context, COLD_SERVICE).use { cold ->
                    val coldInfo = cold.info
                    val coldRead = cold.read(PEER, FIXED_NOW_MS, replyTimeoutSeconds = PROBE_STARTUP_REPLY_TIMEOUT_SECONDS)
                    assertTrue(coldInfo.pid != writerPid && coldInfo.pid != warmInfo.pid)
                    assertEquals(Process.myUid(), coldInfo.uid)
                    assertTrue(
                        "Cold process started before S1 commit returned: ${coldInfo.startElapsedMs} < $s1AfterMs",
                        coldInfo.startElapsedMs >= s1AfterMs,
                    )
                    assertEquals(
                        AutoReconnectGuard.Block(2, S1_BLOCKED_UNTIL_MS, 180_000L),
                        coldRead,
                    )
                    assertEquals(coldInfo.pid, cold.observedPid)
                    assertEquals(coldInfo.token, cold.observedToken)
                    assertEquals(
                        AutoReconnectGuard.Block(2, S1_BLOCKED_UNTIL_MS, 180_000L),
                        cold.activeBlock(FIXED_NOW_MS),
                    )
                    assertEquals(coldInfo.pid, cold.observedPid)
                    assertEquals(coldInfo.token, cold.observedToken)
                    events.put(
                        JSONObject()
                            .put("name", "COLD_READY")
                            .put("process", "cold")
                            .put("pid", coldInfo.pid)
                            .put("uid", coldInfo.uid)
                            .put("token", coldInfo.token)
                            .put("startElapsedMs", coldInfo.startElapsedMs)
                            .put("read", "S1"),
                    )
                }
            }

            events.put(
                JSONObject()
                    .put("name", "SENTINEL_BEFORE_CLEANUP")
                    .put("value", SENTINEL),
            )
            assertEquals(
                SENTINEL,
                prefs.getString(ReconnectPrefsInstrumentationTestSupport.UNRELATED_KEY, null),
            )

            val outputJson = writeEvents(context, marker, writerPid, events)
            instrumentation.sendStatus(0, Bundle().apply { putString("rgp08_output", outputJson) })
        } finally {
            assertTrue(
                "Cleanup fixture commit failed",
                prefs
                    .edit()
                    .remove(KEY_STATES)
                    .remove(LEGACY_KEYS.first())
                    .remove(LEGACY_KEYS[1])
                    .remove(LEGACY_KEYS[2])
                    .remove(LEGACY_KEYS[3])
                    .remove(LEGACY_KEYS[4])
                    .remove(LEGACY_KEYS[5])
                    .remove(HANDOFF_KEY)
                    .remove(ReconnectPrefsInstrumentationTestSupport.UNRELATED_KEY)
                    .commit(),
            )
        }
    }

    private fun writeEvents(
        context: Context,
        marker: String,
        writerPid: Int,
        events: JSONArray,
    ): String {
        val output =
            JSONObject()
                .put("testId", "rgp08-cross-process-prefs")
                .put("marker", marker)
                .put("writerPid", writerPid)
                .put("events", events)
        val outputJson = output.toString()
        val file = File(context.filesDir, OUTPUT_FILE)
        FileOutputStream(file).use { stream ->
            stream.write(outputJson.toByteArray(Charsets.UTF_8))
            stream.write('\n'.code)
            stream.fd.sync()
        }
        check(JSONObject(outputJson).getString("marker") == marker) {
            "RGP08 output marker mismatch"
        }
        return outputJson
    }

    private class ProbeConnection(
        private val context: Context,
        component: ComponentName,
    ) : AutoCloseable {
        private val handlerThread = HandlerThread("Rgp08ProbeClient").apply { start() }
        private val handler =
            Handler(handlerThread.looper) { message ->
                replies.offer(Message.obtain(message))
                true
            }
        private val activeConnection = AtomicReference<BoundConnection?>()
        private val connectionGeneration = AtomicInteger(0)
        private val freshConnections = LinkedBlockingQueue<Int>()
        private val connected = CountDownLatch(1)
        private val replies = ArrayBlockingQueue<Message>(8)
        private val bindingFailure = AtomicReference<String?>()
        private var cachedInfo: Info? = null
        var observedPid: Int = -1
            private set
        var observedToken: String? = null
            private set
        private val callbackExecutor =
            Executor { command ->
                handler.post(command)
            }

        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    val generation = connectionGeneration.incrementAndGet()
                    activeConnection.set(BoundConnection(Messenger(requireNotNull(service)), generation))
                    cachedInfo = null
                    connected.countDown()
                    freshConnections.offer(generation)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    activeConnection.set(null)
                }

                override fun onNullBinding(name: ComponentName?) {
                    activeConnection.set(null)
                    bindingFailure.set("Null binding for $name")
                    connected.countDown()
                }

                override fun onBindingDied(name: ComponentName?) {
                    activeConnection.set(null)
                }
            }

        init {
            try {
                val bound =
                    context.bindService(
                        Intent(Intent.ACTION_MAIN).setComponent(component).setPackage(PACKAGE),
                        Context.BIND_AUTO_CREATE,
                        callbackExecutor,
                        connection,
                    )
                require(bound) { "bindService returned false" }
                require(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    bindingFailure.get() ?: "Bind timeout after ${BIND_TIMEOUT_SECONDS}s"
                }
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        val info: Info
            get() = cachedInfo ?: fetchInfo().also { cachedInfo = it }

        fun read(
            peer: String,
            nowMs: Long,
            replyTimeoutSeconds: Long = MESSAGE_TIMEOUT_SECONDS,
        ): AutoReconnectGuard.Block? {
            val reply =
                send(
                    ReconnectPrefsServiceShared.COMMAND_READ,
                    Bundle().apply {
                        putString(ReconnectPrefsServiceShared.KEY_PEER, peer)
                        putLong(ReconnectPrefsServiceShared.KEY_NOW_MS, nowMs)
                    },
                    replyTimeoutSeconds = replyTimeoutSeconds,
                )
            val failures = reply.data.getString(ReconnectPrefsServiceShared.KEY_READ_FAILURES)
            val untilMs = reply.data.getLong(ReconnectPrefsServiceShared.KEY_READ_UNTIL_MS)
            val remainingMs = reply.data.getLong(ReconnectPrefsServiceShared.KEY_READ_REMAINING_MS)
            return failures?.let { AutoReconnectGuard.Block(it.toInt(), untilMs, remainingMs) }
        }

        fun activeBlock(nowMs: Long): AutoReconnectGuard.Block? {
            val reply =
                send(
                    ReconnectPrefsServiceShared.COMMAND_ACTIVE_BLOCK,
                    Bundle().apply {
                        putLong(ReconnectPrefsServiceShared.KEY_NOW_MS, nowMs)
                    },
                )
            val failures = reply.data.getString(ReconnectPrefsServiceShared.KEY_READ_FAILURES)
            val untilMs = reply.data.getLong(ReconnectPrefsServiceShared.KEY_READ_UNTIL_MS)
            val remainingMs = reply.data.getLong(ReconnectPrefsServiceShared.KEY_READ_REMAINING_MS)
            return failures?.let { AutoReconnectGuard.Block(it.toInt(), untilMs, remainingMs) }
        }

        private fun fetchInfo(): Info {
            val reply =
                send(
                    ReconnectPrefsServiceShared.COMMAND_INFO,
                    sendTimeoutSeconds = BIND_TIMEOUT_SECONDS,
                    replyTimeoutSeconds = BIND_TIMEOUT_SECONDS,
                )
            return Info(
                pid = reply.data.getInt(ReconnectPrefsServiceShared.KEY_PID),
                uid = reply.data.getInt(ReconnectPrefsServiceShared.KEY_UID),
                token = requireNotNull(reply.data.getString(ReconnectPrefsServiceShared.KEY_INSTANCE_TOKEN)),
                startElapsedMs = reply.data.getLong(ReconnectPrefsServiceShared.KEY_START_ELAPSED_MS),
            )
        }

        private fun send(
            command: Int,
            data: Bundle = Bundle(),
            sendTimeoutSeconds: Long = MESSAGE_TIMEOUT_SECONDS,
            replyTimeoutSeconds: Long = MESSAGE_TIMEOUT_SECONDS,
        ): Message {
            var staleFailure: Exception? = null
            repeat(2) { attempt ->
                val snapshot = activeConnection.get()
                val target = snapshot?.messenger
                if (snapshot == null || target == null || !target.binder.isBinderAlive) {
                    staleFailure = RemoteException("Remote Messenger is not alive")
                    if (snapshot != null) {
                        activeConnection.compareAndSet(snapshot, null)
                    }
                    awaitFreshConnection(snapshot?.generation ?: 0)
                    replies.clear()
                    return@repeat
                }
                val message = Message.obtain()
                message.what = command
                message.data = data
                message.replyTo = Messenger(handler)
                val sendLatch = CountDownLatch(1)
                val sent = AtomicBoolean(false)
                handler.post {
                    try {
                        target.send(message)
                        sent.set(true)
                    } catch (failure: RemoteException) {
                        staleFailure = failure
                    } finally {
                        sendLatch.countDown()
                    }
                }
                require(sendLatch.await(sendTimeoutSeconds, TimeUnit.SECONDS)) {
                    "Messenger send timeout after ${sendTimeoutSeconds}s"
                }
                if (!sent.get()) {
                    staleFailure = RemoteException("Remote Messenger failed to send")
                    replies.clear()
                    awaitFreshConnection(snapshot.generation)
                    return@repeat
                }
                val reply = replies.poll(replyTimeoutSeconds, TimeUnit.SECONDS)
                if (reply != null) {
                    return decodeReply(reply, command)
                }
                val currentConnection = activeConnection.get()
                if (currentConnection != snapshot) {
                    staleFailure = RemoteException("Remote Messenger became stale")
                    replies.clear()
                    awaitFreshConnection(snapshot.generation)
                    return@repeat
                }
                val staleConnection = requireNotNull(currentConnection)
                if (!staleConnection.messenger.binder.isBinderAlive) {
                    staleFailure = RemoteException("Remote Messenger became stale")
                    activeConnection.compareAndSet(staleConnection, null)
                    replies.clear()
                    awaitFreshConnection(snapshot.generation)
                    return@repeat
                }
                throw IllegalArgumentException("Messenger reply timeout after ${replyTimeoutSeconds}s")
            }
            throw IllegalArgumentException("Messenger rebind failed", staleFailure)
        }

        private fun awaitFreshConnection(previousGeneration: Int): BoundConnection {
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RECONNECT_TIMEOUT_SECONDS)
            while (true) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                activeConnection.get()?.let { connection ->
                    if (connection.generation > previousGeneration) return connection
                }
                require(remainingNanos > 0) {
                    "Fresh Messenger connection timeout after ${RECONNECT_TIMEOUT_SECONDS}s"
                }
                val generation = freshConnections.poll(remainingNanos, TimeUnit.NANOSECONDS)
                requireNotNull(generation) {
                    "Fresh Messenger connection timeout after ${RECONNECT_TIMEOUT_SECONDS}s"
                }
            }
        }

        private fun decodeReply(
            reply: Message,
            command: Int,
        ): Message {
            require(reply.what == command) { "Messenger reply mismatch: ${reply.what}" }
            observedPid =
                reply.data.getInt(ReconnectPrefsServiceShared.KEY_PID).also { pid ->
                    require(pid != 0) { "Messenger reply missing PID" }
                }
            observedToken =
                reply.data.getString(ReconnectPrefsServiceShared.KEY_INSTANCE_TOKEN).also { token ->
                    requireNotNull(token) { "Messenger reply missing instance token" }
                }
            return reply
        }

        private data class BoundConnection(
            val messenger: Messenger,
            val generation: Int,
        )

        override fun close() {
            runCatching { context.unbindService(connection) }
            handlerThread.quitSafely()
        }
    }

    private data class Info(
        val pid: Int,
        val uid: Int,
        val token: String,
        val startElapsedMs: Long,
    )

    private companion object {
        const val ARG_MARKER = "marker"
        const val ARG_RUN_ID = "runId"
        const val PACKAGE = "com.mcarplay.app"
        const val KEY_STATES = "auto_reconnect_guard_states"
        const val HANDOFF_KEY = "mcarplay.test.handoff"
        const val SENTINEL = "kept-through-rgp08"
        const val PEER = "aa:bb:cc:dd:ee:08"
        const val OUTPUT_FILE = "rgp08-cross-process-prefs.json"
        const val FIXED_NOW_MS = 2_000L
        const val S0_BLOCKED_UNTIL_MS = 62_000L
        const val S1_BLOCKED_UNTIL_MS = 182_000L
        const val BIND_TIMEOUT_SECONDS = 60L
        const val MESSAGE_TIMEOUT_SECONDS = 5L
        const val PROBE_STARTUP_REPLY_TIMEOUT_SECONDS = 60L
        const val RECONNECT_TIMEOUT_SECONDS = 60L
        val LEGACY_KEYS = ReconnectPrefsInstrumentationTestSupport.LEGACY_KEYS
        val WARM_SERVICE = ComponentName(PACKAGE, "com.mcarplay.app.debug.ReconnectPrefsWarmService")
        val COLD_SERVICE = ComponentName(PACKAGE, "com.mcarplay.app.debug.ReconnectPrefsColdService")
    }
}
