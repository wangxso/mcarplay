package com.mcarplay.app.ui.car

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.mcarplay.app.R
import kotlin.math.roundToInt

internal data class DashboardStatus(
    val network: Network,
    val signal: Int?,
    val battery: Int?,
    val charging: Boolean,
) {
    enum class Network(
        val label: Int,
    ) {
        WIFI(R.string.car_network_wifi),
        CELLULAR(R.string.car_network_cell),
        ONLINE(R.string.car_network_online),
        OFFLINE(R.string.car_network_offline),
    }

    companion object {
        private val batteryChanged = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

        fun read(context: Context): DashboardStatus {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            // The default internet network may be cellular while the CarPlay sockets use a local Wi-Fi link.
            val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            val network =
                when {
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true -> Network.OFFLINE
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Network.WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Network.CELLULAR
                    else -> Network.ONLINE
                }
            val signal =
                when (network) {
                    Network.WIFI -> {
                        val info = capabilities?.transportInfo as? WifiInfo
                        val wifi = context.getSystemService(WifiManager::class.java)
                        val rssi = info?.rssi?.takeIf { it in -126..0 }
                        if (wifi != null && rssi != null && wifi.maxSignalLevel > 0) {
                            (wifi.calculateSignalLevel(rssi) * 4f / wifi.maxSignalLevel).roundToInt().coerceIn(0, 4)
                        } else {
                            null
                        }
                    }

                    Network.CELLULAR ->
                        runCatching {
                            val subscription = SubscriptionManager.getActiveDataSubscriptionId()
                            if (!SubscriptionManager.isValidSubscriptionId(subscription)) return@runCatching null
                            context
                                .getSystemService(TelephonyManager::class.java)
                                ?.createForSubscriptionId(subscription)
                                ?.signalStrength
                                ?.level
                                ?.coerceIn(0, 4)
                        }.getOrNull()

                    else -> null
                }
            val battery = context.getSystemService(BatteryManager::class.java)
            val percentage = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            val snapshot = context.registerReceiver(null, batteryChanged)
            val status = snapshot?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = (snapshot?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
            val charging =
                when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> true
                    BatteryManager.BATTERY_STATUS_FULL -> plugged
                    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                    else -> battery?.isCharging == true
                }
            return DashboardStatus(network, signal, percentage, charging)
        }
    }
}

/** Vector status icons whose fill reflects the measurements reported by Android. */
private class DashboardStatusIcon(
    private val drawIcon: (Canvas, Paint) -> Unit,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val size = minOf(bounds.width(), bounds.height()) / 26f
        canvas.translate((bounds.width() - 26 * size) / 2, (bounds.height() - 26 * size) / 2)
        canvas.scale(size, size)
        drawIcon(canvas, paint)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Android no longer uses drawable opacity")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun networkIcon(
    network: DashboardStatus.Network,
    level: Int?,
    accent: Int,
    track: Int,
): Drawable =
    DashboardStatusIcon { canvas, paint ->
        paint.style = Paint.Style.FILL
        when (network) {
            DashboardStatus.Network.CELLULAR ->
                repeat(4) { index ->
                    paint.color = if (level != null && index < level) accent else track
                    val start = 3f + index * 5
                    canvas.drawRoundRect(start, 17f - index * 4, start + 3.5f, 22f, 1f, 1f, paint)
                }

            DashboardStatus.Network.WIFI -> {
                paint.color = if (level == 0) track else accent
                canvas.drawCircle(13f, 20f, 1.6f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.3f
                paint.strokeCap = Paint.Cap.ROUND
                for (band in 1..3) {
                    val radius = 2f + band * 4f
                    paint.color = if (level != null && band < level) accent else track
                    canvas.drawArc(13f - radius, 20f - radius, 13f + radius, 20f + radius, -137f, 94f, false, paint)
                }
            }

            DashboardStatus.Network.ONLINE, DashboardStatus.Network.OFFLINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.8f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = if (network == DashboardStatus.Network.ONLINE) accent else track
                canvas.drawCircle(13f, 13f, 8.5f, paint)
                if (network == DashboardStatus.Network.ONLINE) {
                    canvas.drawOval(9f, 4.5f, 17f, 21.5f, paint)
                    canvas.drawLine(4.5f, 13f, 21.5f, 13f, paint)
                } else {
                    canvas.drawLine(7f, 19f, 19f, 7f, paint)
                }
            }
        }
    }

internal fun batteryIcon(
    status: DashboardStatus,
    fill: Int,
    outline: Int,
    boltColor: Int,
): Drawable {
    val bolt =
        Path().apply {
            moveTo(13.6f, 6.5f)
            lineTo(9f, 14f)
            lineTo(12.1f, 14f)
            lineTo(11.5f, 19.5f)
            lineTo(16.5f, 12f)
            lineTo(13.2f, 12f)
            close()
        }
    return DashboardStatusIcon { canvas, paint ->
        paint.color = outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        canvas.drawRoundRect(2.7f, 7.5f, 22.5f, 18.5f, 2.6f, 2.6f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(23.4f, 10.4f, 25.5f, 15.6f, 1f, 1f, paint)
        val level = status.battery ?: 0
        if (level > 0) {
            paint.color = fill
            canvas.drawRoundRect(4.5f, 9.3f, 4.5f + 16.2f * level / 100, 16.7f, 1.3f, 1.3f, paint)
        }
        if (status.charging) {
            paint.color = outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(bolt, paint)
            paint.color = boltColor
            paint.style = Paint.Style.FILL
            canvas.drawPath(bolt, paint)
        }
    }
}
