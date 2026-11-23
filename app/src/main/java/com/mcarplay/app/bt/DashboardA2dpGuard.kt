package com.mcarplay.app.bt

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Scope temporary A2DP disconnections to the dashboard audio stream and its paired peer. */
internal class DashboardA2dpGuard(
    context: Context,
    private val peerAddress: String,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val worker =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "dashboard-a2dp").apply { isDaemon = true }
        }
    private var pending: Future<*>? = null
    private var nextCheckAt = 0L
    private var registered = false

    @Volatile
    private var closed = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
                    intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED
                ) {
                    return
                }
                val device =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    }
                if (peerAddress.equals(device?.address, ignoreCase = true)) scheduleCheck()
            }
        }

    /** Startup runs off the UI thread. Give the phone route time to settle before setting its media volume. */
    fun start() {
        val initial =
            synchronized(this) {
                if (closed || registered) return
                try {
                    ContextCompat.registerReceiver(
                        app,
                        receiver,
                        IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    registered = true
                } catch (_: RuntimeException) {
                    log("DASHBOARD A2DP: 无法监听蓝牙媒体连接")
                }
                scheduleCheck()
            }
        try {
            initial?.get(2_500L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: java.util.concurrent.TimeoutException) {
            log("DASHBOARD A2DP: 等待蓝牙媒体断开超时，继续启动音频")
        } catch (_: java.util.concurrent.CancellationException) {
            // 会话在音频启动期间结束。
        } catch (error: java.util.concurrent.ExecutionException) {
            log("DASHBOARD A2DP: 检查失败（${error.cause?.javaClass?.simpleName}）")
        }
    }

    @Synchronized
    private fun scheduleCheck(): Future<*>? {
        if (closed) return null
        pending?.let { return it }
        val delay = (nextCheckAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return worker
            .schedule({
                synchronized(this) {
                    pending = null
                    if (closed) return@schedule
                    nextCheckAt = SystemClock.elapsedRealtime() + 3_000L
                }
                when (BtProfiles.disconnectMedia(app, peerAddress) { !closed }) {
                    BtProfiles.MediaDisconnect.DISCONNECTED -> log("DASHBOARD A2DP: 已临时断开车机蓝牙媒体")
                    BtProfiles.MediaDisconnect.FAILED -> log("DASHBOARD A2DP: 未能断开车机蓝牙媒体")
                    BtProfiles.MediaDisconnect.ALREADY_DISCONNECTED, BtProfiles.MediaDisconnect.CANCELLED -> Unit
                }
            }, delay, TimeUnit.MILLISECONDS)
            .also { pending = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pending?.cancel(false)
        pending = null
        worker.shutdownNow()
        if (registered) runCatching { app.unregisterReceiver(receiver) }
    }
}
