package com.mcarplay.app.settings

import android.content.Context

/** Which network may carry Internet traffic while the car hotspot is in use. */
enum class InternetNetwork(
    val storageValue: String,
) {
    PHONE("phone"),
    CAR("car"),
}

/**
 * Internet routing preference for the next connection.
 *
 * `PHONE` (default) requests app-private Wi-Fi. Android independently chooses
 * the default Internet network for other apps. `CAR` keeps the system-level Wi-Fi join, so Android may use the car
 * hotspot as its default network. It cannot change routing of an
 * already-established session, hence "next connection".
 */
object InternetNetworkPrefs {
    val DEFAULT = InternetNetwork.PHONE

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
}
