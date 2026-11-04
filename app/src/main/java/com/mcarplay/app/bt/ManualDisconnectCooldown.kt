package com.mcarplay.app.bt

internal data class ManualDisconnectCooldown(
    val address: String?,
    val stoppedAtMs: Long,
) {
    fun blocks(
        targetAddress: String?,
        nowMs: Long,
    ): Boolean {
        if (address != null && targetAddress != null && !address.equals(targetAddress, ignoreCase = true)) return false
        return nowMs - stoppedAtMs in -DURATION_MS until DURATION_MS
    }

    companion object {
        const val DURATION_MS = 30_000L
    }
}
