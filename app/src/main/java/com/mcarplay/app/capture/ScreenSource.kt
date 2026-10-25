package com.mcarplay.app.capture

import android.view.Surface

/**
 * 车机画面来源抽象：编码器输入 Surface 由画面源填充。
 * 当前唯一实现：[HiddenDisplaySource]（隐形屏：受信虚拟屏 + Shizuku 把应用启动进去）。
 *
 * （原「桌面」（Presentation 自绘）与「镜像主屏」（MediaProjection AUTO_MIRROR）
 *   两种模式已按需移除，2026-10-10。）
 */
interface ScreenSource {
    val name: String

    /** 绑定（或重绑）编码 Surface；返回是否成功 */
    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean

    /** 解绑 Surface（保留底层资源以便快速重连） */
    fun detach()

    /** 释放全部资源 */
    fun release()
}
