package com.mcarplay.app.ui.kit

import android.content.Context
import android.util.TypedValue

/** 界面组件内部的 dp→px 换算（令牌尺寸优先用 res 里的 dimen，这里只给组件内部的细节用）。 */
internal fun Context.dp(value: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

internal fun Context.dp(value: Int): Int = dp(value.toFloat())
