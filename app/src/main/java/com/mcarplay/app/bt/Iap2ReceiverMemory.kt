package com.mcarplay.app.bt

import android.content.Context

/**
 * 「曾经接受过 iAP2 RFCOMM 的设备」记忆（2026-10-31）。
 *
 * 自动模式（未指定目标）出站前有 SDP 闸门：SDP 里没有 …caff 的设备被当作耳机/手表跳过。
 * 但大众车机实测（2026-10-31 诊断报告）SDP **从不声明** …caff，RFCOMM 却能连上并跑完
 * iAP2 认证——目标一丢（权限/数据被重置），自动模式就把它永远跳过。
 * 这里记下 RFCOMM 成功过的地址，下次自动模式不再让 SDP 结论否决它。
 *
 * 只存地址（应用私有存储，不进诊断报告正文），最多 [MAX_ENTRIES] 台。
 */
object Iap2ReceiverMemory {
    private const val PREFS = "iap2_receivers"
    private const val KEY_ADDRESSES = "addresses"
    private const val MAX_ENTRIES = 16

    fun contains(
        context: Context,
        address: String?,
    ): Boolean {
        if (address.isNullOrBlank()) return false
        return read(context).any { it.equals(address, ignoreCase = true) }
    }

    fun remember(
        context: Context,
        address: String?,
    ) {
        if (address.isNullOrBlank()) return
        val current = read(context)
        if (current.any { it.equals(address, ignoreCase = true) }) return
        val next = (current + address.uppercase(java.util.Locale.ROOT)).takeLast(MAX_ENTRIES)
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESSES, next.joinToString(","))
            .apply()
    }

    private fun read(context: Context): List<String> =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESSES, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}
