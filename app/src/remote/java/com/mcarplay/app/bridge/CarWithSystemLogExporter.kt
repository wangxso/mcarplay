package com.mcarplay.app.bridge

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.mcarplay.app.carlife.CarLifeLauncher
import com.mcarplay.app.diagnostics.DiagnosticRedactor
import com.mcarplay.app.diagnostics.PendingReportTransaction
import com.mcarplay.app.shizuku.ShizukuGate
import moe.shizuku.server.IShizukuService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed read-only diagnostics. Never append system output to DebugFileLog (which the bridge uploads). */
internal object CarWithSystemLogExporter {
    private val busy = AtomicBoolean()
    private const val READ_LOGS = "android.permission.READ_LOGS"
    private const val DUMP = "android.permission.DUMP"

    data class Result(
        val ok: Boolean,
        val detail: String,
        val uri: Uri? = null,
    )

    fun adbInstructions(packageName: String): String =
        "adb shell pm grant $packageName $READ_LOGS\n" +
            "adb shell pm grant $packageName $DUMP"

    fun export(context: Context): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "System diagnostics require a worker thread" }
        if (!busy.compareAndSet(false, true)) return Result(false, "正在导出系统日志，请稍候")
        try {
            val shizuku = ShizukuGate.state(context) == ShizukuGate.State.READY
            val readLogs = context.checkSelfPermission(READ_LOGS) == PackageManager.PERMISSION_GRANTED
            val dump = context.checkSelfPermission(DUMP) == PackageManager.PERMISSION_GRANTED
            if (!shizuku && !readLogs) {
                return Result(false, "需要启动并授权 Shizuku，或用电脑 ADB 执行以下授权后重试：\n${adbInstructions(context.packageName)}")
            }
            if (!shizuku && !CarLifeLauncher.isAppInForeground()) {
                return Result(false, "ADB 授权方式需要先打开远程调试页，并在系统询问读取日志时手动允许")
            }
            val body =
                buildString {
                    appendLine("mcarplay CarWith system diagnostics schema=1")
                    appendLine("generatedAt=${Instant.now()} route=${if (shizuku) "SHIZUKU" else "ADB_GRANTED"}")
                    appendLine("privacyMode=REDACTED; recent logcat buffers, not a full recording; no automatic upload.")
                    appendLine("Review before sharing. Missing records do not prove an activity/service never ran.")
                    appendLine("Android/ROM may restrict logs or request confirmation even after READ_LOGS is granted.")
                    for ((label, command) in commands(context.packageName)) {
                        appendLine("\n=== $label ===")
                        if (!shizuku && label.startsWith("services") && !dump) {
                            appendLine("UNAVAILABLE: DUMP permission not granted")
                            continue
                        }
                        try {
                            val result = execute(command, shizuku)
                            appendLine("exit=${result.exitCode} timedOut=${result.timedOut} truncated=${result.truncated}")
                            appendLine(result.output)
                        } catch (error: Exception) {
                            appendLine("UNAVAILABLE: ${error.javaClass.simpleName}")
                        }
                    }
                }
            val bytes = DiagnosticRedactor().redact(body).toByteArray(Charsets.UTF_8)
            val name = "mcarplay-carwith-system-${UUID.randomUUID().toString().take(8)}.txt"
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/mcarplay")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri =
                PendingReportTransaction.write(
                    create = { resolver.insert(collection, values) },
                    write = { item -> checkNotNull(resolver.openOutputStream(item)).use { it.write(bytes) } },
                    publish = { item ->
                        resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0
                    },
                    delete = { item ->
                        resolver.delete(item, null, null)
                        Unit
                    },
                )
            return Result(true, "已保存到 下载/mcarplay/$name（${bytes.size} 字节）。请检查各节 exit/timedOut；空日志不代表启动成功。", uri)
        } catch (error: Exception) {
            return Result(false, "系统日志导出失败：${error.javaClass.simpleName}")
        } finally {
            busy.set(false)
        }
    }

    internal fun commands(packageName: String): List<Pair<String, List<String>>> {
        require(packageName.matches(Regex("[a-zA-Z0-9_.]+")))
        val tags =
            listOf(
                "CarlifeAdbActivity",
                "PermissionActivity",
                "SystemAppPDA",
                "ConnectTypeUtil",
                "CarlinkService",
                "CarlinkServiceManager",
                "MarketManager",
                "CarlifeConnectService",
                "CarLifeConnectService",
                "CarlifeConnService",
                "ActivityTaskManager",
                "ActivityManager",
                "AndroidRuntime",
                "AutoStartManagerService",
                "SecurityManagerService",
                "am_anr",
                "am_crash",
                "am_proc_start",
                "am_proc_died",
                "wm_create_activity",
                "wm_resume_activity",
                "wm_finish_activity",
            )
        return listOf(
            "logcat" to
                (
                    listOf(
                        "logcat",
                        "-d",
                        "-v",
                        "threadtime",
                        "-b",
                        "main",
                        "-b",
                        "system",
                        "-b",
                        "events",
                        "-b",
                        "crash",
                        "-t",
                        "2000",
                        "-s",
                    ) +
                        tags.map { "$it:V" }
                ),
            "services.carwith" to listOf("dumpsys", "activity", "services", "com.miui.carlink"),
            "services.carlife" to listOf("dumpsys", "activity", "services", "com.baidu.carlife.xiaomi"),
            "listeners" to listOf("ss", "-lnt"),
            "backgroundStart" to listOf("appops", "get", packageName, "10021"),
            "featureSwitch" to listOf("settings", "get", "global", "ex_func_carWith"),
        )
    }

    private fun execute(
        command: List<String>,
        shizuku: Boolean,
    ): BoundedDiagnosticCommand.Result {
        if (shizuku) {
            val binder = checkNotNull(rikka.shizuku.Shizuku.getBinder())
            val process = IShizukuService.Stub.asInterface(binder).newProcess(command.toTypedArray(), null, null)
            return BoundedDiagnosticCommand.read(
                ParcelFileDescriptor.AutoCloseInputStream(process.inputStream),
                ParcelFileDescriptor.AutoCloseInputStream(process.errorStream),
                { process.waitForTimeout(it, TimeUnit.MILLISECONDS.toString()) },
                { process.exitValue() },
                { process.destroy() },
            )
        }
        val process = ProcessBuilder(command).start()
        return BoundedDiagnosticCommand.read(
            process.inputStream,
            process.errorStream,
            { process.waitFor(it, TimeUnit.MILLISECONDS) },
            { process.exitValue() },
            { process.destroy() },
            timeoutMs = if (command.first() == "logcat") 30_000 else 5_000,
        )
    }
}
