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
                DiagnosticCode.CARWITH_ENTRY_NOT_OBSERVED -> R.string.dc_carwith_entry_not_observed
                DiagnosticCode.CARWITH_ENTRY_STATUS_UNKNOWN -> R.string.dc_carwith_entry_status_unknown
                DiagnosticCode.CARWITH_LISTENERS_PENDING -> R.string.dc_carwith_listeners_pending
                DiagnosticCode.CARWITH_PROTOCOL_SILENT -> R.string.dc_carwith_protocol_silent
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

    /** 分层进度条用的短标签。 */
    fun step(
        context: Context,
        stage: ConnectionStage,
    ): String =
        when (stage) {
            ConnectionStage.BLUETOOTH -> context.getString(R.string.step_bluetooth)
            ConnectionStage.WIFI -> context.getString(R.string.step_wifi)
            ConnectionStage.DISCOVERY -> context.getString(R.string.step_discovery)
            ConnectionStage.PAIRING -> context.getString(R.string.step_pairing)
            ConnectionStage.SETUP -> context.getString(R.string.step_setup)
            ConnectionStage.CARWITH -> context.getString(R.string.step_carwith)
            ConnectionStage.STREAMING -> context.getString(R.string.step_streaming)
            else -> stage(context, stage)
        }

    fun milestone(
        context: Context,
        milestone: ConnectionMilestone,
    ): String =
        context.getString(
            when (milestone) {
                ConnectionMilestone.BT_LINK -> R.string.ms_bt_link
                ConnectionMilestone.IAP2_IDENTIFIED -> R.string.ms_iap2_identified
                ConnectionMilestone.IAP2_AUTHENTICATED -> R.string.ms_iap2_authenticated
                ConnectionMilestone.WIFI_CREDENTIALS -> R.string.ms_wifi_credentials
                ConnectionMilestone.WIFI_JOINED -> R.string.ms_wifi_joined
                ConnectionMilestone.CTRL_INVITED -> R.string.ms_ctrl_invited
                ConnectionMilestone.RECEIVER_RESOLVED -> R.string.ms_receiver_resolved
                ConnectionMilestone.AIRPLAY_CONNECTED -> R.string.ms_airplay_connected
                ConnectionMilestone.PAIR_SETUP -> R.string.ms_pair_setup
                ConnectionMilestone.PAIR_VERIFY -> R.string.ms_pair_verify
                ConnectionMilestone.AUTH_SETUP -> R.string.ms_auth_setup
                ConnectionMilestone.SESSION_SETUP -> R.string.ms_session_setup
                ConnectionMilestone.RECORD -> R.string.ms_record
                ConnectionMilestone.CARWITH_FRAME -> R.string.ms_carwith_frame
                ConnectionMilestone.FIRST_VIDEO -> R.string.ms_first_video
                ConnectionMilestone.FIRST_AUDIO -> R.string.ms_first_audio
            },
        )

    fun blocker(
        context: Context,
        blocker: ConnectionProgress.Blocker,
    ): String =
        context.getString(
            when (blocker) {
                ConnectionProgress.Blocker.BT_LINK -> R.string.blocker_bt_link
                ConnectionProgress.Blocker.IAP2_AUTH -> R.string.blocker_iap2_auth
                ConnectionProgress.Blocker.WIFI_INFO -> R.string.blocker_wifi_info
                ConnectionProgress.Blocker.WIFI_JOIN -> R.string.blocker_wifi_join
                ConnectionProgress.Blocker.RECEIVER_DISCOVERY -> R.string.blocker_receiver_discovery
                ConnectionProgress.Blocker.RECEIVER_ADDRESS -> R.string.blocker_receiver_address
                ConnectionProgress.Blocker.AIRPLAY_PORT -> R.string.blocker_airplay_port
                ConnectionProgress.Blocker.HOMEKIT_PAIRING -> R.string.blocker_homekit_pairing
                ConnectionProgress.Blocker.SESSION_SETUP -> R.string.blocker_session_setup
                ConnectionProgress.Blocker.RECORD -> R.string.blocker_record
                ConnectionProgress.Blocker.CARWITH_FRAME -> R.string.blocker_carwith_frame
            },
        )

    /** 分层进度条的各段文本（「配对 ●3.4s」），界面按 [ConnectionProgress.StepState] 着色。 */
    fun progressSegments(
        context: Context,
        progress: ConnectionProgress.Progress,
    ): List<Pair<String, ConnectionProgress.StepState>> =
        progress.steps.map { step ->
            val mark = ConnectionProgress.mark(step.state)
            val time = step.durationMs?.takeIf { step.state != ConnectionProgress.StepState.PENDING }
            val tail = if (mark.isEmpty()) "" else " " + mark + (time?.let(ConnectionProgress::formatDuration) ?: "")
            step(context, step.stage) + tail to step.state
        }

    /** 进度条下方：最近里程碑、当前层缺的那一步、车机连接窗口提示；没有可说的返回空串。 */
    fun progressDetail(
        context: Context,
        progress: ConnectionProgress.Progress,
    ): String {
        val latest =
            progress.latest?.let { (reached, atMs) ->
                context.getString(R.string.progress_latest, milestone(context, reached), ConnectionProgress.formatDuration(atMs)) +
                    (
                        progress.sinceBluetoothMs?.let {
                            " · " + context.getString(R.string.progress_since_bluetooth, (it / 1000).toInt())
                        } ?: ""
                    )
            }
        val failed = progress.steps.any { it.state == ConnectionProgress.StepState.FAILED }
        val blocked =
            progress.blocker?.let {
                context.getString(
                    if (failed) R.string.progress_blocker_failed else R.string.progress_blocker_active,
                    blocker(context, it),
                )
            }
        val window = context.getString(R.string.progress_head_unit_window).takeIf { progress.pastHeadUnitWindow }
        return listOfNotNull(latest, blocked, window).joinToString("\n")
    }

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
                snapshot.screenSuspended ->
                    context.getString(R.string.summary_video_paused)
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
