package com.mcarplay.app.compat

import android.content.Context

/**
 * 主页手动选择的车型适配。[AUTO] 按车机自报信号自动判定；其余在自动结果之上叠加该车型已登记的适配开关，
 * 供自动判定认不出（固件号未登记、型号字段缺失）的车机使用。选项只对应仓库里已有的例外规则，
 * 不代表该车型已被实车验证，见 docs/CARWITH-ENTRY-FINDINGS.md。
 *
 * 选项按**接收端家族**而不是整车品牌登记：[MERCEDES_MB_DPP] 只覆盖 issue #55 报告中的
 * `Mercedes-Benz / MB_D-PP-3|4 / AirPlay 320.17.7` 单次 RECORD，不把其他奔驰车机纳入同一规则。
 * [MERCEDES_MB_E] 是 issue #62（`MB_E-P-4 / 480.7.4`）的**实验开关**：保持 `displays[0]` 车机几何，
 * 在 CarWith 功能协商里声明 `DISABLE_RESOLUTION_OPTION=1`，不代表该车型已验证。
 * 宝马没有可开关的品牌例外（现有 BMW 改动都是按协议事实分派的通用路径），因此不设宝马预设。
 */
enum class VehiclePreset {
    AUTO,
    GENERIC,
    CHERY,
    VOLKSWAGEN,
    WINPLAY,
    DIPLAY,
    CLASSIC_MIB3,

    /** 奔驰 MB_D-PP（320.17.7）接收端家族；见 [ReceiverCompatibility.Policy.singleRecordPerSession]。 */
    MERCEDES_MB_DPP,

    /**
     * 奔驰 MB_E-P / 2025 GLC（issue #62，AirPlay 480.7.4）：**实验选项，尚无成功实车验证**。
     *
     * 和 [MERCEDES_MB_DPP] 不是同一代际，不要混用。本项保持 `displays[0]` 的车机几何，打开
     * [ReceiverCompatibility.Policy.preferVehicleResolution]（CarWith `DISABLE_RESOLUTION_OPTION=1`），
     * 并按 HyperCar 同车机的成功时序把隧道 MFi 固定为 32B、先 RECORD 再声明资源/建主屏流，让
     * SETUP/屏幕/RECORD/TEARDOWN 共用绝对会话 URI，并撤回本端未实现的 `mainBuffered` 提议；
     * 不再使用 `displayPanels` 的 1728 高假设。
     * 默认自动判定不会启用；文案必须如实标注「实验」。
     */
    MERCEDES_MB_E,
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
