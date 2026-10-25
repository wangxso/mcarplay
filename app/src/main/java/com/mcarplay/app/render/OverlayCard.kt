package com.mcarplay.app.render

import android.graphics.Bitmap

/**
 * 合成器叠加卡片（当前用于「强制横屏未生效」提示）。
 *
 * 位图由调用方在后台线程画好（Android Canvas 排版文字），GL 线程只负责上传纹理与
 * 绘制；矩形使用**输出画面坐标**（与编码流同一坐标系，顶部原点），
 * 因此触摸命中判定可直接复用同一份数值，无需再做缩放/翻转换算。
 */
class OverlayCard(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}
