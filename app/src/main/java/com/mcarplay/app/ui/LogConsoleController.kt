package com.mcarplay.app.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mcarplay.app.R
import com.mcarplay.app.SessionController

/**
 * 日志控制台（主界面里的深色滚动文本，从 MainActivity 拆出，行为不变）：全量渲染、逐行追加、
 * 在底部时自动跟底、上翻阅读时显示「回到底部」按钮。
 */
class LogConsoleController(
    private val activity: AppCompatActivity,
    private val logText: TextView,
) {
    fun updateBottomButton() {
        activity.findViewById<ImageView>(R.id.btnLogBottom)?.visibility =
            if (atBottom()) View.INVISIBLE else View.VISIBLE
    }

    /** 日志内容最大滚动偏移。 */
    private fun maxScroll(): Int {
        val layout = logText.layout ?: return 0
        return (
            layout.getLineBottom(layout.lineCount - 1) +
                logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
    }

    /** 是否已在底部（40px 容差：吸收行高舍入，避免永远差几像素判不到底）。 */
    private fun atBottom(): Boolean = maxScroll() - logText.scrollY <= 40

    fun scrollToBottom() {
        // +16px 过冲补齐舍入差（控制台深色底，过冲不可见；View 不钳制但视觉无感）
        logText.scrollTo(0, maxScroll() + 16)
        logText.post { updateBottomButton() }
    }

    fun render(text: String) {
        // 改文本前已在底部（或用户上翻阅读中）：决定本次是否自动跟底
        val follow = atBottom()
        logText.text =
            if (SessionController.logEnabled) text else activity.getString(R.string.ma_log_hidden)
        logText.post {
            if (follow) scrollToBottom() else updateBottomButton()
        }
    }

    /**
     * 增量追加一行日志（SessionController 逐行直送；此前每行全量 toString + setText
     * 整段文本，高频日志下 UI/GC 双重放大）。超限时从进程缓冲全量对账一次完成裁剪。
     */
    fun append(line: String) {
        val follow = atBottom()
        if (logText.text.length > 44_000) {
            render(SessionController.logText())
            return
        }
        logText.append(line)
        logText.post {
            if (follow) scrollToBottom() else updateBottomButton()
        }
    }
}
