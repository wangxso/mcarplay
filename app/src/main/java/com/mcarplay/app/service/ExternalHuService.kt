package com.mcarplay.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mcarplay.app.MainActivity
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.carlife.ExternalHuController
import com.mcarplay.app.i18n.AppLanguage

/**
 * Keeps the user-started secondary-HU emulation alive while CarWith connects.
 *
 * It owns no CarPlay transport and must never be presented as a CarPlay
 * session. The notification text reflects only the handshake milestones.
 */
class ExternalHuService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            ExternalHuController.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundNow()
        } catch (error: Exception) {
            SessionController.appendLog(
                "HU-SIM: 前台服务启动失败 ${error.message ?: error.javaClass.simpleName}",
            )
            stopSelf()
            return START_NOT_STICKY
        }
        val started = ExternalHuController.start(applicationContext)
        if (!started) {
            SessionController.appendLog("HU-SIM: 启动失败：${ExternalHuController.snapshot().detail}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ExternalHuController.stop()
        super.onDestroy()
    }

    private fun startForegroundNow() {
        val text = AppLanguage.localized(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                text.getString(R.string.carlife_hu_bridge_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = text.getString(R.string.notif_hu_channel_desc)
                setShowBadge(false)
            },
        )
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(text.getString(R.string.notif_hu_title))
                .setContentText(text.getString(R.string.notif_hu_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "carwith_hu_sim"
        private const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.mcarplay.app.action.STOP_EXTERNAL_HU"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ExternalHuService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ExternalHuService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
