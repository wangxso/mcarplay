package com.mcarplay.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcarplay.app.R
import com.mcarplay.app.util.DeviceIdentity

/**
 * 「本机蓝牙地址」弹窗：Shizuku 读取与手动填写并存。实时读取成功时以它为准，
 * 手动填写只在读不到时使用；与 DarkMessageDialog 同一套深色面板与胶囊按钮。
 */
object BtIdentityDialog {
    fun show(
        activity: Activity,
        onChanged: () -> Unit,
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(14))
            }

        fun text(
            value: String,
            size: Float,
            color: Int,
            bold: Boolean = false,
            top: Int = 8,
        ): TextView =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(activity.getColor(color))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(dp(3).toFloat(), 1f)
                body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
            }
        text(activity.getString(R.string.main_bt_identity_title), 17f, R.color.m3_on_surface, bold = true, top = 0)
        text(
            activity.getString(R.string.bt_identity_intro),
            12.5f,
            R.color.m3_on_var,
        )
        val current = DeviceIdentity.cachedResolution()
        val manual = DeviceIdentity.manualMac(activity)
        text(
            activity.getString(
                R.string.bt_identity_current,
                current?.let { activity.getString(R.string.bt_identity_value, it.mac, DeviceIdentity.sourceLabel(activity, it.source)) }
                    ?: activity.getString(R.string.bt_identity_unknown),
            ) +
                if (manual != null && current != null && current.source != DeviceIdentity.Source.MANUAL && current.mac != manual) {
                    activity.getString(R.string.bt_identity_mismatch, manual)
                } else {
                    ""
                },
            13f,
            R.color.m3_primary,
            top = 10,
        )
        val input =
            EditText(activity).apply {
                setText(manual.orEmpty())
                hint = activity.getString(R.string.bt_identity_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                setBackgroundResource(R.drawable.bg_ssid_sel)
                setTextColor(0xFFC4D2D1.toInt())
                setHintTextColor(0xFF5A6B7A.toInt())
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
        val error = text("", 12f, R.color.m3_error, top = 6)
        val actions =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(ScrollView(activity).apply { setBackgroundResource(R.drawable.bg_dialog_panel) }.also { it.addView(body) })
                .create()

        fun action(
            label: String,
            primary: Boolean,
            onClick: () -> Unit,
        ) {
            actions.addView(
                TextView(activity).apply {
                    text = label
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    if (primary) {
                        setBackgroundResource(R.drawable.bg_cta_fill)
                        setTextColor(activity.getColor(R.color.m3_on_primary))
                    } else {
                        setBackgroundResource(R.drawable.bg_tbtn)
                        setTextColor(activity.getColor(R.color.m3_primary))
                    }
                    setOnClickListener { onClick() }
                },
                LinearLayout
                    .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (actions.childCount > 0) marginStart = dp(6) },
            )
        }
        action(activity.getString(R.string.action_cancel), primary = false) { dialog.dismiss() }
        if (manual != null) {
            action(activity.getString(R.string.bt_identity_clear), primary = false) {
                DeviceIdentity.setManualMac(activity, null)
                dialog.dismiss()
                onChanged()
            }
        }
        action(activity.getString(R.string.action_save), primary = true) {
            val raw = input.text.toString()
            if (raw.isBlank()) {
                error.text = activity.getString(R.string.bt_identity_empty)
                return@action
            }
            if (DeviceIdentity.setManualMac(activity, raw) == null) {
                error.text = activity.getString(R.string.bt_identity_invalid)
                return@action
            }
            dialog.dismiss()
            onChanged()
        }
        dialog.setOnShowListener { PanelWindow.apply(dialog, activity) }
        dialog.show()
        return dialog
    }
}
