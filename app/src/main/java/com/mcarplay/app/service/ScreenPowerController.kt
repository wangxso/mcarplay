package com.mcarplay.app.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.mcarplay.app.shizuku.CarInputClient
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.shizuku.ShizukuGate
import com.mcarplay.app.ui.ScreenDimmer
import com.mcarplay.app.util.Bg

/**
 * 物理屏电源控制的窄依赖端口（供 [ScreenPowerController] 在 JVM 单测中替换全部系统依赖）。
 */
internal interface ScreenPowerPorts {
    fun hasContext(): Boolean

    fun isStreaming(): Boolean

    fun isInteractive(): Boolean

    fun isDimmerAttached(): Boolean

    fun enterDimNow()

    fun exitPanelOff()

    fun setPhysicalScreenPower(on: Boolean): Boolean

    fun shellExec(
        command: String,
        timeoutMs: Long,
    )

    fun isShizukuReady(): Boolean

    fun isInputServiceBound(): Boolean

    fun ensureInputService()

    fun runBackground(
        name: String,
        block: () -> Unit,
    )

    fun uptimeMillis(): Long

    fun sleep(ms: Long)

    fun log(message: String)
}

/**
 * 物理屏真息屏控制器（同 scrcpy `-S`/`--turn-screen-off`）。
 *
 * 接管物理主屏的启动兜底恢复、推流中断电进入、设置面板即时切换，以及三种收尾路径的恢复策略：
 *  - `retireSession()` / `disconnect()`：点亮物理屏（`sleepIfCarSide = false`，撤暗幕 + 恢复面板电）；
 *  - `VideoSender.onEnded`（车机侧断开）：保持面板黑并直接让手机休眠（`sleepIfCarSide = true`，发 `KEYCODE_SLEEP`）。
 *
 * [enterOff] 与 [restore] 同走私有 [lock] 串行化，防止后台 cached 线程池乱序导致「先恢复、后断电」。
 */
