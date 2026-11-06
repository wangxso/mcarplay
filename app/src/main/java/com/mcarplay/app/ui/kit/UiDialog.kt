package com.mcarplay.app.ui.kit

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcarplay.app.R
import com.mcarplay.app.ui.PanelWindow

/**
 * 统一确认框：白色圆角卡片 + 标题 + 正文（超长内部滚动）+ 胶囊操作按钮。
 * 接口与 [com.mcarplay.app.ui.DarkMessageDialog] 对应，迁移时只换调用方的类型名。
 *
 * 操作 1~2 个时横排等宽；3 个及以上竖排整宽。窗口尺寸与旋转重算沿用 [PanelWindow]。
 */
object UiDialog {
    data class Action(
        val label: String,
        val kind: UiButtonKind = UiButtonKind.SECONDARY,
        /** 点击后是否自动关闭；校验失败要留着让用户重试时传 false。 */
        val dismissOnClick: Boolean = true,
        val onClick: () -> Unit,
    )

    /** 操作按钮是否竖排：3 个及以上（窄屏横排会挤爆）。 */
    fun isVertical(actionCount: Int): Boolean = actionCount > 2

    fun show(
        activity: Activity,
        title: String,
        message: CharSequence,
        actions: List<Action>,
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
    ): AlertDialog {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val titleView = TextView(activity, null, 0, R.style.TextAppearance_Ui_Title).apply { text = title }
        val bodyView = TextView(activity, null, 0, R.style.TextAppearance_Ui_Body).apply { text = message }
        val scroll = ScrollView(activity).apply { addView(bodyView, ViewGroup.LayoutParams(match, wrap)) }
        val vertical = isVertical(actions.size)
        val actionRow =
            LinearLayout(activity).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ui_bg_card)
                val pad = resources.getDimensionPixelSize(R.dimen.ui_space_6)
                setPadding(pad, pad, pad, pad)
                addView(titleView, LinearLayout.LayoutParams(match, wrap))
                addView(scroll, LinearLayout.LayoutParams(match, wrap).apply { topMargin = activity.dp(12) })
                addView(actionRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = activity.dp(20) })
            }

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        val gap = activity.dp(8)
        actions.forEachIndexed { index, action ->
            val button =
                UiButtons.create(activity, action.kind, action.label).apply {
                    setOnClickListener {
                        // 防连点：回调里通常要跳转或写系统设置
                        isEnabled = false
                        action.onClick()
                        if (action.dismissOnClick) dialog.dismiss()
                    }
                }
            val lp =
                if (vertical) {
                    LinearLayout.LayoutParams(match, wrap).apply { if (index > 0) topMargin = gap }
                } else {
                    LinearLayout.LayoutParams(0, wrap, 1f).apply { if (index > 0) marginStart = gap }
                }
            actionRow.addView(button, lp)
        }

        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        if (cancelable) dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.setOnShowListener {
            // 正文超长时夹到屏高 46% 并内部滚动；旋转后由 PanelWindow 回调重新夹紧
            val clampBody: (Activity) -> Unit = { ctx ->
                val maxBodyHeight = (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt()
                bodyView.post {
                    val target = if (bodyView.height > maxBodyHeight) maxBodyHeight else ViewGroup.LayoutParams.WRAP_CONTENT
                    if (scroll.layoutParams.height != target) {
                        scroll.layoutParams = scroll.layoutParams.apply { height = target }
                    }
                }
            }
            PanelWindow.apply(dialog, activity, onResize = clampBody)
            clampBody(activity)
        }
        dialog.show()
        return dialog
    }
}
