package com.mcarplay.app.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import androidx.annotation.StringRes
import com.mcarplay.app.R
import com.mcarplay.app.net.WifiIdentity

/** Observes Android's default route, not mcarplay's process binding for projection. */
internal data class ProjectionInternetStatus(
    val preference: InternetNetwork,
    val transport: Transport,
    val validated: Boolean,
    val carIsDefault: Boolean?,
) {
    enum class Transport { NONE, CELLULAR, WIFI, ETHERNET, VPN, OTHER }

    val preferenceMatches: Boolean?
        get() = carIsDefault?.let { it == (preference == InternetNetwork.CAR) }

    @get:StringRes
    val hint: Int?
        get() =
            when (preference) {
                InternetNetwork.PHONE ->
                    when {
                        carIsDefault == true && !validated -> R.string.main_internet_car_default_offline
                        carIsDefault == true -> R.string.main_internet_car_default
                        validated -> null
                        transport == Transport.CELLULAR -> R.string.main_internet_cellular_offline
                        else -> R.string.main_internet_default_offline
                    }
                InternetNetwork.CAR ->
                    when {
                        carIsDefault == true && validated -> null
                        carIsDefault == true -> R.string.main_internet_car_default_offline
                        transport == Transport.CELLULAR && validated -> R.string.main_internet_car_using_cellular
                        transport == Transport.CELLULAR -> R.string.main_internet_cellular_offline
                        carIsDefault == false && validated -> R.string.main_internet_car_other_route
                        !validated -> R.string.main_internet_default_offline
                        else -> null // Wi-Fi identity may be redacted; do not guess.
                    }
            }

    fun logLine(): String =
        "NETWORK: default-route transport=$transport validated=$validated preference=$preference matches=${preferenceMatches ?: "unknown"}"

    companion object {
        fun read(
            context: Context,
            preference: InternetNetwork,
            projection: Network?,
            carSsid: String?,
        ): ProjectionInternetStatus {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val active = manager?.activeNetwork
            val caps = active?.let { manager?.getNetworkCapabilities(it) }
            val transport =
                when {
                    active == null -> Transport.NONE
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> Transport.VPN
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Transport.WIFI
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Transport.CELLULAR
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Transport.ETHERNET
                    else -> Transport.OTHER
                }
            val defaultSsid = WifiIdentity.normalizeSsid((caps?.transportInfo as? WifiInfo)?.ssid)
            val carIsDefault =
                when {
                    active != null && active == projection -> true
                    transport == Transport.WIFI && carSsid != null && defaultSsid != null -> WifiIdentity.matches(carSsid, defaultSsid)
                    transport == Transport.CELLULAR || transport == Transport.ETHERNET -> false
                    else -> null
                }
            return ProjectionInternetStatus(
                preference,
                transport,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                carIsDefault,
            )
        }
    }
}
