package com.mcarplay.app.ui.kit

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import com.mcarplay.app.R
import com.mcarplay.app.diagnostics.ConnectionProgress

/**
 * 连接状态卡片：大标题 + 一行说明 + 分层进度条 + 一行结论/下一步。
 * 不带小标签；状态靠标题、进度条颜色和结论文字的颜色表达。操作按钮放在卡片下方，不在卡片里。
 */
class StatusCard
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr, R.style.Widget_Ui_Card) {
        /** 结论文字的语气，决定颜色（状态文字色，不是色块色）。 */
        enum class Tone(
            @ColorRes val color: Int,
        ) {
            NEUTRAL(R.color.ui_text_secondary),
            OK(R.color.ui_ok_text),
            ERROR(R.color.ui_error_text),
        }

        data class State(
            val title: CharSequence,
            val subtitle: CharSequence? = null,
            val steps: List<ConnectionProgress.Step>? = null,
            val detail: CharSequence? = null,
            val tone: Tone = Tone.NEUTRAL,
        )

        private val titleView = TextView(context, null, 0, R.style.TextAppearance_Ui_Display)
        private val subtitleView = TextView(context, null, 0, R.style.TextAppearance_Ui_Caption)
        private val stepper = StepperView(context)
        private val detailView = TextView(context, null, 0, R.style.TextAppearance_Ui_Label)

        init {
            orientation = VERTICAL
            val match = ViewGroup.LayoutParams.MATCH_PARENT
            val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
            addView(titleView, LayoutParams(match, wrap))
            addView(subtitleView, LayoutParams(match, wrap).apply { topMargin = context.dp(4) })
            addView(stepper, LayoutParams(match, wrap).apply { topMargin = context.dp(16) })
            addView(detailView, LayoutParams(match, wrap).apply { topMargin = context.dp(12) })
        }

        fun bind(state: State) {
            titleView.text = state.title
            subtitleView.setTextOrHide(state.subtitle)
            stepper.setSteps(state.steps)
            detailView.setTextOrHide(state.detail)
            detailView.setTextColor(context.getColor(state.tone.color))
        }

        private fun TextView.setTextOrHide(value: CharSequence?) {
            text = value
            visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
        }
    }
