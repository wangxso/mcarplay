package com.mcarplay.app.ui.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

internal data class DashboardSimSignal(
    val slot: Int,
    val level: Int?,
    val generation: String?,
)

/** Each SIM is observed independently, including when the phone uses Wi-Fi for internet access. */
internal class DashboardSimSignals(
    private val context: Context,
    private val onChanged: (List<DashboardSimSignal>) -> Unit,
) {
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val phones = context.getSystemService(TelephonyManager::class.java)
    private val watched = mutableMapOf<Int, SimCallback>()
    private var started = false
    private val listener =
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() = refreshSubscriptions()
        }

    fun start() {
        if (started ||
            subscriptions == null ||
            phones == null ||
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        started = runCatching { subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, listener) }.isSuccess
        if (started) refreshSubscriptions()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { subscriptions?.removeOnSubscriptionsChangedListener(listener) }
        watched.values.forEach { it.unregister() }
        watched.clear()
        onChanged(emptyList())
    }

    @SuppressLint("MissingPermission")
    private fun refreshSubscriptions() {
        if (!started) return
        val manager = phones ?: return
        val active = runCatching { subscriptions?.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val ids = active.map { it.subscriptionId }.toSet()
        (watched.keys - ids).forEach { watched.remove(it)?.unregister() }
        for (subscription in active) {
            val id = subscription.subscriptionId
            val existing = watched[id]
            if (existing?.slot == subscription.simSlotIndex) continue
            existing?.unregister()
            val phone = manager.createForSubscriptionId(id)
            val callback = SimCallback(subscription.simSlotIndex, phone)
            watched[id] = callback
            runCatching { phone.registerTelephonyCallback(context.mainExecutor, callback) }
        }
        publish()
    }

    private fun publish() {
        if (started) onChanged(watched.values.sortedBy { it.slot }.map { it.snapshot() })
    }

    private inner class SimCallback(
        val slot: Int,
        private val phone: TelephonyManager,
    ) : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DisplayInfoListener {
        private var level = runCatching { phone.signalStrength?.level }.getOrNull()
        private var generation =
            try {
                networkGeneration(phone.dataNetworkType) ?: networkGeneration(phone.voiceNetworkType)
            } catch (_: SecurityException) {
                null
            } catch (_: UnsupportedOperationException) {
                null
            }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            level = signalStrength.level
            publish()
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            generation = networkGeneration(telephonyDisplayInfo.networkType, telephonyDisplayInfo.overrideNetworkType)
            publish()
        }

        fun snapshot() = DashboardSimSignal(slot, level?.takeIf { it in 0..4 }, generation)

        fun unregister() {
            runCatching { phone.unregisterTelephonyCallback(this) }
        }
    }
}

@Suppress("DEPRECATION")
internal fun networkGeneration(
    networkType: Int,
    overrideType: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE,
): String? {
    if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
    ) {
        return "5G"
    }
    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN,
        -> "2G"
        else -> null
    }
}
