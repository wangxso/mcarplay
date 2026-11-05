package com.mcarplay.app.bridge

import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Drain both pipes concurrently, retain bounded output, and never wait for EOF before the deadline. */
internal object BoundedDiagnosticCommand {
    data class Result(
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
        val output: String,
    )

    fun read(
        stdout: InputStream,
        stderr: InputStream,
        waitFor: (Long) -> Boolean,
        exitValue: () -> Int,
        destroy: () -> Unit,
        timeoutMs: Long = 5_000,
        maxBytes: Int = 256_000,
    ): Result {
        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "system-log-reader").apply { isDaemon = true } }

        fun drain(stream: InputStream): Pair<ByteArray, Boolean> {
            val saved = java.io.ByteArrayOutputStream()
            var truncated = false
            val buffer = ByteArray(4096)
            stream.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    val retain = minOf(count, maxBytes - saved.size())
                    if (retain > 0) saved.write(buffer, 0, retain)
                    if (retain < count) truncated = true
                }
            }
            return saved.toByteArray() to truncated
        }
        val out = pool.submit(Callable { drain(stdout) })
        val err = pool.submit(Callable { drain(stderr) })
        try {
            val finished = waitFor(timeoutMs)
            if (!finished) destroy()
            val output = runCatching { out.get(1, TimeUnit.SECONDS) }.getOrNull()
            val errors = runCatching { err.get(1, TimeUnit.SECONDS) }.getOrNull()
            return Result(
                exitCode = if (finished) runCatching(exitValue).getOrDefault(-1) else -1,
                timedOut = !finished,
                truncated = output == null || errors == null || output.second || errors.second,
                output = listOfNotNull(output?.first?.decodeToString(), errors?.first?.decodeToString()).joinToString("\n"),
            )
        } finally {
            runCatching(destroy)
            runCatching { stdout.close() }
            runCatching { stderr.close() }
            out.cancel(true)
            err.cancel(true)
            pool.shutdownNow()
        }
    }
}
