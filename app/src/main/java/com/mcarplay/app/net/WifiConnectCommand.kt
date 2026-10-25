package com.mcarplay.app.net

/**
 * 构造 Shizuku 静默入网用的 `cmd wifi connect-network` 命令（纯函数，单测覆盖）。
 *
 * 语法（AOSP `WifiShellCommand#connect-network`）：
 * ```
 * cmd wifi connect-network <ssid> open|owe|wpa2|wpa3 [<passphrase>] [-m] [-d] [-b <bssid>]
 * ```
 * 要点：
 *  - SSID/密码一律走 [quote]（单引号 + 内嵌单引号转义），名字带空格/中文/引号都不会被
 *    shell 拆开，也不存在命令注入；
 *  - 安全类型按 0x5703 的 `Iap2WirelessSecurity` 映射（0=NONE 1=WEP 2=WPA/WPA2
 *    3=WPA3 过渡 4=WPA3）。WEP 没有对应关键字，按 `wpa2` 试一次（与旧兜底路径一致）；
 *    WPA3 过渡模式同时接受 WPA2，故与 specifier 路径一样用 `wpa2`；
 *  - **命令的 exit=0 不代表已连上**：它只把配置投给 WifiService 就返回，调用方必须
 *    轮询 `cmd wifi status` 等 SSID（见 `SessionController.waitForCarSsid`）。
 */
object WifiConnectCommand {
    /** POSIX shell 单引号字面量（`$`/空格/中文/双引号都原样保留）。 */
    fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /**
     * 完整命令行。`passphrase` 为空（或 [securityType] 为 0=NONE）时按开放热点处理。
     */
    fun build(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): String {
        val open = passphrase.isEmpty() || securityType == 0
        val security =
            when {
                open -> "open"
                securityType == 4 -> "wpa3"
                else -> "wpa2"
            }
        val credentials = if (open) "" else " " + quote(passphrase)
        return "cmd wifi connect-network " + quote(ssid) + " " + security + credentials
    }
}
