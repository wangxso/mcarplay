package com.mcarplay.app.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.audio.AudioDuckingPrefs
import com.mcarplay.app.bt.AutoConnectPrefs
import com.mcarplay.app.compat.CompatibilityPrefs
import com.mcarplay.app.compat.PassatReturnStrategy

/**
 * 「显示」面板：流分辨率缩放（30%–160%，下次连接起效）+ 目标帧率（30/60，
 * 运行中实时切换）。英雄卡展示的是会话运行时生效值（SessionController 快照），
 * 与本页偏好值相区别。
 */
class DisplayPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_display, container, false)

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val selectedText = ctx.getColor(R.color.ui_on_accent)
        val idleText = ctx.getColor(R.color.ui_text_secondary)
        val scaleSeek = view.findViewById<SeekBar>(R.id.displayScaleSeek)
        val scaleValue = view.findViewById<TextView>(R.id.displayScaleValue)
        val scaleNote = view.findViewById<TextView>(R.id.displayScaleNote)
        val fps30 = view.findViewById<TextView>(R.id.btnFps30)
        val fps60 = view.findViewById<TextView>(R.id.btnFps60)

        // 连接时真息屏（物理面板断电）：默认开；会话中切换立即生效
        val screenOffOn = view.findViewById<TextView>(R.id.btnScreenOffOn)
        val screenOffOff = view.findViewById<TextView>(R.id.btnScreenOffOff)
        var screenOffEnabled = DisplayPrefs.screenOffOnConnect(ctx)

        fun refreshScreenOff(enabled: Boolean) {
            screenOffOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            screenOffOn.setTextColor(if (enabled) selectedText else idleText)
            screenOffOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            screenOffOff.setTextColor(if (enabled) idleText else selectedText)
        }

        refreshScreenOff(screenOffEnabled)

        fun applyScreenOff(enabled: Boolean) {
            if (enabled == screenOffEnabled) return
            screenOffEnabled = enabled
            DisplayPrefs.setScreenOffOnConnect(ctx, enabled)
            refreshScreenOff(enabled)
            // 车机屏上就能操作：立即收放，不必等下次连接
            SessionController.applyScreenOffPreference(enabled)
            hostToast(getString(if (enabled) R.string.sf_screen_off_on else R.string.sf_screen_off_off))
        }
        screenOffOn.setOnClickListener { applyScreenOff(true) }
        screenOffOff.setOnClickListener { applyScreenOff(false) }

        // 上车自动连接（车机蓝牙 ACL 连上时自动拉起会话）：默认开
        val autoConnectOn = view.findViewById<TextView>(R.id.btnAutoConnectOn)
        val autoConnectOff = view.findViewById<TextView>(R.id.btnAutoConnectOff)
        var autoConnectEnabled = AutoConnectPrefs.enabled(ctx)

        fun refreshAutoConnect(enabled: Boolean) {
            autoConnectOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            autoConnectOn.setTextColor(if (enabled) selectedText else idleText)
            autoConnectOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            autoConnectOff.setTextColor(if (enabled) idleText else selectedText)
        }

        refreshAutoConnect(autoConnectEnabled)

        fun applyAutoConnect(enabled: Boolean) {
            if (enabled == autoConnectEnabled) return
            autoConnectEnabled = enabled
            AutoConnectPrefs.setEnabled(ctx, enabled)
            refreshAutoConnect(enabled)
            hostToast(getString(if (enabled) R.string.sf_auto_connect_on else R.string.sf_auto_connect_off))
        }
        autoConnectOn.setOnClickListener { applyAutoConnect(true) }
        autoConnectOff.setOnClickListener { applyAutoConnect(false) }

        // HID 原始日志（键位校准）：默认关；打开后每帧 hidSendReport 写一行 HID-RAW
        val hidRawOn = view.findViewById<TextView>(R.id.btnHidRawOn)
        val hidRawOff = view.findViewById<TextView>(R.id.btnHidRawOff)
        var hidRawEnabled = DisplayPrefs.hidRawLog(ctx)

        fun refreshHidRaw(enabled: Boolean) {
            hidRawOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            hidRawOn.setTextColor(if (enabled) selectedText else idleText)
            hidRawOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            hidRawOff.setTextColor(if (enabled) idleText else selectedText)
        }

        refreshHidRaw(hidRawEnabled)

        fun applyHidRaw(enabled: Boolean) {
            if (enabled == hidRawEnabled) return
            hidRawEnabled = enabled
            DisplayPrefs.setHidRawLog(ctx, enabled)
            refreshHidRaw(enabled)
            hostToast(getString(if (enabled) R.string.sf_hid_on else R.string.sf_hid_off))
        }
        hidRawOn.setOnClickListener { applyHidRaw(true) }
        hidRawOff.setOnClickListener { applyHidRaw(false) }

        // 兼容兜底开关（默认关）：恢复统一兼容策略之前的宽松行为，下次连接起效
        bindToggle(
            view.findViewById(R.id.btnPlainFallbackOn),
            view.findViewById(R.id.btnPlainFallbackOff),
            CompatibilityPrefs.plainFallback(ctx),
        ) { enabled ->
            CompatibilityPrefs.setPlainFallback(ctx, enabled)
            hostToast(getString(if (enabled) R.string.sf_plain_on else R.string.sf_plain_off))
        }
        bindToggle(
            view.findViewById(R.id.btnAudioBaselineOn),
            view.findViewById(R.id.btnAudioBaselineOff),
            CompatibilityPrefs.audioBaseline(ctx),
        ) { enabled ->
            CompatibilityPrefs.setAudioBaseline(ctx, enabled)
            hostToast(getString(if (enabled) R.string.sf_audio_fallback_on else R.string.sf_audio_fallback_off))
        }

        // 帕萨特「回主界面再进」实验策略：四选一，默认关；仅主页「车型适配」选了大众时才执行
        val passatButtons =
            listOf(
                PassatReturnStrategy.OFF to view.findViewById<TextView>(R.id.btnPassat0),
                PassatReturnStrategy.REBUILD_RETRY to view.findViewById<TextView>(R.id.btnPassat1),
                PassatReturnStrategy.DISCONNECT_AFTER_FAILURES to view.findViewById<TextView>(R.id.btnPassat2),
                PassatReturnStrategy.NO_REBUILD to view.findViewById<TextView>(R.id.btnPassat3),
            )

        fun refreshPassat(selected: PassatReturnStrategy) {
            passatButtons.forEach { (strategy, button) ->
                val on = strategy == selected
                button.setBackgroundResource(if (on) R.drawable.bg_segment_sel else R.drawable.bg_segment)
                button.setTextColor(if (on) selectedText else idleText)
            }
        }

        var passatCurrent = CompatibilityPrefs.passatReturnStrategy(ctx)
        refreshPassat(passatCurrent)
        passatButtons.forEach { (strategy, button) ->
            button.setOnClickListener {
                if (strategy == passatCurrent) return@setOnClickListener
                passatCurrent = strategy
                CompatibilityPrefs.setPassatReturnStrategy(ctx, strategy)
                refreshPassat(strategy)
                hostToast(
                    getString(
                        when (strategy) {
                            PassatReturnStrategy.OFF -> R.string.sf_passat_off
                            PassatReturnStrategy.REBUILD_RETRY -> R.string.sf_passat_retry
                            PassatReturnStrategy.DISCONNECT_AFTER_FAILURES -> R.string.sf_passat_disconnect
                            PassatReturnStrategy.NO_REBUILD -> R.string.sf_passat_norebuild
                        },
                    ),
                )
            }
        }

        // 导航时音乐音量（ducking 幅度）：滑条 0..30 → 0..-30dB；会话中调整立即生效
        val duckSeek = view.findViewById<SeekBar>(R.id.duckLevelSeek)
        val duckValue = view.findViewById<TextView>(R.id.duckLevelValue)

        fun duckLabel(db: Int) = if (db == 0) getString(R.string.lay_duck_level_off) else getString(R.string.lay_duck_level_value, db)

        val savedDuck = AudioDuckingPrefs.duckDb(ctx)
        duckSeek.progress = AudioDuckingPrefs.progressOfDb(savedDuck)
        duckValue.text = duckLabel(savedDuck)
        duckSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    duckValue.text = duckLabel(AudioDuckingPrefs.dbOfProgress(progress))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val db = AudioDuckingPrefs.dbOfProgress(duckSeek.progress)
                    AudioDuckingPrefs.setDuckDb(ctx, db)
                    SessionController.applyDuckDb(db)
                    hostToast(if (db == 0) getString(R.string.sf_duck_off) else getString(R.string.sf_duck_saved, db))
                }
            },
        )

        // 滑条 progress（0..26）→ 百分比（30..160，步进 5）
        fun progressOf(percent: Int) = (percent - DisplayPrefs.SCALE_MIN) / DisplayPrefs.SCALE_STEP

        fun percentOf(progress: Int) = DisplayPrefs.SCALE_MIN + progress * DisplayPrefs.SCALE_STEP

        val saved = DisplayPrefs.scalePercent(ctx)
        scaleSeek.progress = progressOf(saved)
        scaleValue.text = "$saved%"

        fun refreshScaleNote() {
            val runningNow =
                SessionController.videoSender
                    ?.isRunning == true
            scaleNote.text =
                if (runningNow) {
                    getString(R.string.sf_scale_running, SessionController.activeScalePercent)
                } else {
                    getString(R.string.lay_next_connection)
                }
        }
        refreshScaleNote()

        scaleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scaleValue.text = "${percentOf(progress)}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = percentOf(scaleSeek.progress)
                    DisplayPrefs.setScalePercent(ctx, percent)
                    hostToast(getString(R.string.sf_scale_saved, percent))
                    refreshScaleNote()
                }
            },
        )

        fun refreshFps(active30: Boolean) {
            // 未选中片用暗色底（与容器同色）而非 null 背景，避免背景重置内边距
            fps30.setBackgroundResource(
                if (active30) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            fps30.setTextColor(
                if (active30) selectedText else idleText,
            )
            fps60.setBackgroundResource(
                if (active30) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            fps60.setTextColor(
                if (active30) idleText else selectedText,
            )
        }

        val savedFps = DisplayPrefs.fps(ctx)
        refreshFps(savedFps < 60)

        fun applyFps(fps: Int) {
            DisplayPrefs.setFps(ctx, fps)
            refreshFps(fps < 60)
            val live =
                SessionController.videoSender
                    ?.isRunning == true
            hostToast(getString(if (live) R.string.sf_fps_live else R.string.sf_fps_set, fps))
            if (live) SessionController.activeTargetFps = fps
        }
        fps30.setOnClickListener { applyFps(30) }
        fps60.setOnClickListener { applyFps(60) }
    }

    /** 「开/关」分段开关：与上方几组相同的样式与去重逻辑。 */
    private fun bindToggle(
        on: TextView,
        off: TextView,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val selectedText = requireContext().getColor(R.color.ui_on_accent)
        val idleText = requireContext().getColor(R.color.ui_text_secondary)
        var current = initial

        fun refresh(enabled: Boolean) {
            on.setBackgroundResource(if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment)
            on.setTextColor(if (enabled) selectedText else idleText)
            off.setBackgroundResource(if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel)
            off.setTextColor(if (enabled) idleText else selectedText)
        }
        refresh(current)

        fun apply(enabled: Boolean) {
            if (enabled == current) return
            current = enabled
            refresh(enabled)
            onChange(enabled)
        }
        on.setOnClickListener { apply(true) }
        off.setOnClickListener { apply(false) }
    }
}
