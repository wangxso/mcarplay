package com.mcarplay.app.diagnostics

import java.io.File

/** Keeps the previous segment and returns a line-aligned UTF-8 tail. Callers share one instance or lock. */
class RotatingTextFile(
    private val file: File,
    private val limit: Long,
) {
    init {
        require(limit >= 10)
    }

    private val previous = File(file.parentFile, file.name + ".previous")

    @Synchronized
    fun append(line: String) {
        val original = (line + "\n").toByteArray(Charsets.UTF_8)
        val bytes =
            if (original.size > limit) {
                val marker = if (limit >= 32) "[oversized log entry omitted]\n" else "[omitted]\n"
                marker.toByteArray(Charsets.UTF_8)
            } else {
                original
            }
        if (file.exists() && file.length() + bytes.size > limit) {
            if (previous.exists()) check(previous.delete()) { "Cannot remove previous log segment" }
            check(file.renameTo(previous)) { "Cannot rotate log segment" }
        }
        file.appendBytes(bytes)
    }

    @Synchronized
    fun read(maxBytes: Int): String {
        require(maxBytes > 0)
        val segments = listOf(previous, file).filter { it.isFile }
        var remaining = maxBytes.toLong()
        val chunks = mutableListOf<ByteArray>()
        for (segment in segments.asReversed()) {
            if (remaining <= 0) break
            val take = minOf(segment.length(), remaining).toInt()
            val bytes = ByteArray(take)
            segment.inputStream().use { input ->
                input.channel.position(segment.length() - take)
                java.io.DataInputStream(input).readFully(bytes)
            }
            chunks.add(0, bytes)
            remaining -= take
        }
        val bytes = chunks.fold(ByteArray(0)) { all, next -> all + next }
        val truncated = segments.sumOf { it.length() } > bytes.size
        val offset = if (truncated) bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it + 1 } else 0
        return (if (truncated) "[earlier log content truncated]\n" else "") +
            String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }
}
