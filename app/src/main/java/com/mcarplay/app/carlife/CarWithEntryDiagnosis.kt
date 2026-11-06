package com.mcarplay.app.carlife

import com.mcarplay.app.diagnostics.DiagnosticCode

/**
 * 把 CarWith 入口阶段能观察到的证据映射成有限、可行动的结论。
 * 通知探针只说明是否观察到 CarWith 通知，不能证明其内部初始化曾经执行。
 */
object CarWithEntryDiagnosis {
    data class Evidence(
        val listenersTimedOut: Boolean,
        val listenersConnectedOnce: Boolean,
        val commandFramesReceived: Long,
        val entryLaunchedInBackground: Boolean,
        val notification: CarWithNotificationSnapshot,
        val notificationBaseline: CarWithNotificationSnapshot?,
    ) {
        val notificationSeenDuringRun: Boolean =
            notification.activeNotifications > 0 ||
                notification.deltaSince(notificationBaseline).postEvents > 0L ||
                (notificationBaseline?.activeNotifications ?: 0) > 0
    }

    fun classify(evidence: Evidence): DiagnosticCode =
        when {
            evidence.listenersTimedOut && evidence.notificationSeenDuringRun ->
                DiagnosticCode.CARWITH_LISTENERS_PENDING

            evidence.listenersTimedOut && evidence.entryLaunchedInBackground ->
                DiagnosticCode.CARWITH_ENTRY_BLOCKED

            evidence.listenersTimedOut && evidence.notification.probeState == NotificationProbeState.CONNECTED ->
                DiagnosticCode.CARWITH_ENTRY_NOT_OBSERVED

            evidence.listenersTimedOut ->
                DiagnosticCode.CARWITH_ENTRY_STATUS_UNKNOWN

            evidence.listenersConnectedOnce && evidence.commandFramesReceived == 0L ->
                DiagnosticCode.CARWITH_PROTOCOL_SILENT

            else ->
                DiagnosticCode.CARWITH_FAILED
        }
}
