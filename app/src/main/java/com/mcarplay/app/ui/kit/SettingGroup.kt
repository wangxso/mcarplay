package com.mcarplay.app.ui.kit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mcarplay.app.R

/** 一组设置行：白色圆角卡片，行与行之间用左侧缩进的细分隔线，没有边框。 */
class SettingGroup
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        init {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.ui_bg_card)
            elevation = resources.getDimension(R.dimen.ui_card_elevation)
            // 行的水波纹不能溢出圆角
            clipToOutline = true
            val line =
                GradientDrawable().apply {
                    setColor(context.getColor(R.color.ui_track))
                    setSize(0, maxOf(1, context.dp(1) / 2))
                }
            dividerDrawable = InsetDrawable(line, resources.getDimensionPixelSize(R.dimen.ui_space_4), 0, 0, 0)
            showDividers = SHOW_DIVIDER_MIDDLE
        }

        fun addRow(row: SettingRow): SettingGroup =
            apply {
                addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
    }
