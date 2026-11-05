package com.mcarplay.app.ui.kit

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.mcarplay.app.R

/**
 * 底部面板：自下滑入的圆角面板，顶部有拖动条样式的把手，标题 + 任意内容 + 可选操作按钮。
 * 用于「选择目标设备」「检测向导」这类需要多行内容的选择，不再各自带背景。
 * 面板底色用页面灰 `ui_bg`，里面的 [SettingGroup] 白色卡片才能浮出来；内容高度不超过屏高的 [MAX_HEIGHT_FRACTION]，超出内部滚动。
 */
object UiBottomSheet {
    const val MAX_HEIGHT_FRACTION = 0.85f

    fun show(
        activity: Activity,
        title: String,
        content: View,
        actions: List<UiDialog.Action> = emptyList(),
        cancelable: Boolean = true,
        onCancel: (() -> Unit)? = null,
    ): AppCompatDialog {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val radius = activity.resources.getDimension(R.dimen.ui_radius_card)
        val handle =
            View(activity).apply {
                background =
                    GradientDrawable().apply {
                        cornerRadius = activity.dp(2).toFloat()
                        setColor(activity.getColor(R.color.ui_track))
                    }
            }
        val titleView = TextView(activity, null, 0, R.style.TextAppearance_Ui_Title).apply { text = title }
        val scroll = MaxHeightScrollView(activity, (activity.resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt())
        scroll.addView(content, ViewGroup.LayoutParams(match, wrap))
        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        setColor(activity.getColor(R.color.ui_bg))
                        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    }
                val pad = resources.getDimensionPixelSize(R.dimen.ui_space_5)
                setPadding(pad, activity.dp(8), pad, pad)
                addView(handle, LinearLayout.LayoutParams(activity.dp(36), activity.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(titleView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = activity.dp(16) })
                addView(scroll, LinearLayout.LayoutParams(match, wrap).apply { topMargin = activity.dp(12) })
                if (actions.isNotEmpty()) addView(actionRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = activity.dp(16) })
            }

        val dialog = AppCompatDialog(activity)
        actions.forEachIndexed { index, action ->
            val button =
                UiButtons.create(activity, action.kind, action.label).apply {
                    setOnClickListener {
                        isEnabled = false
                        action.onClick()
                        if (action.dismissOnClick) dialog.dismiss()
                    }
                }
            actionRow.addView(button, LinearLayout.LayoutParams(match, wrap).apply { if (index > 0) topMargin = activity.dp(8) })
        }
        dialog.setContentView(root)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        if (cancelable) dialog.setOnCancelListener { onCancel?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.Animation_Ui_BottomSheet)
        }
        dialog.show()
        return dialog
    }

    /** 内容超过 [maxHeight] 时夹住并内部滚动，短内容贴合。 */
    private class MaxHeightScrollView(
        context: Context,
        private val maxHeight: Int,
    ) : ScrollView(context) {
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }
}
