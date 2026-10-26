package com.mcarplay.app.diagnostics

import android.content.Context
import com.mcarplay.app.util.DebugFileLog
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ConnectionDiagnostics {
    val processId: String = UUID.randomUUID().toString().take(8)
    private var journal: RotatingTextFile? = null
    private var context: Context? = null
    private var writeFailures = 0L

    /** Disk writes leave the calling thread: the UI, video and audio threads only enqueue. */
    private val writer =
        Executors.newSingleThreadExecutor { task -> Thread(task, "diagnostics-writer").apply { isDaemon = true } }

    /** CarPlay connection attempts. The hero card, receiver callbacks and [finishCurrent] use this one. */
    val timeline = ConnectionTimeline(sink = ::persist)

    /** CarWith self-test runs, kept apart so a self-test and a CarPlay attempt never supersede each other. */
    val selfTest = ConnectionTimeline(sink = ::persist, track = "self_test")

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

    fun precheck(
        code: DiagnosticCode,
        target: ConnectionTimeline = timeline,
    ) {
        val id = target.begin("precheck")
        target.finish(id, code, true)
    }

    /**
     * A permission dialog closed. Showing the dialog is not a failure; only a denial is recorded,
     * so the hero card does not report "连接未完成" while the user is still deciding.
     */
    fun permissionResult(
        granted: Boolean,
        deniedCode: DiagnosticCode,
    ) {
        if (!granted) precheck(deniedCode)
    }

    /** The most recently started attempt on either timeline, for the status line. */
    fun latestSnapshot(): ConnectionTimeline.Snapshot? =
        listOfNotNull(timeline.snapshot(), selfTest.snapshot()).maxByOrNull { it.startedAtMs }

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
        val safeStack = DiagnosticRedactor.scrubAtSource(throwable.stackTraceToString().take(32_000))
        listOf(timeline, selfTest).forEach { target ->
            target.snapshot()?.let { target.issue(it.id, DiagnosticCode.UNCAUGHT_EXCEPTION, throwable.javaClass.name) }
        }
        persist(
            JSONObject()
                .put("event", "UNCAUGHT_EXCEPTION")
                .put("timeMs", System.currentTimeMillis())
                .put("thread", thread.name)
                .put("attempt", timeline.snapshot()?.id ?: "none")
                .put("selfTestAttempt", selfTest.snapshot()?.id ?: "none")
                .put("stack", safeStack)
                .toString(),
        )
        // The process is about to die: wait briefly so the queued records reach the journal.
        flush()
        context?.let { DebugFileLog.append(it, "UNCAUGHT_EXCEPTION thread=${thread.name}\n$safeStack") }
    }

    fun history(): String {
        flush()
        return synchronized(this) {
            runCatching { journal?.read(256_000) ?: "[diagnostic journal unavailable]" }
                .getOrDefault("[diagnostic journal unreadable]")
        }
    }

    /** Waits (bounded) until every record queued so far has been written. */
    private fun flush() {
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
    }

    @Synchronized
    fun storageStatus(): String = "diagnosticWriteFailures=$writeFailures"

    private fun persist(line: String) {
        try {
            writer.execute { write(line) }
        } catch (_: Exception) {
            synchronized(this) { writeFailures++ }
        }
    }

    @Synchronized
    private fun write(line: String) {
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
