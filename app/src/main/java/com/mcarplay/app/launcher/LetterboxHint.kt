package com.mcarplay.app.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 「强制横屏未生效」提示：探测 + 几何计算 + 提示卡绘制。
 *
 * 场景（2026-10-18 真机复现）：应用先运行，连接车机后 am compat 写入强制横屏。
 * `am compat` 确实杀了进程，但系统恢复任务时 ActivityRecord 仍按旧的竖屏配置
 * 重建（`displayChangedWithoutRestart=true`），于是应用继续以 letterbox 竖窗运行。
 * 此时唯一的修复手段是 force-stop 后重新启动——提示卡让用户一键完成。
 *
 * 探测数据全部来自 `dumpsys activity activities`（无默认值、无估算）：
 *   `bounds=Rect(...)`        letterbox 内窗（应用实际窗口）四边
 *   `mMaxBounds=Rect(...)`    letterbox 外框（虚拟屏可用区域）
 * 两者之差就是左右（竖屏车机为上下）黑边。系统同时给出 `isLetterboxRunning`
 * 与 `letterboxReason`，据此只对「方向锁导致的 letterbox」提示，
 * 不打扰用户本来就想竖窗显示的应用、也不误报宽高比类 letterbox。
 */

/** 简单整数矩形（避免 android.graphics.Rect 在 JVM 单测里是空实现）。 */
internal data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** `dumpsys activity activities` 里某块屏顶层 Activity 的相关事实。 */
internal data class LetterboxActivity(
    val packageName: String,
    /** `包/类` 形式，可直接 `am start -n`。 */
    val component: String,
    val displayId: Int,
    val letterboxed: Boolean,
    val letterboxReason: String?,
    /** 应用窗口矩形（`bounds=`）。 */
    val inner: PixelBounds,
    /** 该屏可用区域矩形（`mMaxBounds=`；缺失时用调用方给的屏尺寸）。 */
    val full: PixelBounds,
)

