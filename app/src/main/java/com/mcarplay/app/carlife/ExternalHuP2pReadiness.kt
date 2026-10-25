package com.mcarplay.app.carlife

data class ExternalHuP2pReady(
    val identity: ExternalHuDiscoveryProtocol.Identity,
    val advertisement: ExternalHuSdpProtocol.HuAdvertisement,
)

/** Emits a new announcement only for a complete, changed P2P identity. */
internal class ExternalHuP2pReadiness {
    data class Change(
        val evaluation: ExternalHuDiscoveryProtocol.GroupEvaluation,
        val ready: ExternalHuP2pReady?,
        val lost: Boolean,
    )

    var current: ExternalHuP2pReady? = null
        private set

    fun update(
        groupFormed: Boolean,
        isGroupOwner: Boolean,
        identity: ExternalHuDiscoveryProtocol.Identity?,
        networkName: String,
    ): Change {
        val evaluation =
            ExternalHuDiscoveryProtocol.evaluateGroup(
                groupFormed,
                isGroupOwner,
                identity?.isUsable() == true,
                networkName,
            )
        val next =
            if (evaluation.state == ExternalHuDiscoveryProtocol.GroupState.READY && identity != null) {
                ExternalHuP2pReady(
                    identity,
                    ExternalHuSdpProtocol.HuAdvertisement(
                        network =
                            ExternalHuSdpProtocol.CarNetwork(
                                name = identity.deviceName,
                                mac = identity.macAddress,
                                ssid = networkName,
                                password = "",
                                mode = ExternalHuSdpProtocol.WIFI_MODE_P2P,
                            ),
                    ),
                )
            } else {
                null
            }
        val previous = current
        current = next
        return Change(evaluation, next.takeIf { it != previous }, previous != null && next == null)
    }

    fun clear(): Boolean {
        val hadReady = current != null
        current = null
        return hadReady
    }
}
