package com.mcarplay.app.legal

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.ui.PanelWindow

/**
 * 免责声明弹窗（两种形态）：
 *
 * - **首次启动（强制）**：不可取消（返回键/外部点击都无效），「接受」需同时满足
 *   10 秒倒计时与「已滑到底」；「放弃使用」立即可点（拒绝不该被阻拦）。
 * - **菜单重看（只读）**：无倒计时、无滚动要求，一个「关闭」按钮。
 *
 * 倒计时用**绝对截止时间**（[SystemClock.elapsedRealtime]）而不是递减计数：
 * 屏幕旋转导致 Activity 重建时，把 deadline 透传回来即可继续剩余时间，
 * 不会从头再等 10 秒。
 */
object DisclaimerDialog {
    /** 强制接受前的最短停留时间。 */
    const val COUNTDOWN_MS = 10_000L

    private const val WINDOW_HEIGHT_FRACTION = 0.78f

    /**
     * 显示弹窗。
     *
     * @param requireAcceptance true=首次启动强制接受；false=菜单只读
     * @param deadlineAt 倒计时截止的 elapsedRealtime（仅强制形态使用）
     * @param onDecline 点「放弃使用」后的回调（仅强制形态）
     * @param onDismiss 任何方式关闭后的回调（Activity 用来清引用）
     */
    fun show(
        activity: Activity,
        requireAcceptance: Boolean,
        deadlineAt: Long = 0L,
        onDecline: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_disclaimer, null)
        val subtitle = view.findViewById<TextView>(R.id.disclaimerSubtitle)
        val scroll = view.findViewById<ScrollView>(R.id.disclaimerScroll)
        val sections = view.findViewById<LinearLayout>(R.id.disclaimerSections)
        val blockHint = view.findViewById<TextView>(R.id.disclaimerBlockHint)
        val btnDecline = view.findViewById<TextView>(R.id.btnDecline)
        val btnAccept = view.findViewById<TextView>(R.id.btnAccept)

        renderSections(activity, sections)

        if (requireAcceptance) {
            subtitle.text = "首次使用前请完整阅读。接受后可在主界面底部随时重看。"
        } else {
            subtitle.text = "你可以随时回来查看本声明（当前版本 v${DisclaimerPrefs.CURRENT_VERSION}）。"
            btnDecline.visibility = View.GONE
            btnAccept.isEnabled = true
            btnAccept.text = "关闭"
            blockHint.visibility = View.GONE
        }

        val dialog =
            AlertDialog
                .Builder(activity)
                .setView(view)
                .create()

        // 强制形态：返回键与外部点击都不得关闭
        dialog.setCancelable(!requireAcceptance)
        dialog.setCanceledOnTouchOutside(!requireAcceptance)

        var handler: Handler? = null
        var ticker: Runnable? = null

        if (requireAcceptance) {
            val mainHandler = Handler(Looper.getMainLooper())
            handler = mainHandler
            var scrolledToBottom = false
            SessionController.appendLog(
                "DISCLAIMER: 弹出强制声明（倒计时 ${COUNTDOWN_MS / 1000} 秒 + 需滑到底）" +
                    " remain=${(deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)}ms",
            )

            fun refresh() {
                // 布局未完成时 ScrollView 高宽都是 0，"不可滚动" 会被当成 "内容不足一屏"，
                // 从而把 scrolledToBottom 永久置位（真机上表现为不用滚动就能接受）。
                // 必须等布局完成后再判：height>0 才算数。
                val layoutReady = scroll.height > 0
                val viewport = (scroll.height - scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(0)
                val contentScrollable = layoutReady && sections.height > viewport
                if (layoutReady && !scroll.canScrollVertically(1)) scrolledToBottom = true
                val now = SystemClock.elapsedRealtime()
                val remainingMs = (deadlineAt - now).coerceAtLeast(0L)
                val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
                val countdownDone = remainingMs <= 0L
                val enabled = canAcceptDisclaimer(countdownDone, contentScrollable, scrolledToBottom)
                btnAccept.isEnabled = enabled
                btnAccept.text = if (countdownDone) "接受" else "接受（${remainingSeconds}s）"
                val reason =
                    disclaimerBlockReason(
                        countdownDone = countdownDone,
                        contentScrollable = contentScrollable,
                        scrolledToBottom = scrolledToBottom,
                        remainingSeconds = remainingSeconds,
                    )
                blockHint.text = reason.orEmpty()
                blockHint.visibility = if (reason == null) View.GONE else View.VISIBLE
            }

            val looping =
                object : Runnable {
                    override fun run() {
                        refresh()
                        mainHandler.postDelayed(this, 200L)
                    }
                }
            ticker = looping
            mainHandler.post(looping)

            scroll.setOnScrollChangeListener { _, _, _, _, _ -> refresh() }
            // 首帧布局完成后再判一次（此刻才知道内容是否超过一屏）
            scroll.post { refresh() }

            btnAccept.setOnClickListener {
                if (!btnAccept.isEnabled) return@setOnClickListener
                DisclaimerPrefs.accept(activity)
                dialog.dismiss()
            }
            btnDecline.setOnClickListener {
                dialog.dismiss()
                onDecline?.invoke()
            }
        } else {
            btnAccept.setOnClickListener { dialog.dismiss() }
        }

        // 统一收尾：停定时器 + 通知调用方清引用（此前调用方再设一次监听会覆盖这里的清理）
        dialog.setOnDismissListener {
            ticker?.let { handler?.removeCallbacks(it) }
            onDismiss?.invoke()
        }

        dialog.setOnShowListener {
            // 宽度 92%、高度 78% 屏高；登记后旋转时由 Activity 调 resizeShowing 重算
            PanelWindow.apply(dialog, activity, WINDOW_HEIGHT_FRACTION)
        }
        dialog.show()
        return dialog
    }

    /** 逐节渲染：标题加粗 + 正文，节间留白。 */
    private fun renderSections(
        activity: Activity,
        container: LinearLayout,
    ) {
        val density = activity.resources.displayMetrics.density
        DisclaimerContent.sections.forEachIndexed { index, section ->
            val title = TextView(activity)
            title.text = section.title
            title.setTextColor(activity.getColor(R.color.m3_primary))
            title.textSize = 13.5f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            title.setPadding(0, (if (index == 0) 0 else 14 * density).toInt(), 0, (5 * density).toInt())
            container.addView(title)

            val body = TextView(activity)
            body.text = section.body
            body.setTextColor(activity.getColor(R.color.m3_on_var))
            body.textSize = 12.5f
            body.setLineSpacing(4 * density, 1f)
            body.setPadding(0, 0, 0, 0)
            container.addView(
                body,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }
}
