package com.mcarplay.app.launcher

import android.content.Context
import com.mcarplay.app.launcher.settings.AppOrientationCompat
import com.mcarplay.app.render.OverlayCard
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.util.Bg

/**
 * 「强制横屏未生效」提示的编排：事件驱动探测 → 定位黑边 → 生成卡片 → 一键重启。
 *
 * 为什么会需要它（2026-10-18 荣耀真机复现）：应用先运行、连接车机后才写入
 * `am compat` 强制横屏时，系统虽然杀掉了进程，但保留下来的任务会按**旧配置**恢复
 * ActivityRecord（`displayChangedWithoutRestart=true`），于是 `overrideOrientation`
 * 仍是 PORTRAIT、`isLetterboxRunning=true`、`letterboxReason=FIXED_ORIENTATION`。
 * 只有 force-stop 后重新启动才会按新的方向覆盖重建。
 *
 * 探测只在事件点触发（连接建立、应用启动、地图全屏、重启之后），不做轮询；
 * 判定与几何计算都是纯函数（见 LetterboxHint.kt），可单测。
 */
object LetterboxHintController {
    private const val TAG = "LetterboxHint"

    /** 用户点过「关闭」/确认修不好：本次会话不再提示。 */
    private val dismissed = HashSet<String>()

    private enum class State { NORMAL, BUSY, FAILED }

    @Volatile
    private var state = State.NORMAL

    /** 当前提示对应的目标（null = 未显示）。 */
    private data class Target(
        val packageName: String,
        val component: String,
        val displayId: Int,
    )

    @Volatile
    private var target: Target? = null

    /** 卡片矩形（输出画面坐标，顶部原点）；触摸命中直接用它。 */
    @Volatile
    private var cardLeft = 0f

    @Volatile
    private var cardTop = 0f

    @Volatile
    private var cardRight = 0f

    @Volatile
    private var cardBottom = 0f

    @Volatile
    private var probeInFlight = false

    fun isVisible(): Boolean = target != null

    /** 卡片是否命中（输出画面坐标；由 TouchRouter 在注入前调用）。 */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = target != null && x >= cardLeft && x < cardRight && y >= cardTop && y < cardBottom

    /** 卡片被点按：NORMAL=重启应用；BUSY=忽略；FAILED=关闭。 */
    fun onTap() {
        when (state) {
            State.NORMAL -> {
                val t = target ?: return
                restart(t)
            }

            State.BUSY -> Unit

            State.FAILED -> dismiss()
        }
    }

    fun dismiss() {
        target?.let { synchronized(dismissed) { dismissed.add(it.packageName) } }
        clear()
    }

    /** 回桌面 / 会话结束：清掉提示并恢复状态。 */
    fun clear() {
        target = null
        state = State.NORMAL
        cardLeft = 0f
        cardTop = 0f
        cardRight = 0f
        cardBottom = 0f
        VirtualLauncher.setOverlayCard(null)
    }

    /** 新会话：清空「本次会话不再提示」记录。 */
    fun resetSession() {
        synchronized(dismissed) { dismissed.clear() }
        clear()
    }

    /**
     * 事件点探测（连接建立 / 应用启动 / 地图全屏后调用）。放后台执行：
     * 一次 `dumpsys activity activities` 约 20ms，但仍不应占用 UI 线程。
     *
     * [displayIdHint]：调用方**确知**的目标屏（launchApp/地图启动传入）。不传时按
     * 合成器当前活动屏推断——但切屏要等目标屏出首帧（最长 2s），探测早于切换完成
     * 会看错屏、读出空结果（头条这类首帧慢的应用曾因此漏提示，2026-10-18）。
     */
    fun scheduleProbe(
        context: Context,
        reason: String,
        displayIdHint: Int = -1,
        delayMs: Long = 1_200L,
    ) {
        val app = context.applicationContext
        Bg.run("letterbox-probe") {
            if (delayMs > 0) Thread.sleep(delayMs)
            runCatching { probe(app, reason, displayIdHint) }
                .onFailure { android.util.Log.w(TAG, "probe failed after $reason", it) }
        }
    }

