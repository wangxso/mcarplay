package com.mcarplay.app

import com.mcarplay.app.diagnostics.DiagnosticCode
import java.util.concurrent.CountDownLatch

internal fun interface NamedTasks {
    fun submit(
        name: String,
        body: () -> Unit,
    )
}

internal interface RetiringClient {
    val sessionActive: Boolean

    fun stopLikeIphone(): Boolean

    fun teardownSession()

    fun close()
}

internal data class DisconnectSnapshot(
    val reason: DiagnosticCode,
    val client: RetiringClient?,
    val stopCapturedVideo: () -> Unit,
    val releaseCapturedWifi: () -> Unit,
    val completed: CountDownLatch,
)

internal interface DisconnectLive {
    fun log(message: String)

    fun detachBt(reason: String)

    fun stopBtServer()

    fun stopBtClient()

    fun finishBtDiagnostics()

    fun withWifiLock(body: () -> Unit)

    fun stopCtrlAdvertiser()

    fun stopEndpointBrowser()

    fun cleanupShellFallback()

    fun completeOuter(completed: CountDownLatch)
}

/**
 * [com.mcarplay.app.SessionController.disconnect] 原 `session-disconnect` 后台尾段。
 *
 * 只移动后台协议退出与 finally 清理；同步入口的门面锁、快照时点和完成记录仍由原调用方保留。
 */
internal class DisconnectTail(
    private val tasks: NamedTasks,
) {
    fun enqueue(
        snapshot: DisconnectSnapshot,
        live: DisconnectLive,
    ) {
        tasks.submit("session-disconnect") { execute(snapshot, live) }
    }

    private fun execute(
        snapshot: DisconnectSnapshot,
        live: DisconnectLive,
    ) {
        try {
            if (snapshot.reason == DiagnosticCode.USER_STOP && snapshot.client?.sessionActive == true) {
                live.log("SESSION: 按 iPhone 顺序断开（0x4E0D 不可用 → TEARDOWN 屏幕流 → TEARDOWN 会话）")
                if (!runCatching { snapshot.client.stopLikeIphone() }.getOrDefault(false)) {
                    live.log("SESSION: 车机未确认 TEARDOWN——先离开车机热点，由车机自行收尾")
                    live.withWifiLock { runCatching { snapshot.releaseCapturedWifi() } }
                }
            } else {
                snapshot.client?.teardownSession()
            }
        } finally {
            runCatching { snapshot.stopCapturedVideo() }
            runCatching { snapshot.client?.close() }
            // 连接到此结束：先发 0xEA01 收掉本轮 EA 会话，免得车机挡住下一次（奥迪 EAP 泄漏保护）。
            runCatching { live.detachBt("连接结束 ${snapshot.reason.name}") }
            runCatching { live.stopBtServer() }
            runCatching { live.stopBtClient() }
            runCatching { live.finishBtDiagnostics() }
            live.withWifiLock {
                runCatching { live.stopCtrlAdvertiser() }
                runCatching { live.stopEndpointBrowser() }
                runCatching { snapshot.releaseCapturedWifi() }
            }
            runCatching { live.cleanupShellFallback() }
            live.completeOuter(snapshot.completed)
        }
    }
}
