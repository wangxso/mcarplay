package com.mcarplay.app.service

/** Wi-Fi 强制模式是设备全局状态，只允许一个应用变体拥有它。 */
internal object WifiLowLatencyPolicy {
    fun supportsForce(flavor: String): Boolean = flavor == "standard"

    /** AOSP 即使无法切换模式也可能返回 0，必须同时检查命令输出。 */
    fun commandSucceeded(
        exitCode: Int,
        output: String,
    ): Boolean = exitCode == 0 && !output.contains("Command execution failed", ignoreCase = true)
}
