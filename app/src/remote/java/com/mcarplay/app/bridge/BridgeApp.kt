package com.mcarplay.app.bridge

import android.os.Handler
import android.os.Looper
import com.mcarplay.app.SenderApp
import com.mcarplay.app.ui.ScreenDimmer
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DebugFileLog

/**
 * remote 变体的 Application（manifest 覆盖 android:name）。
 *
 * 在进程最早入口安装日志 tap 与调试桥；standard 变体不含本文件，一切无从谈起。
 */
class BridgeApp : SenderApp() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // 远程排障需要实时看手机屏幕：自动假息屏/暗幕（45s 无操作黑屏）在此变体
        // 一律禁用，只保留常亮；手动假息屏不受影响（2026-10-24 用户要求）。
        ScreenDimmer.autoDimAllowed = false
        DebugFileLog.append(this, "BRIDGE: 远程调试变体启动（${com.mcarplay.app.BuildConfig.VERSION_NAME}，自动暗幕已禁用）")
        // 断连对用户可见：悬浮提示 + 设置页状态行的自动重连倒计时（2026-10-22
        // 用户要求）。恢复时短提示收口，反复震荡由悬浮窗单例自然去重（新弹覆盖旧弹）。
        BridgeClient.connectionEvents = { connected ->
            main.post {
                if (connected) {
                    BridgeOverlay.show(this, "调试连接已恢复", dismissMillis = 3_000)
                } else {
                    BridgeOverlay.show(
                        this,
                        "调试连接已断开——正在自动重连（进度见调试页状态行）",
                        dismissMillis = 6_000,
                    )
                }
            }
        }
        // 先清掉卡死的旧安装会话（防堵后续安装），再决定连接/续接
        Bg.run("bridge-clean-sessions") { ReinstallFlow.abandonStaleSessions(this) }

        // 重装后的续接：上次安装前置位 → 拉起设置页弹「继续进入调试模式？」
        // （正规弹窗而非悬浮窗——悬浮窗易被忽略，用户实测反馈）
        if (BridgePrefs.resumePending(this)) {
            DebugFileLog.append(this, "BRIDGE: 检测到重装前标记——询问是否继续调试")
            BridgeSetupActivity.startForResume(this)
        } else if (BridgePrefs.autoConnect(this) && BridgePrefs.serverUrl(this).isNotBlank()) {
            Bg.run("bridge-start") { BridgeClient.start(this) }
        }
    }
}