private val displayRegex = Regex("""^Display #(\d+) \(activities""")
private val topActivityRegex =
    Regex("""topResumedActivity=ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val historyRegex =
    Regex("""^\s*\* Hist\s+#\d+: ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val boundsRegex = Regex("""^\s+bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")
private val maxBoundsRegex = Regex("""mMaxBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

/**
 * 解析指定虚拟屏顶层 Activity 的 letterbox 事实。
 *
 * @param displayId 目标屏
 * @param dump `dumpsys activity activities` 原始输出
 * @param fallbackW/[fallbackH] `mMaxBounds` 缺失时的屏尺寸兜底（调用方持有的事实，
 *        不是常量默认值——仍能算出左右黑边）
 */
internal fun parseTopLetterboxActivity(
    displayId: Int,
    dump: String,
    fallbackW: Int,
    fallbackH: Int,
): LetterboxActivity? {
    if (dump.isBlank()) return null
    val lines = dump.lineSequence().toList()
    var sectionStart = -1
    var sectionEnd = lines.size
    for (index in lines.indices) {
        val match = displayRegex.find(lines[index]) ?: continue
        if (sectionStart < 0) {
            if (match.groupValues[1].toInt() == displayId) sectionStart = index
        } else {
            sectionEnd = index
            break
        }
    }
    if (sectionStart < 0) return null
    // 只取目标 display 段，避免把别的屏的 Hist 块带进来
    val section = lines.subList(sectionStart + 1, sectionEnd)
    // 顶层任务：段内第一个 topResumedActivity（任务按从顶到底输出）
    var topHash: String? = null
    var topPkg: String? = null
    var topCls: String? = null
    for (line in section) {
        val match = topActivityRegex.find(line) ?: continue
        topHash = match.groupValues[1]
        topPkg = match.groupValues[2]
        topCls = match.groupValues[3]
        break
    }
    // 定位顶层 ActivityRecord 的 Hist 块：优先 hash 完全匹配，
    // 兼容 ROM 省略 topResumedActivity 时取第一块 Hist
    var block = -1
    var header: MatchResult? = null
    for (index in section.indices) {
        val match = historyRegex.find(section[index]) ?: continue
        if (topHash == null || match.groupValues[1] == topHash) {
            block = index
            header = match
            break
        }
    }
    if (block < 0 && topHash == null) {
        for (index in section.indices) {
            val match = historyRegex.find(section[index]) ?: continue
            block = index
            header = match
            break
        }
    }
    if (block < 0) return null
    val pkg = header!!.groupValues[2].ifBlank { topPkg ?: return null }
    val cls = header.groupValues[3].ifBlank { topCls ?: return null }
    if (pkg.isBlank() || cls.isBlank()) return null
    var letterboxed = false
    var reason: String? = null
    var inner: PixelBounds? = null
    var full: PixelBounds? = null
    for (index in block + 1 until section.size) {
        val line = section[index]
        if (historyRegex.containsMatchIn(line) || line.trimStart().startsWith("* Task")) break
        if (line.trimStart().startsWith("Resumed activities")) break
        if (line.contains("isLetterboxRunning=true")) letterboxed = true
        if (reason == null) {
            Regex("""letterboxReason=(\w+)""").find(line)?.let { reason = it.groupValues[1] }
        }
        if (inner == null) {
            boundsRegex.find(line)?.let { match ->
                inner =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
        if (full == null) {
            maxBoundsRegex.find(line)?.let { match ->
                full =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
    }
    val outer = full ?: PixelBounds(0, 0, fallbackW, fallbackH)
    val window = inner ?: outer
    if (outer.width <= 0 || outer.height <= 0) return null
    return LetterboxActivity(
        packageName = pkg,
        component = "$pkg/$cls",
        displayId = displayId,
        letterboxed = letterboxed,
        letterboxReason = reason,
        inner = window,
        full = outer,
    )
}

/**
 * 是否值得弹「强制横屏未生效」提示。
 *
 * 四条同时成立：顶层应用在「应用横屏」名单里、确实处于 letterbox、
 * 原因是 FIXED_ORIENTATION（方向锁）、且用户本次会话没关掉过该提示。
 */
internal fun shouldShowLetterboxHint(
    activity: LetterboxActivity,
    forceLandscapePackages: Set<String>,
    dismissedPackages: Set<String>,
): Boolean =
    activity.letterboxed &&
        activity.letterboxReason == "FIXED_ORIENTATION" &&
        activity.packageName in forceLandscapePackages &&
        activity.packageName !in dismissedPackages

internal enum class LetterboxSide { LEFT, RIGHT }

/**
 * 选提示卡放哪一侧：优先左侧（靠近驾驶位），左侧放不下且右侧更宽时退到右侧。
 * [neededPx] = 卡片最小宽度 + 两侧外边距（由文字实测得出，非固定阈值）。
 * 两侧都不够 → null（黑边太窄，与全屏横屏已无区别，提示只会挤成一团）。
 */
internal fun chooseLetterboxSide(
    leftPx: Float,
    rightPx: Float,
    neededPx: Float,
): LetterboxSide? =
    when {
        leftPx >= neededPx && leftPx >= rightPx -> LetterboxSide.LEFT
        rightPx >= neededPx -> LetterboxSide.RIGHT
        leftPx >= neededPx -> LetterboxSide.LEFT
        else -> null
    }

internal enum class LetterboxCardState { NORMAL, BUSY, FAILED }

/**
 * 提示卡的手势裁决（纯函数，TouchRouter 的唯一决策点）。
 *
 * 卡片画在黑边上，绝不注入给应用；按下命中后整段手势由卡片独占：
 *  - 抬起点仍在卡片内 → 算一次点按；
 *  - 移出卡片再抬起 → 取消（避免误触）；
 *  - 按住期间不把 move 漏给应用（否则会拖动下面的列表）。
 */
internal enum class CardGesture { NOT_HANDLED, CONSUME, TAP }

internal fun resolveCardGesture(
    cardVisible: Boolean,
    holding: Boolean,
    down: Boolean,
    hit: Boolean,
    appGestureActive: Boolean = false,
): CardGesture =
    when {
        // 卡片已收起：无论之前是否按住，都交回普通路由
        !cardVisible -> if (holding) CardGesture.CONSUME else CardGesture.NOT_HANDLED
        // 按住中：按下期间的 move 一律吞掉；抬起时按是否仍在卡片内决定点按
        holding && down -> CardGesture.CONSUME
        holding -> if (hit) CardGesture.TAP else CardGesture.CONSUME
        // 未按住：只有「按下瞬间就在卡片上」才接管。应用手势进行中拖进卡片区域
        // 不接管——否则应用收不到抬起，会停在按下态（卡死）。
        down && hit && !appGestureActive -> CardGesture.CONSUME
        else -> CardGesture.NOT_HANDLED
    }

/**
 * 提示卡绘制（离屏 Bitmap → 合成器当纹理贴到黑边上）。
 *
 * 尺寸全部按画布密度换算；**最小宽度由文字实测**（按钮文字 + 内边距），
 * 没有「黑边小于 N px 不显示」这类硬编码阈值——窄到放不下文案自然就不显示。
 */
internal object LetterboxCardPainter {
    private const val MAX_WIDTH_DP = 200f
    private const val MARGIN_DP = 6f
    private const val PAD_H_DP = 8f
    private const val PAD_V_DP = 10f
    private const val GAP_DP = 7f

    /** 按钮高度随字号同步收敛（32dp + 9.5sp 会显得按钮里空荡）。 */
    private const val BUTTON_H_DP = 28f
    private const val RADIUS_DP = 10f
    private const val TITLE_SP = 11.5f
    private const val BODY_SP = 9.5f

    /** 按钮字号与正文同档：11.5sp 在真机上比标题还抢眼（用户实测反馈，2026-10-18）。 */
    private const val BUTTON_SP = 9.5f
    private const val BUTTON_PAD_H_DP = 8f

    fun marginPx(density: Float): Float = MARGIN_DP * density

    /** 卡片允许的最大宽度（黑边再宽也不铺满，避免在大黑边里变成一块巨幅广告牌）。 */
    fun maxWidthPx(density: Float): Float = MAX_WIDTH_DP * density

    /** 卡片最小宽度：由按钮文案与标题实测宽度决定。 */
    fun minWidthPx(density: Float): Float {
        val button = textPaint(BUTTON_SP * density, Color.WHITE, bold = true)
        val buttonW = button.measureText("强制重启") + 2f * BUTTON_PAD_H_DP * density
        val title = textPaint(TITLE_SP * density, Color.WHITE, bold = true)
        // 标题至少容纳 5 个汉字（再窄会碎成单字换行）
        val titleW = title.measureText("横屏未生效")
        return 2f * PAD_H_DP * density + maxOf(buttonW, titleW)
    }

    /**
     * 生成提示卡位图；[widthPx] 由黑边宽度决定。
     * 宽度放不下文案或高度放不下按钮时返回 null（调用方据此不显示）。
     */
    fun render(
        density: Float,
        widthPx: Int,
        maxHeightPx: Int,
        state: LetterboxCardState,
    ): Bitmap? {
        if (widthPx <= 0 || maxHeightPx <= 0) return null
        val width = widthPx.toFloat()
        val textWidth = width - 2f * PAD_H_DP * density
        if (textWidth <= 0f) return null
        if (width < minWidthPx(density)) return null
        val (titleText, bodyText, buttonText) =
            when (state) {
                LetterboxCardState.NORMAL -> Triple("横屏未生效", "仍是竖屏窗口", "强制重启")
                LetterboxCardState.BUSY -> Triple("正在重启", "将以横屏打开", "请稍候")
                LetterboxCardState.FAILED -> Triple("仍然竖屏", "可能不支持", "关闭")
            }
        val titlePaint = textPaint(TITLE_SP * density, TITLE_COLOR, bold = true)
        val bodyPaint = textPaint(BODY_SP * density, BODY_COLOR, bold = false)
        val title = staticLayout(titleText, titlePaint, textWidth.toInt())
        val body = staticLayout(bodyText, bodyPaint, textWidth.toInt())
        val height =
            (
                2f * PAD_V_DP * density + title.height + GAP_DP * density +
                    body.height + GAP_DP * density + BUTTON_H_DP * density
            ).toInt()
        if (height > maxHeightPx) return null
        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = RADIUS_DP * density
        val bounds = RectF(0f, 0f, widthPx.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, fillPaint(BG_COLOR))
        canvas.drawRoundRect(bounds, radius, radius, strokePaint(BORDER_COLOR, density))
        var y = PAD_V_DP * density
        canvas.save()
        canvas.translate(PAD_H_DP * density, y)
        title.draw(canvas)
        canvas.restore()
        y += title.height + GAP_DP * density
        canvas.save()
        canvas.translate(PAD_H_DP * density, y)
        body.draw(canvas)
        canvas.restore()
        y += body.height + GAP_DP * density
        val buttonW = width - 2f * PAD_H_DP * density
        val buttonH = BUTTON_H_DP * density
        val buttonRect =
            RectF(PAD_H_DP * density, y, PAD_H_DP * density + buttonW, y + buttonH)
        val enabled = state == LetterboxCardState.NORMAL
        canvas.drawRoundRect(
            buttonRect,
            buttonH / 2f,
            buttonH / 2f,
            fillPaint(if (enabled) ACCENT_COLOR else DISABLED_FILL),
        )
        val buttonPaint =
            textPaint(
                BUTTON_SP * density,
                if (enabled) BUTTON_TEXT_COLOR else DISABLED_TEXT,
                bold = true,
            ).apply {
                // drawText 默认左对齐：不设 CENTER 时整串从 buttonRect.centerX() 起笔、
                // 向右溢出按钮（真机截图暴露，2026-10-18）
                textAlign = Paint.Align.CENTER
            }
        val metrics = buttonPaint.fontMetrics
        val textY =
            buttonRect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(buttonText, buttonRect.centerX(), textY, buttonPaint)
        return bitmap
    }

    private fun staticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
    ): StaticLayout =
        StaticLayout
            .Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()

    private fun textPaint(
        sizePx: Float,
        color: Int,
        bold: Boolean,
    ): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

    private fun strokePaint(
        color: Int,
        density: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            this.color = color
        }

    // 深色卡 + 琥珀强调色，与 dock 的深色风格一致；黑边上对比足够
    private const val BG_COLOR = 0xF0171C22.toInt()
    private const val BORDER_COLOR = 0x33FFFFFF
    private const val ACCENT_COLOR = 0xFFFFC24B.toInt()
    private const val TITLE_COLOR = 0xFFFFC24B.toInt()
    private const val BODY_COLOR = 0xB3FFFFFF.toInt()
    private const val BUTTON_TEXT_COLOR = 0xFF1B1405.toInt()
    private const val DISABLED_FILL = 0x33FFFFFF
    private const val DISABLED_TEXT = 0xB3FFFFFF.toInt()
}
