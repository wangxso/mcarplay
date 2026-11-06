package com.mcarplay.app.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.mcarplay.app.R

/**
 * 设置行：左侧标题 + 可选说明，右侧可选「当前值 / 箭头 / 开关」。高度不低于 56dp，整行可点。
 * 放进 [SettingGroup] 使用：分组负责圆角卡片与行间分隔线。
 */
class SettingRow
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        private val titleView = TextView(context, null, 0, R.style.TextAppearance_Ui_Body)
        private val summaryView = TextView(context, null, 0, R.style.TextAppearance_Ui_Caption)
        private val valueView = TextView(context, null, 0, R.style.TextAppearance_Ui_Label)
        private val chevron = ImageView(context)
        private val toggle = SwitchCompat(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(56)
            val pad = context.resources.getDimensionPixelSize(R.dimen.ui_space_4)
            setPaddingRelative(pad, context.dp(8), pad, context.dp(8))
            isClickable = true
            isFocusable = true
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(context.getColor(R.color.ui_ripple)),
                    null,
                    ColorDrawable(Color.WHITE),
                )

            summaryView.visibility = GONE
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            texts.addView(titleView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            texts.addView(summaryView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            valueView.visibility = GONE
            addView(
                valueView,
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart =
                        pad
                },
            )

            chevron.setImageResource(R.drawable.ic_chev_right)
            chevron.imageTintList = ColorStateList.valueOf(context.getColor(R.color.ui_text_secondary))
            chevron.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            chevron.visibility = GONE
            addView(chevron, LayoutParams(context.dp(20), context.dp(20)).apply { marginStart = context.dp(4) })

            toggle.visibility = GONE
            toggle.thumbDrawable = context.getDrawable(R.drawable.ui_sw_thumb)
            toggle.trackDrawable = context.getDrawable(R.drawable.ui_sw_track)
            toggle.isClickable = false
            toggle.isFocusable = false
            addView(
                toggle,
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = pad },
            )
        }

        fun setTitle(text: CharSequence): SettingRow = apply { titleView.text = text }

        fun setSummary(text: CharSequence?): SettingRow =
            apply {
                summaryView.text = text
                summaryView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
            }

        /** 右侧的当前值（如「跟随系统」）。 */
        fun setValue(text: CharSequence?): SettingRow =
            apply {
                valueView.text = text
                valueView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
            }

        fun showChevron(show: Boolean = true): SettingRow = apply { chevron.visibility = if (show) VISIBLE else GONE }

        /** 把这一行变成开关行：点整行切换，[onChange] 收到新值。 */
        fun asSwitch(
            checked: Boolean,
            onChange: (Boolean) -> Unit,
        ): SettingRow =
            apply {
                toggle.visibility = VISIBLE
                toggle.isChecked = checked
                setOnClickListener {
                    toggle.toggle()
                    onChange(toggle.isChecked)
                }
            }

        fun setChecked(checked: Boolean) {
            toggle.isChecked = checked
        }

        fun onClick(action: () -> Unit): SettingRow = apply { setOnClickListener { action() } }
    }
