package com.mcarplay.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.mcarplay.app.diagnostics.PendingReportTransaction
import com.mcarplay.app.diagnostics.SupportReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 日志导出：把运行日志写成 `下载/mcarplay/mcarplay-log-<时间>.txt`，普通文件管理器可见。
 *
 * 为什么落 MediaStore.Downloads 而不是应用私有目录：
 *  - `filesDir` / `getExternalFilesDir` 普通用户在文件管理器里找不到（Android 11+
 *    系统文件管理器直接屏蔽 `Android/data`），日志导出等于没导；
 *  - MediaStore.Downloads 无需任何存储权限，落盘即出现在系统「下载」目录，
 *    微信/QQ/邮件发送附件也能直接选到。
 *
 * 内容优先取 `files/main_debug.log`：它记录全部 appendLog（不受 UI 日志开关影响，
 * 最多保留两段各约 2MB）；默认只取最近 512KB。文件不可读时退回界面缓冲并明确标记。
 */
object LogExporter {
    /** Default support scope includes bounded previous-process evidence, even after an app restart. */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        SUPPORT("脱敏诊断报告（含最近历史）", "-diagnostics"),
        CURRENT_SESSION("仅本次启动", ""),
        ALL("最近两段历史日志", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** 给用户看的绝对路径（文件管理器里按这个找）。 */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
        val reportId: String,
    )

    /** 导出目录（相对外部存储根）：下载/mcarplay。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/mcarplay"

    /** 文件管理器里展示的绝对路径前缀（主用户外部存储；分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** 导出并返回结果；失败返回 null（调用方提示用户）。任意线程可调（含 IO）。 */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.SUPPORT,
        knownNames: List<String> = emptyList(),
    ): Result? =
        try {
            val reportId = UUID.randomUUID().toString()
            val body =
                SupportReport.create(
                    context,
                    reportId,
                    scope.label,
                    scope == Scope.CURRENT_SESSION,
                    fallbackText,
                    maxLogBytes = if (scope == Scope.ALL) 4_000_000 else 512_000,
                    knownNames = knownNames,
                )
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val fileName = "mcarplay-log-$stamp-${reportId.take(8)}${scope.fileNameTag}.txt"
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                    // 先标 pending 再写：避免文件管理器/相册扫描到半截文件
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val uri =
                PendingReportTransaction.write(
                    create = { resolver.insert(collection, values) },
                    write = { item ->
                        resolver.openOutputStream(item)?.use { it.write(bytes) }
                            ?: throw IllegalStateException("openOutputStream 返回 null")
                    },
                    publish = { item ->
                        resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0
                    },
                    delete = { item ->
                        resolver.delete(item, null, null)
                        Unit
                    },
                )
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", bytes.size.toLong(), scope, reportId)
        } catch (error: Exception) {
            DebugFileLog.append(context, "DIAGNOSTIC_EXPORT_FAILED ${error.javaClass.simpleName}: ${error.message}")
            null
        }
}
