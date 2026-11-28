package com.mcarplay.app.carlife

/**
 * CarWith 出画协商的唯一决策点：`0x18052` 功能清单是否声明 `DISABLE_RESOLUTION_OPTION=1`，
 * 以及随后 `0x18007` 请求的编码尺寸。
 *
 * 背景（issue #127）：车机 `/info displays[0]` 给出 2560x1200 这类超过默认 1920 长边上限的
 * 主屏几何时，[CarLifeProtocol.videoRequestFor] 会先把请求压成 1920x900；CarWith 不采纳时
 * 回退到自己的档位（实车报告 1920x1080），车机有声音但不显示画面。声明该 key 后应按车机
 * 几何精确请求。
 *
 * 分层约束：
 *  - [preferVehicleResolution] 只来自 `ReceiverCompatibility.Policy`，本类不按车型名分支；
 *  - 能否声明只由本轮 `/info` 的显示几何决定：`displayFromInfo` 为真且几何通过
 *    [CarLifeProtocol.vehicleResolutionRequestFor] 的合法范围/像素预算校验；
 *  - 几何缺失或非法时回退原有 1920 长边请求，并**不声明**该 key（安全回退）。
 */
object CarWithVideoNegotiation {
    data class Decision(
        /** 在 0x18052 功能清单里声明 DISABLE_RESOLUTION_OPTION=1。 */
        val declareDisableResolutionOption: Boolean,
        /** 0x18007 请求的宽。 */
        val width: Int,
        /** 0x18007 请求的高。 */
        val height: Int,
        /** 请求是否为车机 displays[0] 的精确尺寸（未经 1920 长边压缩）。 */
        val exactVehicleGeometry: Boolean,
    )

    /**
     * @param preferVehicleResolution `ReceiverCompatibility.Policy.preferVehicleResolution`
     * @param displayFromInfo 宽高是否来自本轮 `/info` 的 `displays[0]`（不是手机端兜底值）
     * @param displayWidth `/info displays[0].widthPixels`（无效时只影响回退请求）
     * @param displayHeight `/info displays[0].heightPixels`
     * @param maxLongSide 回退请求的长边上限（实验面板路径会临时放开）
     */
    fun decide(
        preferVehicleResolution: Boolean,
        displayFromInfo: Boolean,
        displayWidth: Int,
        displayHeight: Int,
        maxLongSide: Int = CarLifeProtocol.VIDEO_REQUEST_MAX_LONG_SIDE,
    ): Decision {
        val exact =
            if (preferVehicleResolution && displayFromInfo) {
                CarLifeProtocol.vehicleResolutionRequestFor(displayWidth, displayHeight)
            } else {
                null
            }
        if (exact != null) {
            return Decision(
                declareDisableResolutionOption = true,
                width = exact.first,
                height = exact.second,
                exactVehicleGeometry = true,
            )
        }
        val fallback = CarLifeProtocol.videoRequestFor(displayWidth, displayHeight, maxLongSide)
        return Decision(
            declareDisableResolutionOption = false,
            width = fallback.first,
            height = fallback.second,
            exactVehicleGeometry = false,
        )
    }
}
