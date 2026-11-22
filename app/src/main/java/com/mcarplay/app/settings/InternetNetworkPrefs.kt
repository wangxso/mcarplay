package com.mcarplay.app.settings

import android.content.Context

/** Which network may carry Internet traffic while the car hotspot is in use. */
enum class InternetNetwork(
    val storageValue: String,
) {
    PHONE("phone"),
    CAR("car"),
}

enum class PhoneModeSystemWifiDecision {
    PROCEED,
    CONFLICT,
    CONFIRM,
}

/**
 * Internet routing preference for the next connection.
 *
 * `CAR` keeps the existing system-level Wi-Fi join, so Android may use the car
 * hotspot as its default network. `PHONE` forces the app-private Wi-Fi path:
 * the hotspot remains local to mcarplay and other apps keep using mobile data
 * (or any other system default network). It cannot change routing of an
 * already-established session, hence "next connection".
 */
object InternetNetworkPrefs {
    val DEFAULT = InternetNetwork.CAR

    private const val PREFS_NAME = "settings"
    private const val KEY_NETWORK = "internet_network"

    fun get(context: Context): InternetNetwork =
        parse(
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NETWORK, null),
        )

    fun set(
        context: Context,
        network: InternetNetwork,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NETWORK, network.storageValue)
            .apply()
    }

    internal fun parse(value: String?): InternetNetwork = InternetNetwork.entries.firstOrNull { it.storageValue == value } ?: DEFAULT
}

/** Pure join-path policy, kept separate from Android APIs so it is unit-testable. */
object InternetNetworkPolicy {
    /** The silent system-level join is available only in car mode with Shizuku. */
    fun useSystemWifi(
        network: InternetNetwork,
        shizukuReady: Boolean,
    ): Boolean = network == InternetNetwork.CAR && shizukuReady

    /** Phone mode must not bind or adopt an existing system Wi-Fi network. */
    fun allowExistingSystemWifi(network: InternetNetwork): Boolean = network == InternetNetwork.CAR

    /** Phone mode must not use connect-network fallbacks that save the hotspot system-wide. */
    fun allowSystemFallback(network: InternetNetwork): Boolean = network == InternetNetwork.CAR

    /**
     * Phone mode cannot preserve mobile-data routing if Android already owns the same
     * hotspot globally. If a system Wi-Fi is active but its SSID cannot be read, the
     * caller must ask the user instead of assuming the route is safe.
     */
    fun phoneModeSystemWifiDecision(
        network: InternetNetwork,
        activeTarget: Boolean,
        activeIdentityUnknown: Boolean,
        saved: Boolean,
    ): PhoneModeSystemWifiDecision =
        when {
            network != InternetNetwork.PHONE -> PhoneModeSystemWifiDecision.PROCEED
            activeTarget || saved -> PhoneModeSystemWifiDecision.CONFLICT
            activeIdentityUnknown -> PhoneModeSystemWifiDecision.CONFIRM
            else -> PhoneModeSystemWifiDecision.PROCEED
        }
}
