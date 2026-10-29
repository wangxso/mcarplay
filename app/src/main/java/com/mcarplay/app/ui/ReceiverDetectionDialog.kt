package com.mcarplay.app.ui

import android.content.Context
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
        text(activity.getString(R.string.main_detection_title), size = 17f, color = R.color.m3_on_surface, bold = true, top = 0)
        val target = TargetDevicePrefs.get(activity)
        text(
            target?.let {
                activity.getString(R.string.rd_current_device, it.name)
            } ?: activity.getString(R.string.rd_pick_first),
            size = 14f,
            color = R.color.m3_on_surface,
            top = 10,
        )
        text(activity.getString(R.string.rd_intro))
        val status = text("", size = 13f, color = R.color.m3_primary, top = 10)
        val counters = text("", size = 12f)

        val video = Choice(activity, body, activity.getString(R.string.rd_check_video), ::dp)
        val audio = Choice(activity, body, activity.getString(R.string.rd_check_audio), ::dp)
        val touch = Choice(activity, body, activity.getString(R.string.rd_check_touch), ::dp)

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
        val save =
            button(activity.getString(R.string.rd_save), primary = true).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin =
                    dp(16)
            }
        val start = button(activity.getString(if (target == null) R.string.main_pick_device else R.string.rd_start), primary = true)
        val retry = button(activity.getString(R.string.rd_retry_bt))
        val cancel = button(activity.getString(R.string.rd_cancel))
        val delete = button(activity.getString(R.string.rd_delete), danger = true)
        val message = text("", size = 12.5f, color = R.color.m3_primary)
        val close =
            TextView(activity).apply {
                text = activity.getString(R.string.rd_collapse)
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
                            ReceiverDetectionRuntime.storageError -> activity.getString(R.string.rd_storage_error)
                            !ReceiverDetectionRuntime.ready -> activity.getString(R.string.rd_loading)
                            draft != null -> stateText(activity, draft)
                            stored != null && !ReceiverDetectionRuntime.environmentMatches(stored) ->
                                activity.getString(R.string.rd_env_changed)
                            stored != null ->
                                activity.getString(
                                    R.string.rd_stored,
                                    checkText(activity, stored.verification.video),
                                    checkText(activity, stored.verification.audio),
                                    checkText(activity, stored.verification.touch),
                                )
                            else -> activity.getString(R.string.rd_not_detected)
                        }
                    counters.text =
                        draft?.let {
                            activity.getString(
                                R.string.rd_counters,
                                it.sourceFrames,
                                it.videoFrames,
                                it.audioChunks,
                                it.audioPackets,
                                it.touches,
                            )
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
                activity.getString(R.string.rd_retry_title),
                activity.getString(R.string.rd_retry_body),
                listOf(
                    DarkMessageDialog.Action(activity.getString(R.string.sc_keep_waiting)) {},
                    DarkMessageDialog.Action(activity.getString(R.string.rd_retry_action)) { onRetry() },
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
                    if (dialog.isShowing) message.text = activity.getString(if (ok) R.string.rd_saved else R.string.rd_save_failed)
                }
            }
        }
        delete.setOnClickListener {
            val address = target?.address ?: return@setOnClickListener
            DarkMessageDialog.show(
                activity,
                activity.getString(R.string.rd_delete_title),
                activity.getString(R.string.rd_delete_body),
                listOf(
                    DarkMessageDialog.Action(activity.getString(R.string.sc_saved_keep)) {},
                    DarkMessageDialog.Action(activity.getString(R.string.rd_delete_action), DarkMessageDialog.Emphasis.DANGER) {
                        ReceiverDetectionRuntime.delete(address) { ok ->
                            activity.runOnUiThread {
                                if (dialog.isShowing) {
                                    message.text =
                                        activity.getString(if (ok) R.string.rd_deleted else R.string.rd_delete_failed)
                                }
                            }
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
        private val options = listOf(R.string.rd_unverified, R.string.rd_confirmed_ok, R.string.rd_has_problem).map(activity::getString)
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

    private fun stateText(
        context: Context,
        draft: ReceiverDetection.Draft,
    ): String =
        when (draft.state) {
            ReceiverDetection.State.SAVING -> context.getString(R.string.rd_state_saving)
            ReceiverDetection.State.SAVED -> context.getString(R.string.rd_state_saved)
            ReceiverDetection.State.CANCELLED -> context.getString(R.string.rd_state_cancelled)
            ReceiverDetection.State.STOPPED -> context.getString(R.string.rd_state_stopped, issueText(context, draft.issue))
            ReceiverDetection.State.COLLECTING -> {
                val stage =
                    mapOf(
                        "BLUETOOTH" to R.string.rd_stage_bluetooth,
                        "WIFI" to R.string.rd_stage_wifi,
                        "DISCOVERY" to R.string.rd_stage_discovery,
                        "PAIRING" to R.string.stage_pairing,
                        "SETUP" to R.string.rd_stage_setup,
                        "CARWITH" to R.string.stage_carwith,
                        "STREAMING" to R.string.rd_stage_streaming,
                    )[draft.stage] ?: R.string.stage_precheck
                context.getString(
                    R.string.rd_collecting,
                    context.getString(if (draft.trial == 0) R.string.rd_trial_basic else R.string.rd_trial_bluetooth),
                    context.getString(stage),
                ) + (draft.issue?.let { "\n${issueText(context, it)}" } ?: "")
            }
        }

    private fun checkText(
        context: Context,
        check: ReceiverCheck,
    ): String =
        context.getString(
            when (check) {
                ReceiverCheck.CONFIRMED -> R.string.rd_check_confirmed
                ReceiverCheck.FAILED -> R.string.rd_has_problem
                ReceiverCheck.UNVERIFIED -> R.string.rd_unverified
            },
        )

    private fun issueText(
        context: Context,
        issue: String?,
    ): String =
        when (issue) {
            "BUDGET_EXHAUSTED" -> context.getString(R.string.rd_issue_budget)
            "ENVIRONMENT_CHANGED" -> context.getString(R.string.rd_issue_env)
            "SAVE_FAILED" -> context.getString(R.string.rd_issue_save_failed)
            else ->
                com.mcarplay.app.diagnostics.DiagnosticCode.entries
                    .firstOrNull { it.name == issue }
                    ?.let {
                        com.mcarplay.app.diagnostics.DiagnosticText
                            .advice(context, it)
                    }
                    ?: context.getString(R.string.rd_issue_ended)
        }
}
