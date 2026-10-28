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
 * 解析顺序（Shizuku 与手动填写并存）：
 *  1. 实时读取：Shizuku shell `content query --uri content://settings/secure/bluetooth_address`
 *     （shell 身份可读系统安全设置，免 root），再试 `Settings.Secure` 直读。成功即写入本地，
 *     之后没有 Shizuku 也能复用；与手动填写不一致时以实时读取为准。
 *  2. 用户在「本机蓝牙地址」里手动填写的地址（不装 Shizuku 的途径）。
 *  3. 以前实时读取并保存下来的地址。
 * 实时结果进程内永久缓存；退回 2/3 时仍按 10 秒静默窗口重试实时读取。
 */
object DeviceIdentity {
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    enum class Source { SHIZUKU, SETTINGS, MANUAL, REMEMBERED, NONE }

    data class Resolved(
        val mac: String,
        val source: Source,
    )

    @Volatile
    private var cached: Resolved? = null

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
    ): String = resolve(context, forceRetry).mac

    /** 同 [bluetoothMac]，同时给出来源，供日志与「本机蓝牙地址」行展示。 */
    fun resolve(
        context: Context,
        forceRetry: Boolean = false,
    ): Resolved {
        cached?.takeIf { it.source.isLive() }?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!forceRetry && now - lastAttemptAtMillis < RETRY_QUIET_MILLIS) return stored(context)
        synchronized(this) {
            cached?.takeIf { it.source.isLive() }?.let { return it }
            lastAttemptAtMillis = now
            val live =
                resolveViaShizuku()?.let { Resolved(it, Source.SHIZUKU) }
                    ?: resolveViaSettings(context)?.let { Resolved(it, Source.SETTINGS) }
            if (live != null) runCatching { prefs(context).edit().putString(KEY_REMEMBERED, live.mac).apply() }
            return choose(live, manualMac(context), rememberedMac(context)).also { cached = it.takeIf { r -> r.source != Source.NONE } }
        }
    }

    /**
     * 已解析并缓存的本机蓝牙 MAC（含手动填写/已保存的值）；尚未解析返回 null（不触发 shell 查询）。
     *
     * 供 AirPlay 会话级 SETUP 这类「不能阻塞在 shell 上」的位置使用——
     * 无线 CarPlay 的 BT 阶段必然先调用过 [bluetoothMac]，走到这里时缓存已就绪。
     */
    fun cachedBluetoothMac(): String? = cached?.mac

    /** 最近一次解析结果（不触发读取）；未解析返回 null。 */
    fun cachedResolution(): Resolved? = cached

    fun manualMac(context: Context): String? = runCatching { prefs(context).getString(KEY_MANUAL, null) }.getOrNull()?.let(::normalizeMac)

    private fun rememberedMac(context: Context): String? =
        runCatching { prefs(context).getString(KEY_REMEMBERED, null) }.getOrNull()?.let(::normalizeMac)

    /**
     * 保存手动填写的地址；`null` 清除。返回规范化后的值，格式不合法时返回 null 且不写入。
     * 实时读取成功的缓存不受影响（仍以实时值为准）。
     */
    fun setManualMac(
        context: Context,
        input: String?,
    ): String? {
        val normalized = input?.let(::normalizeMac)
        if (input != null && normalized == null) return null
        prefs(context)
            .edit()
            .apply {
                if (normalized == null) remove(KEY_MANUAL) else putString(KEY_MANUAL, normalized)
            }.apply()
        synchronized(this) {
            if (cached?.source?.isLive() != true) cached = null
        }
        return normalized
    }

    fun sourceLabel(source: Source): String =
        when (source) {
            Source.SHIZUKU -> "Shizuku 读取"
            Source.SETTINGS -> "系统设置读取"
            Source.MANUAL -> "手动填写"
            Source.REMEMBERED -> "此前读取并保存"
            Source.NONE -> "未获取"
        }

    /** 实时读取优先；否则手动填写；否则以前保存的实时结果；都没有则为占位值。 */
    internal fun choose(
        live: Resolved?,
        manual: String?,
        remembered: String?,
    ): Resolved =
        live
            ?: manual?.let { Resolved(it, Source.MANUAL) }
            ?: remembered?.let { Resolved(it, Source.REMEMBERED) }
            ?: Resolved(PLACEHOLDER_MAC, Source.NONE)

    /**
     * 接受 `AA:BB:CC:DD:EE:FF`、`aa-bb-…`、空格分隔或 12 位连续十六进制，输出大写冒号格式。
     * 手机的公共蓝牙地址由 IEEE 分配：拒绝占位值、全 0/全 F、组播位或本地管理位置位的地址
     * （`02:00:00:00:00:00` 正是本地管理位，也是系统给普通应用的占位）。
     */
    fun normalizeMac(input: String): String? {
        val hex = input.trim().replace(Regex("[:\\-\\s.]"), "")
        if (!Regex("[0-9A-Fa-f]{12}").matches(hex)) return null
        val mac = hex.uppercase().chunked(2).joinToString(":")
        val first = mac.substring(0, 2).toInt(16)
        if (first and 0x01 != 0 || first and 0x02 != 0) return null
        if (mac == "00:00:00:00:00:00") return null
        return mac
    }

    /** 不做 shell 查询：缓存，否则手动填写，否则已保存的值。UI 线程可调用。 */
    fun peek(context: Context): Resolved = stored(context)

    private fun stored(context: Context): Resolved {
        cached?.let { return it }
        return choose(null, manualMac(context), rememberedMac(context)).also { if (it.source != Source.NONE) cached = it }
    }

    private fun Source.isLive(): Boolean = this == Source.SHIZUKU || this == Source.SETTINGS

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
            MAC_REGEX.find(result.output)?.value?.let(::normalizeMac)
        } catch (_: Exception) {
            null
        }

    private fun resolveViaSettings(context: Context): String? =
        try {
            Settings.Secure
                .getString(context.contentResolver, "bluetooth_address")
                ?.takeIf { MAC_REGEX.matches(it) }
                ?.let(::normalizeMac)
        } catch (_: Exception) {
            null
        }

    private const val RETRY_QUIET_MILLIS = 10_000L
    private const val PREFS = "device_identity"
    private const val KEY_MANUAL = "manual_bluetooth_mac"
    private const val KEY_REMEMBERED = "resolved_bluetooth_mac"
}