    private fun probe(
        context: Context,
        reason: String,
        displayIdHint: Int,
    ) {
        if (probeInFlight) return
        probeInFlight = true
        try {
            val displayId =
                displayIdHint
                    .takeIf { it >= 0 && VirtualLauncher.hintDisplaySize(it) != null }
                    ?: visibleDisplayId() ?: run {
                    if (isVisible()) clear()
                    return
                }
            val (fallbackW, fallbackH) =
                VirtualLauncher.hintDisplaySize(displayId) ?: run {
                    if (isVisible()) clear()
                    return
                }
            // 方向覆盖是异步串行写入的：探测前等队列排空，否则可能读到写入前的状态
            AppOrientationCompat.awaitPending()
            val enabled = AppOrientationCompat.enabledPackages(context)
            if (enabled.isEmpty()) {
                if (isVisible()) clear()
                return
            }
            val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
            val activity =
                parseTopLetterboxActivity(displayId, dump, fallbackW, fallbackH) ?: run {
                    if (isVisible()) clear()
                    return
                }
            val should =
                shouldShowLetterboxHint(activity, enabled, synchronized(dismissed) { dismissed.toSet() })
            if (!should) {
                if (isVisible()) {
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 清除提示（$reason）——" +
                            "letterbox=${activity.letterboxed} reason=${activity.letterboxReason}",
                    )
                    clear()
                }
                return
            }
            // 系统侧覆盖必须真的生效，否则「强制重启」注定无效（误导性提示）。
            // 偏好开着而系统侧缺失是**可自愈**的（会话写入失败/被外部清掉）：
            // 按用户意愿补写覆盖。`am compat` 会当场杀掉目标进程，所以补写后必须
            // 主动把它拉回原屏——否则用户看到的是「点开应用又被打回桌面、毫无提示」
            // （2026-10-18 实测头条：只剩这条路径会静默失败）。
            val changeIds = AppOrientationCompat.changeIdsRegistered()
            var systemOn =
                changeIds && AppOrientationCompat.isSystemEnabledFor(activity.packageName)
            var current = activity
            var healedAndRelaunched = false
            if (!systemOn && changeIds) {
                val healed =
                    AppOrientationCompat.setEnabled(context, activity.packageName, true) ==
                        AppOrientationCompat.WriteResult.OK
                systemOn = healed || AppOrientationCompat.isSystemEnabledFor(activity.packageName)
                VirtualLauncher.logSink?.invoke(
                    "HINT: 「${activity.packageName}」系统侧横屏覆盖缺失——补写" +
                        "${if (healed) "成功" else "失败"}",
                )
                if (systemOn) {
                    // 补写已杀掉进程：等它退出后在原屏重启（与按钮动作等价，但自动完成）
                    Thread.sleep(600)
                    val start =
                        Shell.exec(
                            "am start -n ${activity.component} --display $displayId -f 0x18000000",
                            timeoutMs = 8_000,
                        )
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 补写后重启 「${activity.packageName}」ok=" +
                            (start.ok && start.output.contains("Starting:", ignoreCase = true)),
                    )
                    healedAndRelaunched = true
                    Thread.sleep(2_200)
                    val fresh = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                    current =
                        parseTopLetterboxActivity(displayId, fresh, fallbackW, fallbackH) ?: run {
                            if (isVisible()) clear()
                            return
                        }
                    if (!shouldShowLetterboxHint(
                            current,
                            enabled,
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                    ) {
                        if (isVisible()) clear()
                        VirtualLauncher.logSink?.invoke(
                            "HINT: 补写覆盖并重启后「${activity.packageName}」已横屏",
                        )
                        return
                    }
                }
            }
            if (!systemOn) {
                VirtualLauncher.logSink?.invoke(
                    "HINT: 「${activity.packageName}」letterbox，但本机不支持横屏覆盖——" +
                        "不提示重启（changeIds=$changeIds）",
                )
                if (isVisible()) clear()
                return
            }
            // 黑边太窄（放不下卡片）时 showCard 返回 false 且不挂卡片；日志只记真实展示
            val wasVisible = isVisible()
            // 补写并重启后仍竖屏 → 说明这个应用压根不吃方向覆盖，直接给「仍然竖屏」
            // 终态卡（不再诱导用户再点一次必然失败的重启）
            state = if (healedAndRelaunched) State.FAILED else State.NORMAL
            // 自愈后复探过的话以最新读数为准（组件可能已换成重建后的实例）
            target = Target(current.packageName, current.component, displayId)
            val shown = showCard(current, state)
            if (!shown) {
                target = null
                return
            }
            if (!wasVisible) {
                VirtualLauncher.logSink?.invoke(
                    "HINT: 「${current.packageName}」横屏未生效（$reason）" +
                        " 内窗=${current.inner.width}x${current.inner.height}" +
                        " 外框=${current.full.width}x${current.full.height}" +
                        " 黑边 左=${current.inner.left - current.full.left}" +
                        "/右=${current.full.right - current.inner.right}",
                )
            }
        } finally {
            probeInFlight = false
        }
    }

    /**
     * 当前应用区实际显示的屏：地图全屏时是地图屏，否则是活动应用屏。
     * 桌面（GridActivity）不在「应用横屏」名单里，探测会自然跳过。
     */
    private fun visibleDisplayId(): Int? {
        if (VirtualLauncher.mapFullscreen && VirtualLauncher.mapDisplayId >= 0) {
            return VirtualLauncher.mapDisplayId
        }
        val id = VirtualLauncher.activeAppDisplayId()
        return if (id >= 0) id else null
    }

    /**
     * 依据 letterbox 矩形生成并挂上卡片。
     *
     * 坐标系：dumpsys 的 bounds 是**应用屏自身像素**；合成器把整块屏拉伸/缩放到
     * 应用区，故按 应用区宽 / 屏宽 换算成输出画面坐标（两轴比例相等，取横向）。
     * 卡片只在黑边够宽时显示——宽度由文字实测决定，没有硬编码阈值。
     */
    private fun showCard(
        activity: LetterboxActivity,
        newState: State,
    ): Boolean {
        val area = VirtualLauncher.appAreaRect() ?: return false
        val display =
            VirtualLauncher.hintDisplaySize(activity.displayId) ?: return false
        if (display.first <= 0 || area.width() <= 0f) return false
        val scale = area.width() / display.first
        val leftBar = (activity.inner.left - activity.full.left) * scale
        val rightBar = (activity.full.right - activity.inner.right) * scale
        val density = VirtualLauncher.uiDensity()
        val margin = LetterboxCardPainter.marginPx(density)
        val needed = LetterboxCardPainter.minWidthPx(density) + 2f * margin
        // 黑边太窄（与全屏横屏已无区别）→ 不显示
        val side = chooseLetterboxSide(leftBar, rightBar, needed) ?: return false
        val barWidth = if (side == LetterboxSide.LEFT) leftBar else rightBar
        val width = minOf(LetterboxCardPainter.maxWidthPx(density), barWidth - 2f * margin).toInt()
        val bitmap =
            LetterboxCardPainter.render(
                density = density,
                widthPx = width,
                maxHeightPx = area.height().toInt(),
                state = newState.toCardState(),
            ) ?: return false
        state = newState
        val barStart = if (side == LetterboxSide.LEFT) area.left else area.right - barWidth
        val left = barStart + (barWidth - bitmap.width) / 2f
        val top = area.top + (area.height() - bitmap.height) / 2f
        cardLeft = left
        cardTop = top
        cardRight = left + bitmap.width
        cardBottom = top + bitmap.height
        VirtualLauncher.logSink?.invoke(
            "HINT: 卡片 bitmap=${bitmap.width}x${bitmap.height} rect=" +
                "(${cardLeft.toInt()},${cardTop.toInt()}-${cardRight.toInt()},${cardBottom.toInt()})" +
                " bar=$barWidth density=$density widthPx=$width",
        )
        VirtualLauncher.setOverlayCard(
            OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom),
        )
        return true
    }

    private fun State.toCardState(): LetterboxCardState =
        when (this) {
            State.NORMAL -> LetterboxCardState.NORMAL
            State.BUSY -> LetterboxCardState.BUSY
            State.FAILED -> LetterboxCardState.FAILED
        }

    /**
     * 强制重启目标应用：force-stop → 在**原屏**重新启动 → 复探。
     * 后台线程执行；动作前先等方向覆盖队列排空（否则重启后仍读到旧覆盖）。
     */
    private fun restart(t: Target) {
        state = State.BUSY
        reRenderBusy()
        VirtualLauncher.logSink?.invoke("HINT: 强制重启 ${t.packageName}（display=${t.displayId}）")
        Bg.run("letterbox-restart") {
            runCatching {
                AppOrientationCompat.awaitPending()
                // am compat 的写入确实会杀进程，但任务按旧配置恢复；这里显式 stop 让
                // 下次启动走全新路径（实测：重启后 overrideOrientation=NOSENSOR、
                // areBoundsLetterboxed=false）。
                Shell.exec("am force-stop ${t.packageName}", timeoutMs = 8_000)
                Thread.sleep(400)
                val start =
                    Shell.exec(
                        "am start -n ${t.component} --display ${t.displayId} -f 0x18000000",
                        timeoutMs = 8_000,
                    )
                if (!start.ok) {
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 重启 ${t.packageName} 失败 exit=${start.exitCode} out=${start.output.take(120)}",
                    )
                }
                // 等应用重建窗口后再复探
                Thread.sleep(2_000)
                val (w, h) = VirtualLauncher.hintDisplaySize(t.displayId) ?: return@runCatching
                val ctx = VirtualLauncher.appContext() ?: return@runCatching
                val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                val activity = parseTopLetterboxActivity(t.displayId, dump, w, h)
                val still =
                    activity != null &&
                        activity.packageName == t.packageName &&
                        shouldShowLetterboxHint(
                            activity,
                            AppOrientationCompat.enabledPackages(ctx),
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                if (still) {
                    state = State.FAILED
                    if (!showCard(activity!!, State.FAILED)) {
                        // 黑边放不下卡片（或屏已释放）：直接收掉，避免重启无反馈
                        clear()
                    }
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 重启后「${t.packageName}」仍然 letterbox（reason=${activity.letterboxReason}）",
                    )
                } else {
                    clear()
                    VirtualLauncher.logSink?.invoke("HINT: 重启后「${t.packageName}」已横屏")
                }
            }.onFailure {
                state = State.NORMAL
                VirtualLauncher.logSink?.invoke("HINT: 重启异常 ${it.message}")
            }
        }
    }

    /** BUSY 态重画：沿用当前卡片的宽度/位置，避免重新解析。 */
    private fun reRenderBusy() {
        val density = VirtualLauncher.uiDensity()
        val width = (cardRight - cardLeft).toInt()
        val maxHeight = (VirtualLauncher.appAreaRect()?.height() ?: 0f).toInt()
        val bitmap = LetterboxCardPainter.render(density, width, maxHeight, LetterboxCardState.BUSY) ?: return
        // 上下以原矩形中心对齐（高度可能变化）
        val centerY = (cardTop + cardBottom) / 2f
        cardTop = centerY - bitmap.height / 2f
        cardBottom = cardTop + bitmap.height
        cardRight = cardLeft + bitmap.width
        VirtualLauncher.setOverlayCard(OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom))
    }

    /** 诊断用：当前提示的包名（未显示时 null）。 */
    fun currentPackage(): String? = target?.packageName

    /** 是否有探测在跑（测试/诊断）。 */
    fun isProbing(): Boolean = probeInFlight

    /** 上次卡片的屏幕坐标快照（诊断/测试用）。 */
    fun cardRect(): FloatArray = floatArrayOf(cardLeft, cardTop, cardRight, cardBottom)
}
