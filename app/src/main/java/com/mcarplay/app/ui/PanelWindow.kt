package com.mcarplay.app.ui

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import java.lang.ref.WeakReference

/**
 * 自绘面板弹窗的窗口尺寸统一口：宽度固定屏宽 92%，高度可给定比例。
 *
 * **旋转后必须重算**：这些尺寸在弹窗显示时按当时的屏幕方向算过一次，方向变了
 * 不重算就会保持竖屏宽度（横屏里只占中间一半）或超屏。
 * 谁在显示由 [register] 登记（弱引用，不持有泄漏；失效条目在下次重算时清掉）。
 */
object PanelWindow {
    /** 面板宽度占屏宽比例（与目标车机选择、免责声明一致）。 */
    const val WIDTH_FRACTION = 0.92f

    private data class Entry(
        val dialog: WeakReference<AlertDialog>,
        /** null = WRAP_CONTENT（短弹窗贴合内容）。 */
        val heightFraction: Float?,
        /** 旋转后额外重算（例如正文滚动区按新屏高重新夹紧）。 */
        val onResize: ((Activity) -> Unit)? = null,
    )

    private val showing = mutableListOf<Entry>()

    /**
     * 设窗口尺寸并登记（供 Activity 的 onConfigurationChanged 统一重算）。
     *
     * @param heightFraction null = WRAP_CONTENT
     * @param onResize 旋转后额外回调（此时窗口已按新方向 resize）
     */
    fun apply(
        dialog: AlertDialog,
        activity: Activity,
        heightFraction: Float? = null,
        onResize: ((Activity) -> Unit)? = null,
    ) {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sizeTo(dialog, activity, heightFraction)
        register(dialog, heightFraction, onResize)
    }

    /** 屏幕方向/尺寸变化后重算所有仍在显示的弹窗。 */
    fun resizeShowing(activity: Activity) {
        showing.removeAll { it.dialog.get()?.isShowing != true }
        showing.forEach { entry ->
            val dialog = entry.dialog.get() ?: return@forEach
            sizeTo(dialog, activity, entry.heightFraction)
            entry.onResize?.invoke(activity)
        }
    }

    private fun register(
        dialog: AlertDialog,
        heightFraction: Float?,
        onResize: ((Activity) -> Unit)?,
    ) {
        showing.removeAll { it.dialog.get() == null || it.dialog.get() == dialog }
        showing.add(Entry(WeakReference(dialog), heightFraction, onResize))
    }

    private fun sizeTo(
        dialog: Dialog,
        activity: Activity,
        heightFraction: Float?,
    ) {
        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        val height =
            if (heightFraction == null) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                (metrics.heightPixels * heightFraction).toInt()
            }
        dialog.window?.setLayout(width, height)
    }
}
