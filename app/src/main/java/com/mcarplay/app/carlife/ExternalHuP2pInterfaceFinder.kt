package com.mcarplay.app.carlife

import java.net.Inet4Address
import java.net.NetworkInterface

data class P2pInterfaceReport(
    val interfaceName: String?,
    val addresses: List<String>,
)

object ExternalHuP2pInterfaceFinder {
    fun find(ipv4: Inet4Address?): P2pInterfaceReport {
        val interfaces =
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
                .getOrDefault(emptyList())
        val target = ipv4?.hostAddress
        val match =
            interfaces.firstOrNull { networkInterface ->
                target != null &&
                    networkInterface.inetAddresses.toList().any { it.hostAddress == target }
            } ?: interfaces.firstOrNull { target == null && it.name.startsWith("p2p", ignoreCase = true) }
        return P2pInterfaceReport(
            interfaceName = match?.name,
            addresses =
                match
                    ?.inetAddresses
                    ?.toList()
                    ?.mapNotNull { it.hostAddress }
                    .orEmpty(),
        )
    }
}
