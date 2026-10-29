package com.mcarplay.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import com.mcarplay.app.R
import com.mcarplay.app.update.ReleaseNotes
import com.mcarplay.app.update.WhatsNew

/** 更新后首次打开时的「更新内容」弹窗（按当前界面语言展示 CHANGELOG 对应一半）。 */
object WhatsNewDialog {
    fun show(
        activity: Activity,
        pending: WhatsNew.Pending,
    ): AlertDialog {
        val title =
            if (pending.tag == ReleaseNotes.UNRELEASED) {
                activity.getString(R.string.whats_new_title_unreleased)
            } else {
                activity.getString(R.string.whats_new_title, pending.tag)
            }
        return DarkMessageDialog.show(
            activity = activity,
            title = title,
            message = render(pending.notes),
            actions =
                listOf(
                    DarkMessageDialog.Action(activity.getString(R.string.whats_new_ok), DarkMessageDialog.Emphasis.PRIMARY) {},
                ),
        )
    }

    /** 摘要一段 + 条目列表；`**粗体**` 保留为粗体，代码标记去掉。 */
    fun render(notes: ReleaseNotes.Localized): CharSequence {
        val out = SpannableStringBuilder()
        notes.summary?.let { appendInline(out, it) }
        notes.items.forEach { item ->
            if (out.isNotEmpty()) out.append(if (out.endsWith("\n")) "\n" else "\n\n")
            out.append("• ")
            appendInline(out, item)
            out.append("\n")
        }
        return out.trimEnd()
    }

    private fun appendInline(
        out: SpannableStringBuilder,
        text: String,
    ) {
        val (plain, bold) = ReleaseNotes.inline(text)
        val offset = out.length
        out.append(plain)
        bold.forEach { range ->
            out.setSpan(StyleSpan(Typeface.BOLD), offset + range.first, offset + range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
