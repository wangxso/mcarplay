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
        text("本机蓝牙地址", 17f, R.color.m3_on_surface, bold = true, top = 0)
        text(
            "WinPlay 等接收端会核对手机的真实蓝牙地址，Android 不允许普通应用直接读取。" +
                "已启动 Shizuku 时自动读取并保存；不用 Shizuku 时，请在手机设置里查到「蓝牙地址」后填在这里" +
                "（通常在「设置 → 我的设备 → 全部参数与信息」或「状态信息」中）。",
            12.5f,
            R.color.m3_on_var,
        )
        val current = DeviceIdentity.cachedResolution()
        val manual = DeviceIdentity.manualMac(activity)
        text(
            "当前使用：" +
                (current?.let { "${it.mac}（${DeviceIdentity.sourceLabel(it.source)}）" } ?: "尚未获取") +
                if (manual != null && current != null && current.source != DeviceIdentity.Source.MANUAL && current.mac != manual) {
                    "\n手动填写的 $manual 与实时读取不一致，连接时以实时读取为准。"
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
                hint = "例如 AA:BB:CC:DD:EE:FF"
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
        action("取消", primary = false) { dialog.dismiss() }
        if (manual != null) {
            action("清除手动地址", primary = false) {
                DeviceIdentity.setManualMac(activity, null)
                dialog.dismiss()
                onChanged()
            }
        }
        action("保存", primary = true) {
            val raw = input.text.toString()
            if (raw.isBlank()) {
                error.text = "请输入 12 位十六进制蓝牙地址"
                return@action
            }
            if (DeviceIdentity.setManualMac(activity, raw) == null) {
                error.text = "地址格式不对，或不是手机的公共蓝牙地址（例如 02:00:00:00:00:00 是系统占位值）"
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
