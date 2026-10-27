package com.mcarplay.app.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mcarplay.app.R
import com.mcarplay.app.bt.TargetDevicePrefs
import com.mcarplay.app.compat.ReceiverCheck
import com.mcarplay.app.compat.ReceiverDetection
import com.mcarplay.app.compat.ReceiverDetectionRuntime
import com.mcarplay.app.compat.ReceiverVerification

/** 手机侧检测面板；关闭面板不取消会话，草稿在进程中保留，重建后可以重新打开。 */
object ReceiverDetectionDialog {
    fun show(
        activity: AppCompatActivity,
        connecting: () -> Boolean,
        onStart: () -> Unit,
        onRetry: () -> Unit,
        onCancel: () -> Unit,
        onPickTarget: () -> Unit,
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()
        // 与 DarkMessageDialog 同一套视觉：标题和操作都画在 bg_dialog_panel 里，
        // 不用系统 AlertDialog 的标题/按钮（浅色主题下与深色 UI 割裂）。
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(14))
            }

        fun text(
            value: String,
            size: Float = 12.5f,
            color: Int = R.color.m3_on_var,
            bold: Boolean = false,
            top: Int = 6,
        ): TextView =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(activity.getColor(color))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(dp(3).toFloat(), 1f)
                body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
            }
        text("检测当前车机", size = 17f, color = R.color.m3_on_surface, bold = true, top = 0)
        val target = TargetDevicePrefs.get(activity)
        text(target?.let { "当前设备：${it.name}" } ?: "请先选择已配对的车机", size = 14f, color = R.color.m3_on_surface, top = 10)
        text("检测会尝试当前设备的连接参数。画面、声音和触控需实际确认，保存后下次自动复用。")
        val status = text("", size = 13f, color = R.color.m3_primary, top = 10)
        val counters = text("", size = 12f)

        val video = Choice(activity, body, "车机画面", ::dp)
        val audio = Choice(activity, body, "车机扬声器声音（含速度与音调）", ::dp)
        val touch = Choice(activity, body, "点击与滑动位置", ::dp)

        fun button(
            label: String,
            primary: Boolean = false,
            danger: Boolean = false,
        ): TextView =
            TextView(activity).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(11), dp(16), dp(11))
                when {
                    primary -> {
                        setBackgroundResource(R.drawable.bg_cta_fill)
                        setTextColor(activity.getColor(R.color.m3_on_primary))
                    }

                    danger -> {
                        background =
                            GradientDrawable().apply {
                                cornerRadius = 999f * density
                                setColor(activity.getColor(R.color.m3_err_container))
                            }
                        setTextColor(activity.getColor(R.color.m3_on_err_container))
                    }

                    else -> {
                        setBackgroundResource(R.drawable.bg_btn_tonal)
                        setTextColor(activity.getColor(R.color.m3_on_sec_container))
                    }
                }
                body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        val save = button("保存已确认的适配结果", primary = true).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) }
        val start = button(if (target == null) "选择设备" else "开始检测（含声音）", primary = true)
        val retry = button("蓝牙阶段重试")
        val cancel = button("取消检测并断开")
        val delete = button("删除这台设备的适配记录", danger = true)
        val message = text("", size = 12.5f, color = R.color.m3_primary)
        val close =
            TextView(activity).apply {
                text = "收起"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(activity.getColor(R.color.m3_primary))
                setBackgroundResource(R.drawable.bg_tbtn)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                body.addView(this, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            }
        val scroll =
            ScrollView(activity).apply {
                setBackgroundResource(R.drawable.bg_dialog_panel)
                addView(body)
            }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(scroll)
                .create()
        close.setOnClickListener { dialog.dismiss() }
        val handler = Handler(Looper.getMainLooper())
        var revision = ""
        val tick =
            object : Runnable {
                override fun run() {
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return
                    val binding = ReceiverDetectionRuntime.binding(target?.address)
                    val draft = ReceiverDetectionRuntime.detection.snapshot()?.takeIf { it.binding == binding }
                    val stored = ReceiverDetectionRuntime.saved(target?.address)
                    val nextRevision = "${draft?.id}/${draft?.observation?.session}/${draft?.trial}"
                    if (nextRevision != revision) {
                        revision = nextRevision
                        listOf(video, audio, touch).forEach { it.setSelection(0) }
                    }
                    status.text =
                        when {
                            ReceiverDetectionRuntime.storageError -> "本地记录读取失败，原文件已保留；请导出诊断报告。"
                            !ReceiverDetectionRuntime.ready -> "正在读取本地适配记录…"
                            draft != null -> stateText(draft)
                            stored != null && !ReceiverDetectionRuntime.environmentMatches(stored) ->
                                "手机或 CarWith 环境已变化，旧记录保留；请重新检测。"
                            stored != null -> "已有本地配置：画面${checkText(
                                stored.verification.video,
                            )}，声音${checkText(stored.verification.audio)}，触控${checkText(stored.verification.touch)}。"
                            else -> "尚未检测。请确认车机已打开无线 CarPlay 配对入口。"
                        }
                    counters.text =
                        draft?.let {
                            "源视频 ${it.sourceFrames} 帧 · 已发送 ${it.videoFrames} 帧\n" +
                                "原生音频 ${it.audioChunks} 段 · 已发音频 ${it.audioPackets} 包 · 已转发触控 ${it.touches} 次\n" +
                                "计数只用于检查链路，请根据车机实际效果确认。"
                        } ?: ""
                    video.isEnabled = draft?.canConfirmVideo == true
                    audio.isEnabled = draft?.canConfirmVideo == true
                    touch.isEnabled = draft?.canConfirmVideo == true
                    save.isEnabled =
                        draft?.state in listOf(ReceiverDetection.State.COLLECTING, ReceiverDetection.State.STOPPED) &&
                        draft?.capabilities != null &&
                        draft.canConfirmVideo &&
                        video.selectedItemPosition == 1 &&
                        !ReceiverDetectionRuntime.saving
                    start.isEnabled =
                        ReceiverDetectionRuntime.ready &&
                        !ReceiverDetectionRuntime.saving &&
                        !connecting() &&
                        draft?.state != ReceiverDetection.State.COLLECTING
                    retry.visibility =
                        if (ReceiverDetectionRuntime.detection.retryAvailable() &&
                            !ReceiverDetectionRuntime.saving
                        ) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                    cancel.visibility =
                        if (
                            draft?.state == ReceiverDetection.State.COLLECTING ||
                            (draft?.state == ReceiverDetection.State.STOPPED && connecting())
                        ) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                    delete.visibility = if (stored != null) View.VISIBLE else View.GONE
                    delete.isEnabled =
                        !connecting() &&
                        !ReceiverDetectionRuntime.saving &&
                        draft?.state != ReceiverDetection.State.COLLECTING
                    listOf(save, start, retry, cancel, delete).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
                    handler.postDelayed(this, 500)
                }
            }
        start.setOnClickListener {
            dialog.dismiss()
            if (target == null) onPickTarget() else onStart()
        }
        retry.setOnClickListener {
            DarkMessageDialog.show(
                activity,
                "尝试另一种蓝牙连接方式？",
                "本轮会暂时断开这台设备的蓝牙通话／音乐连接，再尝试 CarPlay。只进行一次对照；原适配记录会保留。",
                listOf(
                    DarkMessageDialog.Action("继续等待") {},
                    DarkMessageDialog.Action("断开后重试") { onRetry() },
                ),
            )
        }
        cancel.setOnClickListener { onCancel() }
        save.setOnClickListener {
            val values = ReceiverCheck.entries
            ReceiverDetectionRuntime.save(
                ReceiverVerification(
                    values[video.selectedItemPosition],
                    values[audio.selectedItemPosition],
                    values[touch.selectedItemPosition],
                ),
            ) { ok ->
                activity.runOnUiThread {
                    if (dialog.isShowing) message.text = if (ok) "适配结果已保存到本机。" else "未能保存，请检查视频已确认、待确认项的链路证据及存储状态。"
                }
            }
        }
        delete.setOnClickListener {
            val address = target?.address ?: return@setOnClickListener
            DarkMessageDialog.show(
                activity,
                "删除本地适配记录？",
                "只删除这台设备的适配参数，下次使用通用协商。",
                listOf(
                    DarkMessageDialog.Action("保留") {},
                    DarkMessageDialog.Action("删除", DarkMessageDialog.Emphasis.DANGER) {
                        ReceiverDetectionRuntime.delete(address) { ok ->
                            activity.runOnUiThread { if (dialog.isShowing) message.text = if (ok) "已删除本地适配记录。" else "删除失败，原记录已保留。" }
                        }
                    },
                ),
            )
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.setOnShowListener { handler.post(tick) }
        dialog.show()
        PanelWindow.apply(dialog, activity, heightFraction = 0.9f)
        return dialog
    }

    /** 三段式确认（未验证 / 正常 / 有问题），沿用设置面板的 bg_segment 分段控件代替系统 Spinner。 */
    private class Choice(
        activity: AppCompatActivity,
        parent: LinearLayout,
        title: String,
        dp: (Int) -> Int,
    ) {
        private val options = listOf("未验证", "确认正常", "有问题")
        private val selectedColor = 0xFFFFFFFF.toInt()
        private val idleColor = 0xFF8E8E93.toInt()
        private val row =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_segment)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
        private val cells: List<TextView>
        var selectedItemPosition = 0
            private set
        var isEnabled = true
            set(value) {
                field = value
                row.alpha = if (value) 1f else 0.45f
                cells.forEach { it.isEnabled = value }
            }

        init {
            parent.addView(
                TextView(activity).apply {
                    text = title
                    textSize = 14f
                    setTextColor(activity.getColor(R.color.m3_on_surface))
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
            )
            cells =
                options.mapIndexed { index, option ->
                    TextView(activity).apply {
                        text = option
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(7), dp(8), dp(7))
                        setOnClickListener { setSelection(index) }
                        row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            setSelection(0)
        }

        fun setSelection(position: Int) {
            selectedItemPosition = position
            cells.forEachIndexed { index, cell ->
                val selected = index == position
                if (selected) cell.setBackgroundResource(R.drawable.bg_segment_sel) else cell.background = null
                cell.setTextColor(if (selected) selectedColor else idleColor)
            }
        }
    }

    private fun stateText(draft: ReceiverDetection.Draft): String =
        when (draft.state) {
            ReceiverDetection.State.SAVING -> "正在保存…"
            ReceiverDetection.State.SAVED -> "已保存到本机。可收起面板继续使用，重新连接时自动复用。"
            ReceiverDetection.State.CANCELLED -> "检测已取消，原适配记录保留。"
            ReceiverDetection.State.STOPPED -> "本轮检测已停止：${issueText(draft.issue)}。已记录的结果可继续确认。"
            ReceiverDetection.State.COLLECTING -> {
                val stage =
                    mapOf(
                        "BLUETOOTH" to "等待车机蓝牙与连接信息",
                        "WIFI" to "加入车机网络",
                        "DISCOVERY" to "发现接收端",
                        "PAIRING" to "AirPlay 配对",
                        "SETUP" to "建立媒体会话",
                        "CARWITH" to "等待 CarWith 首帧",
                        "STREAMING" to "正在发送画面，请确认实际效果",
                    )[draft.stage] ?: "连接准备"
                "${if (draft.trial == 0) "基础检测" else "蓝牙对照"}：$stage" + (draft.issue?.let { "\n${issueText(it)}" } ?: "")
            }
        }

    private fun checkText(check: ReceiverCheck): String =
        when (check) {
            ReceiverCheck.CONFIRMED -> "已确认"
            ReceiverCheck.FAILED -> "有问题"
            ReceiverCheck.UNVERIFIED -> "未验证"
        }

    private fun issueText(issue: String?): String =
        when (issue) {
            "BUDGET_EXHAUSTED" -> "检测等待已达到上限，未确定的项目没有判为不支持"
            "ENVIRONMENT_CHANGED" -> "环境已变化，请重新检测"
            "SAVE_FAILED" -> "保存失败，原适配记录保留"
            else ->
                com.mcarplay.app.diagnostics.DiagnosticCode.entries
                    .firstOrNull { it.name == issue }
                    ?.advice ?: "连接已结束"
        }
}
