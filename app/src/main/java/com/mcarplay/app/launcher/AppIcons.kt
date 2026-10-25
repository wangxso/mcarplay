package com.mcarplay.app.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** 应用图标位图化工具（iOS 风格圆角；桌面网格 / dock / 设置页共用）。 */
object AppIcons {
    /**
     * Drawable → 圆角位图；[radiusFraction] 为圆角占边长比例（约 22%）。
     *
     * 在 Drawable 的副本上渲染（constantState.newDrawable；bounds 保存/恢复兜底）：
     * iconCache 中的实例被多个界面共同持有，直接 setBounds 会污染其他使用者——
     * 实机故障根因：桌面网格按自己的 iconSize 改写共享 Drawable 的 bounds 后，
     * dock 里同一实例的缩放矩阵失配（图标变小且偏左，点 home 触发网格重绑后复现）。
     */
    fun rounded(
        drawable: Drawable,
        size: Int,
        radiusFraction: Float = 0.22f,
    ): Bitmap {
        if (size <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * radiusFraction
        val path =
            Path().apply {
                addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW)
            }
        val target =
            runCatching { drawable.constantState?.newDrawable()?.mutate() }
                .getOrNull() ?: drawable
        val savedBounds = Rect(target.bounds)
        canvas.save()
        canvas.clipPath(path)
        target.setBounds(0, 0, size, size)
        target.draw(canvas)
        canvas.restore()
        target.bounds = savedBounds
        return bitmap
    }
}
