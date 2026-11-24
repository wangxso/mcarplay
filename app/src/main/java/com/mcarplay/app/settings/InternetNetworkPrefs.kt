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

    fun get(context: Context): InternetNetwork = get(context, VideoSourcePrefs.get(context))

    internal fun get(
        context: Context,
        source: VideoSource,
    ): InternetNetwork =
        parse(
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NETWORK, null),
            source,
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

    internal fun defaultFor(source: VideoSource): InternetNetwork = if (source == VideoSource.DASHBOARD) InternetNetwork.PHONE else DEFAULT

    internal fun parse(
        value: String?,
        source: VideoSource = VideoSource.CAR_WITH,
    ): InternetNetwork = InternetNetwork.entries.firstOrNull { it.storageValue == value } ?: defaultFor(source)
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
