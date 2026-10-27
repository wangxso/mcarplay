package com.mcarplay.app.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_dialog_panel)
                setPadding(dp(20), dp(8), dp(20), dp(12))
            }

        fun label(text: String): TextView =
            TextView(activity).apply {
                this.text = text
                textSize = 14f
                setTextColor(activity.getColor(R.color.m3_on_surface))
                setPadding(0, dp(6), 0, dp(6))
                body.addView(this)
            }
        val target = TargetDevicePrefs.get(activity)
        label(target?.let { "当前设备：${it.name}" } ?: "请先选择已配对的车机")
        label("检测会尝试当前设备的连接参数。画面、声音和触控需实际确认，保存后下次自动复用。")
        val status = label("")
        val counters = label("")

        fun check(title: String): Spinner {
            label(title)
            return Spinner(activity).apply {
                adapter =
                    ArrayAdapter(
                        activity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf("未验证／跳过", "确认正常", "有问题"),
                    )
                body.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        val video = check("车机画面")
        val audio = check("车机扬声器声音（含速度与音调）")
        val touch = check("点击与滑动位置")

        fun button(text: String): Button =
            Button(activity).apply {
                this.text = text
                isAllCaps = false
                body.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        val save = button("保存已确认的适配结果")
        val start = button(if (target == null) "选择设备" else "开始检测（含声音）")
        val retry = button("蓝牙阶段重试")
        val cancel = button("取消检测并断开")
        val delete = button("删除这台设备的适配记录")
        val message = label("")
        val scroll = ScrollView(activity).apply { addView(body) }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle("检测当前车机")
                .setView(scroll)
                .setNegativeButton("收起") { _, _ -> }
                .create()
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
