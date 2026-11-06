package com.mcarplay.app.ui

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.diagnostics.DiagnosticExportPolicy
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.LogExporter
import java.util.Locale

/**
 * 日志导出流程（从 MainActivity 拆出，行为不变）：确认（仅 remote/debug 明文导出）→ 后台写文件 →
 * 完成弹窗（打开文件 / 打开目录 / 分享）。只依赖 [Host] 的日志、提示条与需脱敏名称。
 */
class LogExportController(
    private val activity: AppCompatActivity,
    private val host: Host,
) {
    interface Host {
        fun log(message: String)

        fun snack(
            message: String,
            durationMs: Long = 2_400L,
        )

        /** 需要从日志里脱敏的名称（热点名、目标车机名等）。 */
        fun diagnosticNames(): List<String>
    }

    /**
     * 导出日志到「下载/mcarplay/」并弹窗告知位置。
     *
     * 默认导出诊断摘要与最近历史；进程重启后仍能反馈前一轮问题。
     * 长按导出按钮包含更大的最近运行日志（[LogExporter.Scope.ALL]）；仅 remote/debug 明文导出。
     * 为什么必须弹窗而不是 snack：snack 只在页面底部浮 2.4s，用户点了导出后
     * 往往正在看日志区、根本注意不到；且失败时也必须有明确反馈（写文件可能因
     * 存储满/媒体库异常失败）。
     */
    fun export(scope: LogExporter.Scope = LogExporter.Scope.SUPPORT) {
        if (DiagnosticExportPolicy.unredacted) {
            DarkMessageDialog.show(
                activity,
                title = activity.getString(R.string.raw_diagnostics_title),
                message = activity.getString(R.string.raw_diagnostics_notice),
                actions =
                    listOf(
                        DarkMessageDialog.Action(activity.getString(android.R.string.cancel), onClick = {}),
                        DarkMessageDialog.Action(
                            activity.getString(R.string.raw_diagnostics_export_confirm),
                            DarkMessageDialog.Emphasis.PRIMARY,
                        ) { performLogExport(scope) },
                    ),
            )
        } else {
            performLogExport(scope)
        }
    }

    private fun performLogExport(scope: LogExporter.Scope) {
        host.snack(activity.getString(R.string.ma_exporting))
        val fallback = SessionController.logText()
        val knownNames = host.diagnosticNames()
        Bg.run("export-log") {
            val result = LogExporter.export(activity.applicationContext, fallback, scope, knownNames)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result == null) {
                    host.snack(activity.getString(R.string.ma_export_failed), 4_000L)
                    return@runOnUiThread
                }
                host.log("日志已导出（${scope.label}）: ${result.displayPath}（${result.bytes} 字节）")
                val scopeNote =
                    activity.getString(
                        if (scope == LogExporter.Scope.SUPPORT) {
                            R.string.ma_scope_support
                        } else if (scope == LogExporter.Scope.CURRENT_SESSION) {
                            R.string.ma_scope_current
                        } else {
                            R.string.ma_scope_all
                        },
                    )
                DarkMessageDialog.show(
                    activity,
                    title = activity.getString(R.string.ma_export_done_title),
                    message =
                        activity.getString(
                            R.string.ma_export_done_body,
                            result.displayPath,
                            result.reportId.take(8),
                            formatBytes(result.bytes),
                            scopeNote,
                        ),
                    actions =
                        listOf(
                            DarkMessageDialog.Action(activity.getString(R.string.ma_open_file), DarkMessageDialog.Emphasis.PRIMARY) {
                                openExportedFile(result.uri, result.displayPath)
                            },
                            DarkMessageDialog.Action(activity.getString(R.string.ma_open_folder)) {
                                openExportFolder(result.displayPath)
                            },
                            DarkMessageDialog.Action(activity.getString(R.string.ma_share_file)) {
                                shareExportedFile(result.uri)
                            },
                            DarkMessageDialog.Action(activity.getString(R.string.action_close), onClick = {}),
                        ),
                )
            }
        }
    }

    /** 人类可读大小：<1KB 显示字节（整数除法会显示成误导性的「0 KB」）。 */
    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> activity.getString(R.string.ma_bytes, bytes.toInt())
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }

    /**
     * 打开系统文件管理器并定位到导出目录（DocumentsUI 的 document URI 可直达子目录）。
     *
     * 为什么优先**显式组件**：荣耀 ROM 的隐式 ACTION_VIEW + directory 会被 resolver
     * 乱匹配（2026-10-18 实测弹出了「网易云音乐」默认项）；同一条 intent 用
     * `am start` 却正确落到 DocumentsUI。显式指定 FilesActivity 可跳过 resolver，
     * 失败再逐级回退（别的 ROM 组件名可能不同 → 隐式 → 外部存储根 → 文字指引）。
     */
    private fun openExportFolder(displayPath: String) {
        val folder = displayPath.substringBeforeLast('/')
        val relative = folder.removePrefix("/storage/emulated/0/")
        val dirUri =
            Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3A" + Uri.encode(relative).replace("/", "%2F"),
            )
        val explicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                component =
                    android.content.ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val implicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/directory",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        for (intent in listOf(explicit, implicit, storageRoot)) {
            if (runCatching { activity.startActivity(intent) }.isSuccess) {
                if (intent !== explicit) host.snack(activity.getString(R.string.ma_open_by_path, folder), 5_000L)
                return
            }
        }
        host.snack(activity.getString(R.string.ma_open_downloads), 5_000L)
    }

    /** 用系统默认应用打开（多数 ROM 会落到文件管理器 / 文本查看器预览）。 */
    private fun openExportedFile(
        uri: Uri,
        displayPath: String,
    ) {
        val view =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { activity.startActivity(view) }.isSuccess) return
        // 无文本查看器时退到「文件管理器」：DocumentsUI 的内部存储根（用户按路径进 下载/mcarplay）
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { activity.startActivity(storageRoot) }.isSuccess) {
            host.snack(activity.getString(R.string.ma_open_by_path, displayPath), 5_000L)
            return
        }
        host.snack(activity.getString(R.string.ma_no_viewer), 5_000L)
    }

    /** 分享日志文件（微信/QQ/邮件等），内容 URI 带临时读权限。 */
    private fun shareExportedFile(uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching { activity.startActivity(Intent.createChooser(send, activity.getString(R.string.ma_share_chooser))) }
            .onFailure { host.snack(activity.getString(R.string.ma_no_share_app)) }
    }
}
