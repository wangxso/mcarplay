package com.mcarplay.app.diagnostics

import android.content.Context
import com.mcarplay.app.util.DebugFileLog
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ConnectionDiagnostics {
    val processId: String = UUID.randomUUID().toString().take(8)
    private var journal: RotatingTextFile? = null
    private var context: Context? = null
    private var writeFailures = 0L
    val timeline = ConnectionTimeline(sink = ::persist)

    @Synchronized
    fun initialize(app: Context) {
        if (context != null) return
        context = app.applicationContext
        journal = RotatingTextFile(File(app.filesDir, "connection-diagnostics.jsonl"), 128_000)
        persist(JSONObject().put("event", "PROCESS_START").put("timeMs", System.currentTimeMillis()).toString())
        DebugFileLog.append(app, "DIAGNOSTICS: schema=1 process=$processId timeMs=${System.currentTimeMillis()}")
    }

    fun begin(source: String): String = timeline.begin(source)

    fun receiverAttempt(): String {
        val current = timeline.snapshot()
        val attempt =
            if (current != null &&
                current.status == "waiting" &&
                current.stage in listOf(ConnectionStage.PRECHECK, ConnectionStage.BLUETOOTH)
            ) {
                current.id
            } else {
                begin("receiver_endpoint")
            }
        timeline.stage(attempt, ConnectionStage.WIFI)
        return attempt
    }

    fun precheck(code: DiagnosticCode) {
        val id = begin("precheck")
        timeline.finish(id, code, true)
    }

    fun finishCurrent(
        code: DiagnosticCode,
        failed: Boolean,
        detail: String = "",
    ) {
        timeline.snapshot()?.let { timeline.finish(it.id, code, failed, detail) }
    }

    fun issueCurrent(code: DiagnosticCode) {
        timeline.snapshot()?.let { timeline.issue(it.id, code) }
    }

    fun uncaught(
        thread: Thread,
        throwable: Throwable,
    ) {
        val safeStack =
            DiagnosticRedactor()
                .redact(throwable.stackTraceToString().take(32_000))
                .replace(Regex("<(?:IP|MAC|NAME)-[0-9]+>"), "<redacted-at-source>")
        timeline.snapshot()?.let { timeline.issue(it.id, DiagnosticCode.UNCAUGHT_EXCEPTION, throwable.javaClass.name) }
        persist(
            JSONObject()
                .put("event", "UNCAUGHT_EXCEPTION")
                .put("timeMs", System.currentTimeMillis())
                .put("thread", thread.name)
                .put("attempt", timeline.snapshot()?.id ?: "none")
                .put("stack", safeStack)
                .toString(),
        )
        context?.let { DebugFileLog.append(it, "UNCAUGHT_EXCEPTION thread=${thread.name}\n$safeStack") }
    }

    @Synchronized
    fun history(): String =
        runCatching { journal?.read(256_000) ?: "[diagnostic journal unavailable]" }
            .getOrDefault("[diagnostic journal unreadable]")

    @Synchronized
    fun storageStatus(): String = "diagnosticWriteFailures=$writeFailures"

    @Synchronized
    private fun persist(line: String) {
        try {
            val record = JSONObject(line).put("schema", 1).put("process", processId)
            val runtime = Runtime.getRuntime()
            record
                .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
                .put("threadCountApprox", Thread.activeCount())
            journal?.append(record.toString())
        } catch (_: Exception) {
            writeFailures++
        }
    }
}
