package com.mcarplay.app.debug

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.mcarplay.app.bt.AutoReconnectGuard
import java.util.UUID

public abstract class ReconnectPrefsServiceShared : Service() {
    public final override fun onBind(intent: Intent?): IBinder {
        require(intent != null) { "Missing bind intent" }
        require(intent.component != null) { "Missing explicit bind component" }
        require(intent.`package` == packageName) { "Unexpected bind package: ${intent.`package`}" }
        return messenger.binder
    }

    public final override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = throw UnsupportedOperationException("RGP08 probe services must be bound, not started")

    protected abstract val serviceProcessName: String

    private val handlerThread by lazy {
        HandlerThread("ReconnectPrefsProbe").apply { start() }
    }

    private val instanceToken: String by lazy { UUID.randomUUID().toString() }
    private val processPid: Int by lazy { Process.myPid() }
    private val processUid: Int by lazy { Process.myUid() }
    private val startElapsedMs: Long by lazy { Process.getStartElapsedRealtime() }
    private val messenger by lazy {
        Messenger(Handler(handlerThread.looper) { message -> handleMessage(message) })
    }

    private fun handleMessage(message: Message): Boolean {
        val replyTo = requireNotNull(message.replyTo) { "Missing Messenger replyTo" }
        val reply = Message.obtain()
        reply.what = message.what
        when (message.what) {
            COMMAND_INFO ->
                reply.data =
                    Bundle().apply {
                        putString(KEY_PROCESS_NAME, serviceProcessName)
                        putInt(KEY_PID, processPid)
                        putInt(KEY_UID, processUid)
                        putString(KEY_INSTANCE_TOKEN, instanceToken)
                        putLong(KEY_START_ELAPSED_MS, startElapsedMs)
                    }

            COMMAND_READ ->
                reply.data =
                    Bundle().apply {
                        val peer = message.data.getString(KEY_PEER)
                        val nowMs = message.data.getLong(KEY_NOW_MS)
                        val block = AutoReconnectGuard.block(applicationContext, peer, nowMs)
                        putString(KEY_READ_FAILURES, block?.failures?.toString())
                        putLong(KEY_READ_UNTIL_MS, block?.untilMs ?: -1L)
                        putLong(KEY_READ_REMAINING_MS, block?.remainingMs ?: -1L)
                    }

            COMMAND_ACTIVE_BLOCK ->
                reply.data =
                    Bundle().apply {
                        val nowMs = message.data.getLong(KEY_NOW_MS)
                        val block = AutoReconnectGuard.activeBlock(applicationContext, nowMs)
                        putString(KEY_READ_FAILURES, block?.failures?.toString())
                        putLong(KEY_READ_UNTIL_MS, block?.untilMs ?: -1L)
                        putLong(KEY_READ_REMAINING_MS, block?.remainingMs ?: -1L)
                    }

            else -> throw IllegalStateException("Unknown RGP08 command: ${message.what}")
        }
        replyTo.send(reply)
        return true
    }

    internal companion object {
        const val COMMAND_INFO = 1
        const val COMMAND_READ = 2
        const val COMMAND_ACTIVE_BLOCK = 3
        const val KEY_PEER = "peer"
        const val KEY_NOW_MS = "nowMs"
        const val KEY_PROCESS_NAME = "processName"
        const val KEY_PID = "pid"
        const val KEY_UID = "uid"
        const val KEY_INSTANCE_TOKEN = "instanceToken"
        const val KEY_START_ELAPSED_MS = "startElapsedMs"
        const val KEY_READ_FAILURES = "readFailures"
        const val KEY_READ_UNTIL_MS = "readUntilMs"
        const val KEY_READ_REMAINING_MS = "readRemainingMs"
    }
}
