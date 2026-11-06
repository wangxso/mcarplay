package com.mcarplay.app.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import com.mcarplay.app.R
import com.mcarplay.app.diagnostics.ConnectionProgress

/**
 * 分层进度条：每层一段圆角色条，段数随 [ConnectionProgress.LAYERS]。
 * 已完成 = accent，进行中 = 半透明 accent，失败 = error，主动停止 = 次要文字色，未到达 = track。
 * 只负责画；文字（当前层名称与耗时）由 [StatusCard] 之类的宿主给出。
 */
class StepperView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var steps: List<ConnectionProgress.StepState> = emptyList()
        private val gap = context.dp(3)
        private val barHeight = context.dp(5)

        /** 传 null 或空表示没有进度（整条按未到达画一个占位）。 */
        fun setSteps(value: List<ConnectionProgress.Step>?) {
            val next = value?.map { it.state }.orEmpty()
            if (next == steps) return
            steps = next
            invalidate()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), barHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val states = steps.ifEmpty { ConnectionProgress.LAYERS.map { ConnectionProgress.StepState.PENDING } }
            val count = states.size
            val segment = (width - paddingLeft - paddingRight - gap * (count - 1)).toFloat() / count
            if (segment <= 0f) return
            val radius = barHeight / 2f
            states.forEachIndexed { index, state ->
                paint.color = context.getColor(colorFor(state))
                val left = paddingLeft + index * (segment + gap)
                rect.set(left, 0f, left + segment, barHeight.toFloat())
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }

        companion object {
            @ColorRes
            fun colorFor(state: ConnectionProgress.StepState): Int =
                when (state) {
                    ConnectionProgress.StepState.DONE -> R.color.ui_accent
                    ConnectionProgress.StepState.ACTIVE -> R.color.ui_accent_soft
                    ConnectionProgress.StepState.FAILED -> R.color.ui_error
                    ConnectionProgress.StepState.STOPPED -> R.color.ui_text_secondary
                    ConnectionProgress.StepState.PENDING -> R.color.ui_track
                }
        }
    }
