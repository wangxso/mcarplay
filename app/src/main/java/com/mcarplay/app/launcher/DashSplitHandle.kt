package com.mcarplay.app.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 仪表盘分屏拖拽把手：中缝内的三点指示条（横屏竖排三点、竖屏横排三点）。
 *
 * 按住拖动调整地图卡/音乐卡分屏——拖动中 [VirtualLauncher.previewDashSplit] 只做
 * 地图纹理拉伸渲染（不动虚拟屏），松手 [commitDashSplit] 才真实 resize。
 *
 * 两个触摸硬约定（2026-10-21 方案评审）：
 *  - DOWN 必须 `requestDisallowInterceptTouchEvent(true)`：宿主是 ViewPager2 的
 *    首页，否则横向拖动会被 pager 当翻页抢走；
 *  - 位移数学用 rawX/rawY：把手自身位置每帧随拖动变化，getX 相对自身是自引用。
 *    手指移出把手范围不影响事件归属（Android 按指针流绑定到 DOWN 接收者）。
 */
class DashSplitHandle(
    context: Context,
) : View(context) {
    /** true=竖屏（上下拖，拖 y）；false=横屏（左右拖，拖 x）。 */
    private var verticalSplit = false

    /** (把手中心·应用屏像素, 是否落定)；落定=true 时回调方应 commit。 */
    private var onDrag: ((Float, Boolean) -> Unit)? = null

    private var downCenter = 0f
    private var downRaw = 0f
    private var dragging = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()

    fun configure(
        verticalSplit: Boolean,
        onDrag: (Float, Boolean) -> Unit,
    ) {
        this.verticalSplit = verticalSplit
        this.onDrag = onDrag
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val raw = if (verticalSplit) event.rawY else event.rawX
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downRaw = raw
                downCenter = VirtualLauncher.dashHandleCenterScreen()
                dragging = downCenter >= 0
                isPressed = true
                invalidate()
                dragging
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                onDrag?.invoke(downCenter + (raw - downRaw), false)
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (!dragging) return false
                dragging = false
                isPressed = false
                invalidate()
                // CANCEL 同样按当前落点收尾：commit 幂等且钳制，与 UP 行为一致可预期
                onDrag?.invoke(downCenter + (raw - downRaw), true)
                true
            }

            else -> false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        if (isPressed) {
            val inset = 6f * density
            bgRect.set(inset, inset, width - inset, height - inset)
            bgPaint.color = 0x14FFFFFF
            canvas.drawRoundRect(bgRect, bgRect.height() / 2f, bgRect.height() / 2f, bgPaint)
        }
        dotPaint.color = if (isPressed) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
        val r = 2.5f * density
        val gap = 10f * density
        val cx = width / 2f
        val cy = height / 2f
        // 三点方向随手条：竖屏=横条→横排点；横屏=竖条→竖排点
        // （2026-10-22 两轮修正：首版不分支恒竖排，次版分支又接反）
        if (verticalSplit) {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx - gap, cy, r, dotPaint)
            canvas.drawCircle(cx + gap, cy, r, dotPaint)
        } else {
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx, cy - gap, r, dotPaint)
            canvas.drawCircle(cx, cy + gap, r, dotPaint)
        }
    }
}
