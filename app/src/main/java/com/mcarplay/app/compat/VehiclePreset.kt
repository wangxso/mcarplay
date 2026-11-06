package com.mcarplay.app.compat

import android.content.Context

/**
 * 主页手动选择的车型适配。[AUTO] 按车机自报信号自动判定；其余在自动结果之上叠加该车型已登记的适配开关，
 * 供自动判定认不出（固件号未登记、型号字段缺失）的车机使用。选项只对应仓库里已有的例外规则，
 * 不代表该车型已被实车验证，见 docs/CARWITH-ENTRY-FINDINGS.md。
 */
enum class VehiclePreset {
    AUTO,
    GENERIC,
    CHERY,
    VOLKSWAGEN,
    WINPLAY,
    DIPLAY,
    CLASSIC_MIB3,
    ;

    companion object {
        fun parse(name: String?): VehiclePreset = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** 当前生效的手动车型（进程内；启动时从 [CompatibilityPrefs] 载入，设置变更时同步）。 */
object VehiclePresetRuntime {
    @Volatile
    var current: VehiclePreset = VehiclePreset.AUTO

    fun load(context: Context) {
        current = CompatibilityPrefs.vehiclePreset(context)
    }

    fun set(
        context: Context,
        preset: VehiclePreset,
    ) {
        CompatibilityPrefs.setVehiclePreset(context, preset)
        current = preset
    }
}
