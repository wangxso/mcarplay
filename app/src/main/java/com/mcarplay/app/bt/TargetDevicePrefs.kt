package com.mcarplay.app.bt

import android.content.Context

/**
 * 「目标车机」选择偏好（2026-10-16）：
 *
 * 用户可能配对了多台 CarPlay 车机（真车 + 测试台），未指定目标时两条链路都按
 * 「任一已配对设备」处理——出站客户端逐个尝试、入站服务端来者不拒，连到哪台
 * 取决于谁先应答（不确定）。选定目标后**只对该设备**发起连接、也只接受该设备
 * 的连入，避免误连非目标车机（典型：家里两台车的其中一台）。
 *
 * 存地址 + 名称快照：地址是判定依据（大小写不敏感比对），名称仅用于 UI 展示
 * （设备可能改名，展示以快照为准，不用时重新选择即可）。
 */
object TargetDevicePrefs {
    private const val PREFS = "target_device"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    data class Target(
        val address: String,
        val name: String,
    )

    /** null = 未指定（自动：出站尝试全部候选、入站接受任一设备）。 */
    fun get(context: Context): Target? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Target(address = address, name = prefs.getString(KEY_NAME, null) ?: address)
    }

    fun set(
        context: Context,
        target: Target?,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (target == null) {
            prefs.edit().clear().apply()
        } else {
            prefs
                .edit()
                .putString(KEY_ADDRESS, target.address)
                .putString(KEY_NAME, target.name)
                .apply()
        }
    }

    fun matches(
        target: Target,
        address: String?,
    ): Boolean = address != null && address.equals(target.address, ignoreCase = true)
}