internal class ScreenPowerController(
    private val ports: ScreenPowerPorts,
) {
    /** 物理主屏是否被我们置为断电（只用于「要不要恢复」的判断，避免无谓的 AIDL 调用）。 */
    @Volatile
    private var phoneScreenOff = false

    /**
     * 电源切换串行化。enter/restore 都是 Bg 任务（cached 池不保证顺序），
     * 不加锁会出现「restore 先跑完、enter 后跑」→ 会话都结束了面板还被关掉。
     */
    private val lock = Any()

    internal val isScreenOff: Boolean
        get() = phoneScreenOff

    /**
     * 上次进程若在物理息屏期间崩溃/被杀，面板会停在 OFF（UserService 是常驻 shell 进程，
     * 它持有的电源状态不随应用进程消失）。新进程启动时恢复一次：此刻还没有会话，
     * 恢复是安全的——否则用户拿到的是一块按不亮的黑屏（可按电源键两次救回）。
     */
    fun restoreAtStartup() {
        ports.runBackground("screen-power-startup-restore") {
            if (!ports.isShizukuReady()) return@runBackground
            val deadline = ports.uptimeMillis() + STARTUP_RESTORE_WAIT_MS
            while (ports.uptimeMillis() < deadline && !ports.isInputServiceBound()) {
                ports.ensureInputService()
                ports.sleep(STARTUP_POLL_MS)
            }
            // 只恢复「已绑定服务」的情况：Shizuku 没起来时不必打扰用户（面板也不会是我们关的）
            if (ports.isInputServiceBound() && !ports.setPhysicalScreenPower(true)) {
                ports.log("SCREEN: 启动恢复物理屏失败（ROM 未授权 shell 电源控制）")
            }
        }
    }

    /**
     * 设置面板切「连接时真息屏」：会话中立即收放（没有会话时什么都不做）。
     *
     * 车机屏上就能操作，所以手机面板黑着也不影响切换；关掉会立刻把面板点亮。
     */
    fun applyPreference(enabled: Boolean) {
        if (enabled) {
            enterOff()
        } else {
            restore(sleepIfCarSide = false)
        }
    }

    /**
     * 会话进入「真息屏」：把**物理主屏**硬件断电，系统仍保持唤醒，虚拟屏照常出帧。
     *
     * 与暗幕的区别：暗幕只是一层黑 View，触摸层仍通电（口袋误触、耗电）；
     * 这里是 SurfaceFlinger 级断电（触摸 IC 同步熄），观感等同锁屏，但**不锁屏**——
     * 车机侧的高德/QQ 音乐不会被 Keyguard 挂起。
     *
     * 失败只记日志：ROM 没给 shell 权限或取不到 display token 时，最坏退回暗幕。
     */
    fun enterOff() {
        if (!ports.hasContext()) return
        // 没有在推流就不必关屏（设置面板无会话时切换也会走到这里）
        if (!ports.isStreaming()) return
        ports.runBackground("screen-power-off") {
            synchronized(lock) {
                if (phoneScreenOff) return@synchronized
                // Bg 任务可能晚于会话结束才被执行：会话没了就绝不能再关屏
                if (!ports.isStreaming()) {
                    ports.log("SCREEN: 会话已结束——跳过物理息屏")
                    return@synchronized
                }
                // 手机若正在休眠：先唤醒让框架把物理屏置 ON；否则我们设 OFF 之后，框架的
                // 唤醒流程又会把面板点亮（两者抢同一个 SurfaceFlinger 电源状态）。
                if (!ports.isInteractive()) {
                    ports.log("SCREEN: 手机处于休眠——先唤醒再物理息屏（避免框架把面板重新点亮）")
                    ports.shellExec("input keyevent KEYCODE_WAKEUP", SHELL_KEYEVENT_TIMEOUT_MS)
                    ports.sleep(WAKEUP_SETTLE_MS)
                    // 无密码 / SmartLock 场景顺手划开锁屏；安全锁会失败，忽略即可
                    ports.shellExec("wm dismiss-keyguard", SHELL_DISMISS_KEYGUARD_TIMEOUT_MS)
                }
                // 断电由暗幕承载（2026-10-24 用户实测定谳：面板断电后触摸 IC 仍工作，
                // 点击暗幕即唤醒；暗幕同时是口袋误触盾——没有它，兜里触摸会点到前台应用）。
                // enterDimNow = 上暗幕 + 断面板电 + 记账（panelOffByUs），与手动假息屏同路。
                if (ports.isDimmerAttached()) {
                    ports.enterDimNow()
                    phoneScreenOff = true
                    ports.log("SCREEN: 真息屏已进入（暗幕承载触摸检测——点暗幕唤醒，无需按电源键）")
                } else {
                    // 暗幕不可用（缺悬浮窗权限）降级裸断电：无触摸唤醒手段，只能按电源键
                    val ok = ports.setPhysicalScreenPower(false)
                    phoneScreenOff = ok
                    ports.log(
                        if (ok) {
                            "SCREEN: 物理屏已断电（暗幕不可用——无触摸唤醒手段，按电源键两次可恢复）"
                        } else {
                            "SCREEN: 物理屏断电失败（ROM 未给 shell 权限 / 取不到 display token）"
                        },
                    )
                }
            }
        }
    }

    /**
     * 恢复物理屏点亮。幂等：没关过就什么都不做。
     *
     * @param sleepIfCarSide 车机侧断开（人在车外、手机在口袋）为 true：直接让手机休眠，
     *   不先点亮面板（否则口袋里的手机会先亮一下再熄）；用户手动断开走 false。
     */
    fun restore(sleepIfCarSide: Boolean) {
        ports.runBackground("screen-power-restore") {
            synchronized(lock) {
                if (!phoneScreenOff) return@synchronized
                phoneScreenOff = false
                if (sleepIfCarSide) {
                    // 保持 SurfaceFlinger 的 OFF 状态，让框架正常休眠；下次唤醒时框架会把面板置 ON
                    ports.shellExec("input keyevent KEYCODE_SLEEP", SHELL_KEYEVENT_TIMEOUT_MS)
                    ports.log("SCREEN: 车机侧断开——已让手机休眠（面板保持黑）")
                } else {
                    // 暗幕承载的断电态在这里收口：撤暗幕 + 恢复面板电（幂等）
                    ports.exitPanelOff()
                    val ok = ports.setPhysicalScreenPower(true)
                    ports.log("SCREEN: 物理屏已恢复点亮 ok=$ok")
                }
            }
        }
    }

    companion object {
        internal const val STARTUP_RESTORE_WAIT_MS = 3_000L
        internal const val STARTUP_POLL_MS = 150L
        internal const val WAKEUP_SETTLE_MS = 400L
        internal const val SHELL_KEYEVENT_TIMEOUT_MS = 4_000L
        internal const val SHELL_DISMISS_KEYGUARD_TIMEOUT_MS = 3_000L

        /** 生产适配器：直接转发到 [PowerManager]、[ScreenDimmer]、[CarInputClient]、[ShizukuGate]、[Shell] 与 [Bg]。 */
        fun create(
            context: () -> Context?,
            isStreaming: () -> Boolean,
            log: (String) -> Unit,
        ): ScreenPowerController =
            ScreenPowerController(
                object : ScreenPowerPorts {
                    override fun hasContext(): Boolean = context() != null

                    override fun isStreaming(): Boolean = isStreaming()

                    override fun isInteractive(): Boolean {
                        val ctx = context() ?: return true
                        return runCatching {
                            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                        }.getOrDefault(true)
                    }

                    override fun isDimmerAttached(): Boolean = ScreenDimmer.isAttached()

                    override fun enterDimNow() = ScreenDimmer.enterDimNow()

                    override fun exitPanelOff() = ScreenDimmer.exitPanelOff()

                    override fun setPhysicalScreenPower(on: Boolean): Boolean = CarInputClient.setPhysicalScreenPower(on)

                    override fun shellExec(
                        command: String,
                        timeoutMs: Long,
                    ) {
                        Shell.exec(command, timeoutMs)
                    }

                    override fun isShizukuReady(): Boolean {
                        val ctx = context() ?: return false
                        return ShizukuGate.state(ctx) == ShizukuGate.State.READY
                    }

                    override fun isInputServiceBound(): Boolean = CarInputClient.service != null

                    override fun ensureInputService() {
                        context()?.let(CarInputClient::ensure)
                    }

                    override fun runBackground(
                        name: String,
                        block: () -> Unit,
                    ) = Bg.run(name, block)

                    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()

                    override fun sleep(ms: Long) = Thread.sleep(ms)

                    override fun log(message: String) = log(message)
                },
            )
    }
}
