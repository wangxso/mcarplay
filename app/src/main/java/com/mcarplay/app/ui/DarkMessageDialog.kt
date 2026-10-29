package com.mcarplay.app.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcarplay.app.R

/**
 * 深色消息弹窗：标题 + 正文（超长可滚动）+ 操作按钮。
 *
 * **为什么不用裸 `AlertDialog.Builder.setMessage`**：系统对话框在
 * `Theme.AppCompat.DayNight` 下是浅色主题，与本应用全深色 UI 割裂
 * （2026-10-18 真机截图暴露）。这里自绘 `bg_dialog_panel` 面板，
 * 与免责声明 / 选择目标车机两个弹窗保持一致。
 *
 * 布局规则：1~2 个操作横排靠右；3 个及以上竖排整宽（窄屏横排会挤爆）。
 */
object DarkMessageDialog {
    enum class Emphasis {
        /** 实心强调按钮（bg_cta_fill）。 */
        PRIMARY,

        /** 文字按钮（bg_tbtn）。 */
        TEXT,

        /** 危险操作（暗红底）。 */
        DANGER,
    }

    /**
     * @param dismissOnClick 点击后是否自动关闭（默认 true）。需要保留弹窗
     *        （例如校验失败后让用户重试）时传 false。
     */
    data class Action(
        val label: String,
        val emphasis: Emphasis = Emphasis.TEXT,
        val dismissOnClick: Boolean = true,
        val onClick: () -> Unit,
    )

    /**
     * @param actions 按显示顺序排列；横排时靠右，竖排时自上而下
     * @param cancelable 返回键/点外部是否可关闭
     * @param onCancel 通过取消方式关闭时回调（null = 不关心）
     */
    fun show(
        activity: Activity,
        title: String,
        message: CharSequence,
        actions: List<Action>,
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_message, null)
        val titleView = view.findViewById<TextView>(R.id.msgTitle)
        val bodyView = view.findViewById<TextView>(R.id.msgBody)
        val scroll = view.findViewById<ScrollView>(R.id.msgScroll)
        val actionRow = view.findViewById<LinearLayout>(R.id.msgActions)
        titleView.text = title
        bodyView.text = message

        val density = activity.resources.displayMetrics.density
        val vertical = actions.size > 2
        actionRow.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        actionRow.gravity = if (vertical) Gravity.END else Gravity.END or Gravity.CENTER_VERTICAL

        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(view)
                .create()

        actions.forEachIndexed { index, action ->
            actionRow.addView(
                buildButton(activity, action, density, vertical, isFirst = index == 0) {
                    action.onClick()
                    if (action.dismissOnClick) dialog.dismiss()
                },
            )
        }

        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        if (cancelable) {
            dialog.setOnCancelListener { onCancel?.invoke() }
        }
        dialog.setOnShowListener {
            // 正文超长时夹到屏高 46% 并内部滚动；短文案贴合内容（固定高度会留大片空白）。
            // clampBody 同时作为旋转回调：横屏屏高变小后要重新夹紧，否则会撑出屏幕。
            val clampBody: (Activity) -> Unit = { ctx ->
                val maxBodyHeight = (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
                bodyView.post {
                    val target =
                        if (bodyView.height > maxBodyHeight) {
                            maxBodyHeight
                        } else {
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    if (scroll.layoutParams.height != target) {
                        scroll.layoutParams =
                            scroll.layoutParams.apply { height = target }
                    }
                }
            }
            // 宽度 92%、高度按内容自适应；旋转时 PanelWindow 会回调 clampBody 重算
            PanelWindow.apply(dialog, activity, onResize = clampBody)
            clampBody(activity)
        }
        dialog.show()
        return dialog
    }

    private fun buildButton(
        activity: Activity,
        action: Action,
        density: Float,
        vertical: Boolean,
        isFirst: Boolean,
        onClick: () -> Unit,
    ): TextView =
        TextView(activity).apply {
            text = action.label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            when (action.emphasis) {
                Emphasis.PRIMARY -> {
                    background = activity.getDrawable(R.drawable.bg_cta_fill)
                    setTextColor(activity.getColor(R.color.m3_on_primary))
                }

                Emphasis.DANGER -> {
                    // 危险动作：暗红底 + 浅红字（复用错误容器色）
                    background =
                        GradientDrawable().apply {
                            cornerRadius = 999f * density
                            setColor(activity.getColor(R.color.m3_err_container))
                        }
                    setTextColor(activity.getColor(R.color.m3_on_err_container))
                }

                Emphasis.TEXT -> {
                    // 竖排时次要按钮也上胶囊底：否则只有主按钮是实心整宽胶囊，
                    // 另两项是裸文字，视觉权重完全失衡（真机截图暴露）
                    background =
                        activity.getDrawable(
                            if (vertical) R.drawable.bg_btn_tonal else R.drawable.bg_tbtn,
                        )
                    setTextColor(
                        activity.getColor(
                            if (vertical) R.color.m3_on_sec_container else R.color.m3_primary,
                        ),
                    )
                }
            }
            layoutParams =
                if (vertical) {
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            // 竖排：按钮之间留 6dp（首个不留）
                            if (!isFirst) topMargin = (6 * density).toInt()
                        }
                } else {
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            // 横排：首项贴右缘，后续各项之间留 6dp
                            if (!isFirst) marginStart = (6 * density).toInt()
                        }
                }
            setOnClickListener {
                // 防连点：回调里通常要跳转/重开流程/写系统设置
                isEnabled = false
                onClick()
            }
        }
}
