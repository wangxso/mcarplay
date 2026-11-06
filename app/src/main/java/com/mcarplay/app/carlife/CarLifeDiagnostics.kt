package com.mcarplay.app.carlife

import android.content.Context
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import com.mcarplay.app.diagnostics.ConnectionStage
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.diagnostics.DiagnosticText
import com.mcarplay.app.service.CarPlayService

/** Standalone CarWith connectivity check, independent of the CarPlay transport. */
object CarLifeDiagnostics {
    @Volatile
    private var session: CarLifeSession? = null
    private var diagnosticAttempt: String? = null

    @Volatile
    private var lastReport = "schema=1 state=NOT_STARTED"

    @Volatile
    private var lastCode: DiagnosticCode? = null

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
                    attempt?.let { ConnectionDiagnostics.selfTest.sourceFrame(it) }
                    frame.dimensions()?.let { (width, height) ->
                        if (loggedFormat != width to height) {
                            loggedFormat = width to height
                            log("CARLIFE: 自检收到编码配置 ${width}x$height")
                        }
                    }
                },
                onEnded = { reason ->
                    attempt?.let {
                        val code = created?.endDiagnosticCode ?: DiagnosticCode.CARWITH_FAILED
                        lastCode = code
                        ConnectionDiagnostics.selfTest.finish(it, code, true, reason)
                    }
                    log("CARLIFE: 自检结束 - $reason")
                    created?.let {
                        SessionController.releaseCarLifeSession(it)
                        synchronized(this) {
                            if (session === it) {
                                lastReport = it.diagnosticSummary()
                                session = null
                            }
                        }
                    }
                    CarPlayService.stopDiagnostic(appContext)
                },
                log = ::log,
                launchStrategy = CarLifeLaunchStrategy.COMPATIBLE,
            )
        val candidate = created ?: return false
        if (!SessionController.claimCarLifeSession(CarLifeSessionRegistry.Owner.DIAGNOSTIC, candidate)) {
            log("CARLIFE: 正式 CarPlay 会话正在占用 CarWith，自检未启动")
            return false
        }
        session = candidate
        lastCode = null
        lastReport = "schema=1 state=STARTING"
        attempt = ConnectionDiagnostics.selfTest.begin("carwith_self_test")
        diagnosticAttempt = attempt
        ConnectionDiagnostics.selfTest.stage(requireNotNull(attempt), ConnectionStage.SELF_TEST)
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
        diagnosticAttempt?.let { ConnectionDiagnostics.selfTest.finish(it, DiagnosticCode.USER_STOP, false) }
        session?.let { active ->
            active.stop()
            lastReport = active.diagnosticSummary()
            release(active)
        }
    }

    @Synchronized
    fun status(context: Context): String {
        val active =
            session ?: return lastCode?.let {
                context.getString(R.string.carlife_self_test_last_result, DiagnosticText.advice(context, it))
            } ?: context.getString(R.string.ma_hu_step_idle)
        val phase =
            context.getString(
                when (active.state) {
                    CarLifeStateMachine.State.CONNECTED -> R.string.cl_waiting_listeners
                    CarLifeStateMachine.State.VIDEO_RUNNING ->
                        if (active.accessUnitCount > 0) R.string.cl_real_frames else R.string.cl_waiting_first_frame
                    CarLifeStateMachine.State.FAILED -> R.string.cl_handshake_failed
                    else -> R.string.cl_handshaking
                },
            )
        return "$phase ${active.videoInfo()} ${active.videoStats}"
    }

    @Synchronized
    fun isRunning(): Boolean = session?.isRunning == true

    /** 不等待 start()/stop() 的活动启动或清理锁，remote 主线程只读轻量快照。 */
    fun reportSummary(): String = session?.diagnosticSummary() ?: lastReport

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
