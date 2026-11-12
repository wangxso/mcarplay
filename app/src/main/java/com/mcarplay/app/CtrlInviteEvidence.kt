package com.mcarplay.app

import com.mcarplay.app.compat.ReceiverObservation

/** 同一次入站邀请的时间、目标、本机地址与实际 Wi-Fi 网络，不能分别读取混成另一轮。 */
internal data class CtrlInviteEvidence<N : Any>(
    val receivedAtMillis: Long,
    val target: String?,
    val localAddress: String?,
    val network: N?,
    val observation: ReceiverObservation? = null,
) {
    fun verifiedNetwork(
        nowMillis: Long,
        freshnessMillis: Long,
        currentTarget: String?,
        currentNetwork: N?,
        currentObservation: ReceiverObservation? = null,
        requireObservation: Boolean = false,
        networkForAddress: (String) -> N?,
    ): N? {
        if (receivedAtMillis <= 0L || nowMillis - receivedAtMillis !in 0..freshnessMillis) return null
        val targetBinding = target ?: return null
        if (targetBinding != currentTarget) return null
        if (requireObservation && (observation == null || observation != currentObservation)) return null
        if (observation != null && observation != currentObservation) return null
        val address = localAddress ?: return null
        val invited = network ?: return null
        if (currentNetwork != invited || networkForAddress(address) != invited) return null
        return invited
    }
}
