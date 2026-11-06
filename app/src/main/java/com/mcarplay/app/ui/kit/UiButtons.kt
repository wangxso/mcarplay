package com.mcarplay.app.ui.kit

import android.content.Context
import android.widget.TextView
import androidx.annotation.StyleRes
import com.mcarplay.app.R

/**
 * 三档按钮（见 docs/UI-REDESIGN.md §5.1）：主按钮蓝底白字，次按钮淡蓝底蓝字，危险按钮淡红底红字。
 * 外观全部来自 `Widget.Ui.Button.*` 样式，这里只负责按档位创建。
 */
enum class UiButtonKind(
    @StyleRes val style: Int,
) {
    PRIMARY(R.style.Widget_Ui_Button_Primary),
    SECONDARY(R.style.Widget_Ui_Button_Secondary),
    DANGER(R.style.Widget_Ui_Button_Danger),
}

object UiButtons {
    fun create(
        context: Context,
        kind: UiButtonKind,
        label: CharSequence,
    ): TextView = TextView(context, null, 0, kind.style).apply { text = label }
}
