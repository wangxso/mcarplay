package com.mcarplay.app.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.mcarplay.app.shizuku.Shell

/**
 * 本机身份解析：蓝牙 MAC 与设备名（无线 CarPlay 协议必需）。
 *
 * 背景（Audi MIB3 真车实测，2026-10-09）：Android 11+ 应用层调
 * `BluetoothAdapter.getAddress()` 只会拿到占位 `02:00:00:00:00:00`；
 * 而车机在解析 0x4300 的无线组（bluetoothTransportIdentifier）与后续
 * 0x4E0E 时会校验其是否与实际 RFCOMM 对端一致——占位值导致车机静默丢弃
 * （现象：链路层照常 ACK，CSM 层不再回应，永远等不到 0x4301）。
 *
 * 解析顺序：
 *  1. Shizuku shell：`content query --uri content://settings/secure/bluetooth_address`
 *     （shell 身份可读系统安全设置，免 root）
 *  2. `Settings.Secure` 直读（普通应用多数机型读不到，兜底）
 * 成功结果永久缓存；失败 10 秒内不重试（避免每次连接阻塞 4 秒）。
 */
object DeviceIdentity {
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    @Volatile
    private var cachedMac: String? = null

    @Volatile
    private var lastAttemptAtMillis = 0L

    /**
     * 真实蓝牙 MAC（大写冒号格式）；解析失败返回占位。
     *
     * @param forceRetry 忽略静默窗口立即重试。ctrl 广播提前到「启动连接」后，
     *   首次注册可能赶上 Shizuku 尚未就绪、只能拿到占位值；而车机按 TXT id
     *   （=真实蓝牙 MAC）匹配是否邀请——占位值等于永远不被邀请。广播侧检测到
     *   占位时必须能立刻重试，而不是等满 10 秒静默窗口。
     */
    fun bluetoothMac(
        context: Context,
        forceRetry: Boolean = false,
    ): String {
        cachedMac?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!forceRetry && now - lastAttemptAtMillis < RETRY_QUIET_MILLIS) return PLACEHOLDER_MAC
        synchronized(this) {
            cachedMac?.let { return it }
            lastAttemptAtMillis = now
            val resolved = resolveViaShizuku() ?: resolveViaSettings(context) ?: PLACEHOLDER_MAC
            if (resolved != PLACEHOLDER_MAC) cachedMac = resolved
            return resolved
        }
    }

    /**
     * 已解析并缓存的本机蓝牙 MAC；尚未解析成功返回 null（不触发 shell 查询）。
     *
     * 供 AirPlay 会话级 SETUP 这类「不能阻塞在 shell 上」的位置使用——
     * 无线 CarPlay 的 BT 阶段必然先调用过 [bluetoothMac]，走到这里时缓存已就绪。
     */
    fun cachedBluetoothMac(): String? = cachedMac

    /** 设备名（0x4E09 DeviceInformationUpdate 用；对齐真机发的是本机蓝牙名）。 */
    fun deviceName(context: Context): String =
        try {
            context
                .getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: "iPhone"
        } catch (_: Exception) {
            "iPhone"
        }

    private fun resolveViaShizuku(): String? =
        try {
            val result =
                Shell.exec(
                    "content query --uri content://settings/secure/bluetooth_address",
                    timeoutMs = 4_000,
                )
            MAC_REGEX.find(result.output)?.value?.uppercase()
        } catch (_: Exception) {
            null
        }

    private fun resolveViaSettings(context: Context): String? =
        try {
            Settings.Secure
                .getString(context.contentResolver, "bluetooth_address")
                ?.takeIf { MAC_REGEX.matches(it) }
                ?.uppercase()
        } catch (_: Exception) {
            null
        }

    private const val RETRY_QUIET_MILLIS = 10_000L
}
