package com.mcarplay.app.diagnostics

import android.content.Context
import com.mcarplay.app.R

/**
 * 诊断状态的界面文案（随界面语言）。[ConnectionTimeline.Snapshot.summary] 与
 * [DiagnosticCode.advice] 保持中文原样：它们写进诊断报告，开发者按固定文本对照。
 */
object DiagnosticText {
    fun advice(
        context: Context,
        code: DiagnosticCode,
    ): String =
        context.getString(
            when (code) {
                DiagnosticCode.BT_PERMISSION -> R.string.dc_bt_permission
                DiagnosticCode.BT_DISABLED -> R.string.dc_bt_disabled
                DiagnosticCode.BT_LINK_CLOSED -> R.string.dc_bt_link_closed
                DiagnosticCode.ENDPOINT_PENDING -> R.string.dc_endpoint_pending
                DiagnosticCode.LOCATION_REQUIRED -> R.string.dc_location_required
                DiagnosticCode.WIFI_LOST -> R.string.dc_wifi_lost
                DiagnosticCode.WIFI_PERMISSION -> R.string.dc_wifi_permission
                DiagnosticCode.WIFI_JOIN_FAILED -> R.string.dc_wifi_join_failed
                DiagnosticCode.PAIRING_FAILED -> R.string.dc_pairing_failed
                DiagnosticCode.SETUP_FAILED -> R.string.dc_setup_failed
                DiagnosticCode.CARWITH_FAILED -> R.string.dc_carwith_failed
                DiagnosticCode.CARWITH_ENTRY_BLOCKED -> R.string.dc_carwith_entry_blocked
                DiagnosticCode.CARWITH_BUSY -> R.string.dc_carwith_busy
                DiagnosticCode.VIDEO_CLOSED -> R.string.dc_video_closed
                DiagnosticCode.AUDIO_UNAVAILABLE -> R.string.dc_audio_unavailable
                DiagnosticCode.CONNECTION_INCOMPLETE -> R.string.dc_connection_incomplete
                DiagnosticCode.DETECTION_TIMEOUT -> R.string.dc_detection_timeout
                DiagnosticCode.UNCAUGHT_EXCEPTION -> R.string.dc_uncaught_exception
                DiagnosticCode.USER_STOP -> R.string.dc_user_stop
                DiagnosticCode.SUPERSEDED -> R.string.dc_superseded
            },
        )

    fun stage(
        context: Context,
        stage: ConnectionStage,
    ): String =
        context.getString(
            when (stage) {
                ConnectionStage.PRECHECK -> R.string.stage_precheck
                ConnectionStage.BLUETOOTH -> R.string.stage_bluetooth
                ConnectionStage.WIFI -> R.string.stage_wifi
                ConnectionStage.DISCOVERY -> R.string.stage_discovery
                ConnectionStage.PAIRING -> R.string.stage_pairing
                ConnectionStage.SETUP -> R.string.stage_setup
                ConnectionStage.CARWITH -> R.string.stage_carwith
                ConnectionStage.STREAMING -> R.string.stage_streaming
                ConnectionStage.SELF_TEST -> R.string.stage_self_test
            },
        )

    /** 与 [ConnectionTimeline.Snapshot.summary] 同一套判断，文案按界面语言。 */
    fun summary(
        context: Context,
        snapshot: ConnectionTimeline.Snapshot,
    ): String {
        val status =
            when (snapshot.status) {
                "waiting" -> context.getString(R.string.status_waiting)
                "streaming" -> context.getString(R.string.status_streaming)
                "receiving_source" -> context.getString(R.string.status_receiving_source)
                "failed" -> context.getString(R.string.status_failed)
                "stopped" -> context.getString(R.string.status_stopped)
                else -> snapshot.status
            }
        val detail =
            snapshot.code?.let { "${it.name}: ${advice(context, it)}" } ?: when {
                snapshot.status == "streaming" && (snapshot.lastVideoMsAgo ?: 0) >= 5000 ->
                    context.getString(R.string.summary_video_stalled, ((snapshot.lastVideoMsAgo ?: 0) / 1000).toInt())
                snapshot.sourceFrames > 0 && snapshot.videoFrames == 0L && snapshot.stage != ConnectionStage.SELF_TEST ->
                    context.getString(R.string.summary_source_only)
                snapshot.stage == ConnectionStage.CARWITH && snapshot.elapsedMs >= 15_000 ->
                    context.getString(R.string.summary_waiting_carwith)
                else -> context.getString(R.string.summary_default)
            }
        return context.getString(
            R.string.summary_header,
            snapshot.id,
            stage(context, snapshot.stage),
            status,
            (snapshot.elapsedMs / 1000).toInt(),
        ) + "\n" + detail
    }
}
