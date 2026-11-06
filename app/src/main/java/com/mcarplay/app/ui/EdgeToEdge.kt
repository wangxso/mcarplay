package com.mcarplay.app.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 沉浸式系统栏：内容延伸到状态栏与底部手势条（小白条）之下，两条系统栏都透明，
 * 图标明暗随浅/深色主题。各页面自己负责把 [systemInsets] 让给内容，
 * 这样底部标签栏的底色可以一直铺到屏幕底缘。
 *
 * 假息屏遮罩是 FLAG_LAYOUT_NO_LIMITS 的全屏覆盖窗口，系统栏透明时它直接盖住系统栏下方的内容，
 * 所以不再需要把导航栏涂成黑色来和遮罩融合。
 */
object EdgeToEdge {
    /** 调用时机：`setContentView` 之后。 */
    fun enable(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 系统默认会在手势条后垫一层半透明遮罩（三键导航时），关掉才是真正的沉浸
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        val night =
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /** 每次系统栏/挖孔/输入法变化时回调；系统栏和挖孔合并，输入法不计入（键盘由各页自行处理）。 */
    fun onInsets(
        view: View,
        apply: (Insets) -> Unit,
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            apply(windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 整个内容区四周让出系统栏（用于子页面：标题栏在上，内容在下，没有贴底的栏）。 */
    fun padContent(activity: Activity) {
        enable(activity)
        val content = activity.findViewById<View>(android.R.id.content)
        onInsets(content) { content.setPadding(it.left, it.top, it.right, it.bottom) }
    }
}
