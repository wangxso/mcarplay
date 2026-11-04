package com.mcarplay.app.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import com.mcarplay.app.util.Bg

/**
 * 推流期间的锁屏/息屏观测：把屏幕与电源状态迁移写进连接日志与诊断时间线。
 *
 * 手动锁屏会结束会话（2026-10-31 艾瑞泽 8 实车），但当时日志只有「媒体退出」，
 * 看不出先断的是 CarWith 源、Wi‑Fi 还是 AirPlay 通道。这里**不改变任何系统状态**，只记录：
 *  - SCREEN_OFF / SCREEN_ON / USER_PRESENT / Doze 迁移的时刻；
 *  - Keyguard 是否锁定、是否安全锁、是否交互、是否处于 Doze；
 *  - 本应用是否在电池优化豁免名单（未豁免时 ROM 息屏后可能冻结进程或限网）；
 *  - 会话若在息屏期间结束，距离息屏多少毫秒——锁屏退出与无关断开由此区分。
 *
 * Wi‑Fi 低延迟锁（[StreamingWifiLock]）只在「前台 + 亮屏」时生效，息屏后系统恢复省电；
 * 时间线里的息屏时刻可与接收端拆链时刻直接对照。
 */
object ScreenLockMonitor {
    /** 一次状态采样（纯数据，便于单测文案）。 */
    data class PowerSnapshot(
        val interactive: Boolean,
        val keyguardLocked: Boolean,
        val keyguardSecure: Boolean,
        val deviceIdle: Boolean,
        val batteryExempt: Boolean,
    )

    private var receiver: BroadcastReceiver? = null

    /** 最近一次 SCREEN_OFF 的单调时刻；亮屏后清零。 */
    private var screenOffAtMs = 0L

    private var log: ((String) -> Unit)? = null
    private var note: ((String) -> Unit)? = null

    /**
     * 推流开始时调用：打印一次初始状态并订阅屏幕/Doze 广播。幂等。
     *
     * @param log 连接日志（appendLog）
     * @param note 诊断时间线 NOTE（绑定当前 attempt）
     */
    @Synchronized
    fun start(
        context: Context,
        log: (String) -> Unit,
        note: (String) -> Unit,
    ) {
        val app = context.applicationContext
        this.log = log
        this.note = note
        screenOffAtMs = if (snapshot(app).interactive) 0L else SystemClock.uptimeMillis()
        report(app, "STREAM_START")
        if (receiver != null) return
        val created =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    val event =
                        when (intent.action) {
                            Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                            Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                            Intent.ACTION_USER_PRESENT -> "USER_PRESENT"
                            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "IDLE_CHANGED"
                            else -> return
                        }
                    synchronized(this@ScreenLockMonitor) {
                        when (event) {
                            "SCREEN_OFF" -> screenOffAtMs = SystemClock.uptimeMillis()
                            "SCREEN_ON" -> screenOffAtMs = 0L
                        }
                    }
                    // 广播在主线程：日志会落盘，采样与写入放到后台
                    val app = ctx.applicationContext
                    Bg.run("power-state") { synchronized(this@ScreenLockMonitor) { report(app, event) } }
                }
            }
        receiver = created
        runCatching {
            app.registerReceiver(
                created,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                },
            )
        }.onFailure { receiver = null }
    }

    /** 推流结束时调用：息屏期间结束的会话额外记一行时间差。幂等。 */
    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        val offAt = screenOffAtMs
        if (log != null) {
            endedWhileScreenOff(offAt, SystemClock.uptimeMillis())?.let { line ->
                log?.invoke("POWER: $line")
                note?.invoke("STREAM_END_SCREEN_OFF ${line.substringAfter("：")}")
            }
        }
        screenOffAtMs = 0L
        log = null
        note = null
    }

    private fun report(
        context: Context,
        event: String,
    ) {
        val state = snapshot(context)
        log?.invoke("POWER: " + describe(event, state))
        note?.invoke(timelineNote(event, state))
    }

    private fun snapshot(context: Context): PowerSnapshot {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return PowerSnapshot(
            interactive = runCatching { power?.isInteractive }.getOrNull() ?: true,
            keyguardLocked = runCatching { keyguard?.isKeyguardLocked }.getOrNull() ?: false,
            keyguardSecure = runCatching { keyguard?.isDeviceSecure }.getOrNull() ?: false,
            deviceIdle = runCatching { power?.isDeviceIdleMode }.getOrNull() ?: false,
            batteryExempt = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() ?: false,
        )
    }

    /** 连接日志一行（中文，给人看）。 */
    internal fun describe(
        event: String,
        state: PowerSnapshot,
    ): String {
        val title =
            when (event) {
                "STREAM_START" -> "推流开始"
                "SCREEN_OFF" -> "手机息屏"
                "SCREEN_ON" -> "手机亮屏"
                "USER_PRESENT" -> "手机已解锁"
                "IDLE_CHANGED" -> "Doze 状态变化"
                else -> event
            }
        val hint =
            when {
                event == "SCREEN_OFF" && !state.batteryExempt -> "——未豁免电池优化，系统可能在息屏后冻结/限网"
                event == "SCREEN_OFF" -> "——Wi‑Fi 低延迟锁息屏后不再生效"
                event == "STREAM_START" && !state.batteryExempt -> "——建议把 mcarplay 设为「省电策略：无限制」"
                else -> ""
            }
        return "$title（交互=${yes(state.interactive)} 锁屏=${yes(state.keyguardLocked)} " +
            "安全锁=${yes(state.keyguardSecure)} Doze=${yes(state.deviceIdle)} " +
            "电池优化豁免=${yes(state.batteryExempt)}）$hint"
    }

    /** 诊断时间线 NOTE（英文键值，便于离线工具统计）。 */
    internal fun timelineNote(
        event: String,
        state: PowerSnapshot,
    ): String =
        "POWER $event interactive=${bit(state.interactive)} keyguard=${bit(state.keyguardLocked)} " +
            "secure=${bit(state.keyguardSecure)} idle=${bit(state.deviceIdle)} batteryExempt=${bit(state.batteryExempt)}"

    /** 息屏期间结束时返回说明行；亮屏中结束返回 null。 */
    internal fun endedWhileScreenOff(
        screenOffAtMs: Long,
        nowMs: Long,
    ): String? {
        if (screenOffAtMs <= 0L) return null
        return "推流在息屏期间结束：afterScreenOffMs=${(nowMs - screenOffAtMs).coerceAtLeast(0L)}"
    }

    private fun yes(value: Boolean) = if (value) "是" else "否"

    private fun bit(value: Boolean) = if (value) "1" else "0"
}
