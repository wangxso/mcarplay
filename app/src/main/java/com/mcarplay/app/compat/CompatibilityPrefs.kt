package com.mcarplay.app.compat

import android.content.Context

/**
 * 兼容兜底开关（均默认关）。打开后恢复统一兼容策略之前的宽松行为，供个别车机
 * 尝试；它们没有实车证据，不是推荐设置，用户指南见 README「兼容兜底开关」。
 */
object CompatibilityPrefs {
    private const val PREFS = "settings"
    private const val KEY_PLAIN_FALLBACK = "compat_plain_fallback"
    private const val KEY_VEHICLE_PRESET = "compat_vehicle_preset"
    private const val KEY_AUDIO_BASELINE = "compat_audio_baseline"

    private const val KEY_PASSAT_RETURN = "compat_passat_return_strategy"
    private const val KEY_CHERY_DISABLE_BLUETOOTH = "compat_chery_disable_bluetooth"

    const val PLAIN_FALLBACK_DEFAULT = false
    const val AUDIO_BASELINE_DEFAULT = false
    const val CHERY_DISABLE_BLUETOOTH_DEFAULT = false

    /** 进程内缓存，供 [ReceiverCompatibility.resolve] 在无 Context 调用处读取；启动与设置变更时同步。 */
    @Volatile
    var cheryDisableBluetoothRuntime: Boolean = CHERY_DISABLE_BLUETOOTH_DEFAULT
        private set

    fun loadRuntime(context: Context) {
        cheryDisableBluetoothRuntime = cheryDisableBluetooth(context)
    }

    /** 配对失败（非 403）但 RTSP 连上过时，再试一次 et=NONE 明文会话。 */
    fun plainFallback(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PLAIN_FALLBACK, PLAIN_FALLBACK_DEFAULT)

    fun setPlainFallback(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PLAIN_FALLBACK, enabled)
            .apply()
    }

    /** 主页手动选择的车型适配；默认 [VehiclePreset.AUTO]。 */
    fun vehiclePreset(context: Context): VehiclePreset =
        VehiclePreset.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VEHICLE_PRESET, null))

    fun setVehiclePreset(
        context: Context,
        preset: VehiclePreset,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VEHICLE_PRESET, preset.name)
            .apply()
    }

    /** 接收端没有可用的媒体音频声明时，仍按协议基线（PCM 48k）尝试出声。 */
    fun audioBaseline(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUDIO_BASELINE, AUDIO_BASELINE_DEFAULT)

    fun setAudioBaseline(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_BASELINE, enabled)
            .apply()
    }

    /** 帕萨特「回主界面再进」实验策略；默认关，仅用户手动选择时生效。 */
    fun passatReturnStrategy(context: Context): PassatReturnStrategy =
        PassatReturnStrategy.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASSAT_RETURN, null))

    fun setPassatReturnStrategy(
        context: Context,
        strategy: PassatReturnStrategy,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PASSAT_RETURN, strategy.key)
            .apply()
    }

    /**
     * 奇瑞车机在 SETUP 后发送 `disableBluetooth`（请车机关掉自身蓝牙，iPhone 同款）。
     * 默认关：艾瑞泽 8 直接熄火下车时车机可能未及 `setBtEnable`，下次上车蓝牙会保持关闭。
     */
    fun cheryDisableBluetooth(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHERY_DISABLE_BLUETOOTH, CHERY_DISABLE_BLUETOOTH_DEFAULT)

    fun setCheryDisableBluetooth(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CHERY_DISABLE_BLUETOOTH, enabled)
            .apply()
        cheryDisableBluetoothRuntime = enabled
    }
}
