package com.mcarplay.app.carlife

import android.content.Context
import com.mcarplay.app.SessionController
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import com.mcarplay.app.diagnostics.ConnectionStage
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.service.CarPlayService

/** Standalone CarWith connectivity check, independent of the CarPlay transport. */
object CarLifeDiagnostics {
    private var session: CarLifeSession? = null
    private var diagnosticAttempt: String? = null

    @Synchronized
    fun start(context: Context): Boolean {
        if (session?.isRunning == true) return true
        if (SessionController.isFormalCarLifeActive()) {
            log("CARLIFE: 正式 CarPlay 会话正在占用 CarWith，自检未启动")
            return false
        }
        val appContext = context.applicationContext as android.app.Application
        var created: CarLifeSession? = null
        var attempt: String? = null
        var loggedFormat: Pair<Int, Int>? = null
        created =
            CarLifeSession(
                application = appContext,
                onAccessUnit = { frame ->
                    attempt?.let { ConnectionDiagnostics.timeline.sourceFrame(it) }
                    frame.dimensions()?.let { (width, height) ->
                        if (loggedFormat != width to height) {
                            loggedFormat = width to height
                            log("CARLIFE: 自检收到编码配置 ${width}x$height")
                        }
                    }
                },
                onEnded = { reason ->
                    attempt?.let { ConnectionDiagnostics.timeline.finish(it, DiagnosticCode.CARWITH_FAILED, true, reason) }
                    log("CARLIFE: 自检结束 - $reason")
                    created?.let {
                        SessionController.releaseCarLifeSession(it)
                        synchronized(this) {
                            if (session === it) session = null
                        }
                    }
                    CarPlayService.stopDiagnostic(appContext)
                },
                log = ::log,
                launchStrategy = CarLifeLaunchStrategy.EXPORTED_ENTRY,
            )
        val candidate = created ?: return false
        if (!SessionController.claimCarLifeSession(CarLifeSessionRegistry.Owner.DIAGNOSTIC, candidate)) {
            log("CARLIFE: 正式 CarPlay 会话正在占用 CarWith，自检未启动")
            return false
        }
        session = candidate
        attempt = ConnectionDiagnostics.begin("carwith_self_test")
        diagnosticAttempt = attempt
        ConnectionDiagnostics.timeline.stage(requireNotNull(attempt), ConnectionStage.SELF_TEST)
        return try {
            candidate.start()
        } catch (error: Exception) {
            log("CARLIFE: 自检启动异常 - ${error.message ?: error.javaClass.simpleName}")
            false
        }.also { started ->
            if (!started) release(candidate)
        }
    }

    @Synchronized
    fun stop() {
        diagnosticAttempt?.let { ConnectionDiagnostics.timeline.finish(it, DiagnosticCode.USER_STOP, false) }
        session?.let { active ->
            active.stop()
            release(active)
        }
    }

    @Synchronized
    fun status(): String {
        val active = session ?: return "未运行"
        val phase =
            when (active.state) {
                CarLifeStateMachine.State.CONNECTED -> "等待 CarWith 七口监听"
                CarLifeStateMachine.State.VIDEO_RUNNING ->
                    if (active.accessUnitCount > 0) "收到真实视频帧" else "视频已初始化，等待首帧"
                CarLifeStateMachine.State.FAILED -> "握手失败"
                else -> "七口已连接，协议握手中"
            }
        return "$phase ${active.videoInfo()} ${active.videoStats}"
    }

    @Synchronized
    fun isRunning(): Boolean = session?.isRunning == true

    private fun release(candidate: CarLifeSession) {
        synchronized(this) {
            if (!SessionController.releaseCarLifeSession(candidate)) {
                log("CARLIFE: 自检租约已由正式会话释放")
            }
            if (session === candidate) session = null
        }
    }

    private fun log(message: String) {
        SessionController.appendLog(message)
    }
}
