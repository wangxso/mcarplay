package com.mcarplay.app.net

/**
 * 解析 `cmd wifi list-networks` 的输出（纯函数，单测覆盖）。
 *
 * 真实格式（空格对齐、**无制表符**，SSID 可含空格；同一网络按安全类型可能多行）：
 * ```
 * Network Id      SSID                         Security type
 * 0            2-502                            wpa2-psk
 * 1            李志华的iPhone                    wpa3-sae
 * ```
 * 解析规则：首段 = netId，末段 = 安全类型，中间全部拼回 SSID。
 *
 * 已知边界：SSID 内含连续多个空格时会被压成一个——车机热点名极少如此；
 * 此时宁可漏判（不提示用户）也不误删其它网络。
 */
object WifiSavedNetworks {
    /**
     * 返回 SSID 精确匹配的 netId 列表（去重；未匹配返回空）。
     * 调用方只在列表非空时才可能执行 `forget-network`。
     */
    fun idsOf(
        listNetworksOutput: String,
        ssid: String,
    ): List<Int> =
        listNetworksOutput
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                // 至少 netId + SSID + security 三段
                if (parts.size < 3) return@mapNotNull null
                val id = parts.first().toIntOrNull() ?: return@mapNotNull null
                val name = parts.subList(1, parts.size - 1).joinToString(" ")
                if (name == ssid) id else null
            }.distinct()
            .toList()
}
