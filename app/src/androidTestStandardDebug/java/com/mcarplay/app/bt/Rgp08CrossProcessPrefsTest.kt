package com.mcarplay.app.bt

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcarplay.app.debug.ReconnectPrefsServiceShared
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Rgp08CrossProcessPrefsTest {
    private lateinit var instrumentation: Instrumentation
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var warm: ProbeConnection
    private lateinit var cold: ProbeConnection

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        prefs = ReconnectPrefsInstrumentationTestSupport.settings(context)
        warm = ProbeConnection(context, WARM_SERVICE)
        cold = ProbeConnection(context, COLD_SERVICE)
    }

    @After
    fun tearDown() {
        warm.close()
        cold.close()
    }

    @Test
    fun verifiesColdProductionReadAfterCrossProcessCommit() {
        val marker = "rgp08-${UUID.randomUUID()}"
        val writerPid = Process.myPid()
        val events = JSONArray()

        try {
            val s0 =
                JSONObject()
                    .put("address", PEER)
                    .put("failures", 1)
                    .put("firstFailureAtMs", 1_000L)
                    .put("lastFailureAtMs", 1_000L)
                    .put("blockedUntilMs", 0L)
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

            val warmInfo = warm.info
            val warmReadS0 = warm.read(PEER, FIXED_NOW_MS)
            assertTrue(warmInfo.pid != writerPid)
            assertEquals(Process.myUid(), warmInfo.uid)
            assertNull(warmReadS0)
            assertNull(warm.activeBlock(FIXED_NOW_MS))
            events.put(
                JSONObject()
                    .put("name", "WARM_READY")
                    .put("process", "warm")
                    .put("pid", warmInfo.pid)
                    .put("uid", warmInfo.uid)
                    .put("token", warmInfo.token)
                    .put("read", "null"),
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
            assertTrue(
                "Warm observation must be S0 or S1: $warmObservation",
                warmObservation == null ||
                    warmObservation == AutoReconnectGuard.Block(2, S1_BLOCKED_UNTIL_MS, 180_000L),
            )
            events.put(
                JSONObject()
                    .put("name", "WARM_OBSERVATION")
                    .put("pid", warm.observedPid)
                    .put("token", warm.observedToken)
                    .put("read", if (warmObservation == null) "null" else "S1"),
            )

            val coldInfo = cold.info
            val coldRead = cold.read(PEER, FIXED_NOW_MS)
            assertTrue(coldInfo.pid != writerPid && coldInfo.pid != warmInfo.pid)
            assertEquals(Process.myUid(), coldInfo.uid)
            assertTrue(
                "Cold process started before S1 commit returned: ${coldInfo.startElapsedMs} < $s1AfterMs",
                coldInfo.startElapsedMs >= s1AfterMs,
            )
            assertEquals(AutoReconnectGuard.Block(2, S1_BLOCKED_UNTIL_MS, 180_000L), coldRead)
            assertEquals(
                AutoReconnectGuard.Block(2, S1_BLOCKED_UNTIL_MS, 180_000L),
                cold.activeBlock(FIXED_NOW_MS),
            )
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
            events.put(
                JSONObject()
                    .put("name", "SENTINEL_BEFORE_CLEANUP")
                    .put("value", SENTINEL),
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
    ) {
        private val handlerThread = HandlerThread("Rgp08ProbeClient").apply { start() }
        private val handler =
            Handler(handlerThread.looper) { message ->
                replies.offer(Message.obtain(message))
                true
            }
        private val remote = AtomicReference<Messenger?>()
        private val connected = CountDownLatch(1)
        private val replies = ArrayBlockingQueue<Message>(8)
        var observedPid: Int = -1
            private set
        var observedToken: String? = null
            private set
        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    remote.set(Messenger(requireNotNull(service)))
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    remote.set(null)
                }
            }

        init {
            val bound =
                context.bindService(
                    Intent(Intent.ACTION_MAIN).setComponent(component).setPackage(PACKAGE),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            require(bound) { "bindService returned false" }
            require(connected.await(5L, TimeUnit.SECONDS)) { "Bind timeout" }
        }

        val info: Info by lazy { fetchInfo() }

        fun read(
            peer: String,
            nowMs: Long,
        ): AutoReconnectGuard.Block? {
            val reply =
                send(
                    ReconnectPrefsServiceShared.COMMAND_READ,
                    Bundle().apply {
                        putString(ReconnectPrefsServiceShared.KEY_PEER, peer)
                        putLong(ReconnectPrefsServiceShared.KEY_NOW_MS, nowMs)
                    },
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
            val reply = send(ReconnectPrefsServiceShared.COMMAND_INFO)
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
        ): Message {
            val message = Message.obtain()
            message.what = command
            message.data = data
            message.replyTo = Messenger(handler)
            val sendLatch = CountDownLatch(1)
            val sent = AtomicBoolean(false)
            handler.post {
                try {
                    remote.get()?.send(message)
                    sent.set(true)
                } finally {
                    sendLatch.countDown()
                }
            }
            require(sendLatch.await(5L, TimeUnit.SECONDS) && sent.get()) { "Messenger send timeout" }
            val reply = replies.poll(5L, TimeUnit.SECONDS)
            requireNotNull(reply) { "Messenger reply timeout" }
            require(reply.what == command) { "Messenger reply mismatch: ${reply.what}" }
            observedPid = reply.data.getInt(ReconnectPrefsServiceShared.KEY_PID)
            observedToken = reply.data.getString(ReconnectPrefsServiceShared.KEY_INSTANCE_TOKEN)
            return reply
        }

        fun close() {
            context.unbindService(connection)
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
        const val S1_BLOCKED_UNTIL_MS = 182_000L
        const val RUN_ID_ARG = "runId"
        val LEGACY_KEYS = ReconnectPrefsInstrumentationTestSupport.LEGACY_KEYS
        val WARM_SERVICE = ComponentName(PACKAGE, "com.mcarplay.app.debug.ReconnectPrefsWarmService")
        val COLD_SERVICE = ComponentName(PACKAGE, "com.mcarplay.app.debug.ReconnectPrefsColdService")
    }
}
