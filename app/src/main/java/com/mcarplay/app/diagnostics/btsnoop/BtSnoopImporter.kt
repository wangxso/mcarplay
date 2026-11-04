package com.mcarplay.app.diagnostics.btsnoop

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 从用户选择或分享来的文件里找 btsnoop 并分析：支持错误报告 zip（含一层嵌套 zip）和裸 btsnoop 文件。
 *
 * 全程流式读取、只在内存里保留分析状态；原始抓包不落盘、不复制、不上传。
 * 错误报告里路径固定为 `FS/data/misc/bluetooth/logs/btsnoop_hci.log`（以及轮换出的 `.last`）。
 */
internal object BtSnoopImporter {
    sealed interface Outcome {
        class Analyzed(
            val files: List<BtSnoopFileAnalysis>,
        ) : Outcome

        /** 是 zip，但里面没有 btsnoop（通常是没开 HCI 日志，或开的是「已过滤」）。 */
        object NoCapture : Outcome

        /** 既不是 zip 也不是 btsnoop。 */
        object Unsupported : Outcome
    }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private const val MAX_ZIP_DEPTH = 2

    fun analyze(
        input: InputStream,
        cancelled: () -> Boolean = { false },
    ): Outcome {
        val buffered = BufferedInputStream(input, 64 * 1024)
        val head = peek(buffered, 8)
        return when {
            BtSnoopFile.isBtSnoop(head) -> Outcome.Analyzed(listOf(analyzeCapture("btsnoop", buffered, cancelled)))
            startsWith(head, ZIP_MAGIC) -> {
                val files = ArrayList<BtSnoopFileAnalysis>()
                scanZip(ZipInputStream(buffered), 1, files, cancelled)
                if (files.isEmpty()) Outcome.NoCapture else Outcome.Analyzed(files.sortedBy { it.stats.firstMicros ?: Long.MAX_VALUE })
            }
            else -> Outcome.Unsupported
        }
    }

    private fun scanZip(
        zip: ZipInputStream,
        depth: Int,
        files: MutableList<BtSnoopFileAnalysis>,
        cancelled: () -> Boolean,
    ) {
        while (!cancelled()) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name.substringAfterLast('/')
            when {
                isCaptureName(name) -> {
                    val shield = NonClosing(zip)
                    val buffered = BufferedInputStream(shield, 64 * 1024)
                    if (BtSnoopFile.isBtSnoop(peek(buffered, 8))) {
                        runCatching { analyzeCapture(name, buffered, cancelled) }.getOrNull()?.let { files += it }
                    }
                }
                name.endsWith(".zip", ignoreCase = true) && depth < MAX_ZIP_DEPTH ->
                    runCatching { scanZip(ZipInputStream(NonClosing(zip)), depth + 1, files, cancelled) }
            }
        }
    }

    /** `btsnoop_hci.log`、`btsnoop_hci.log.last`、`btsnoop_hci.log.filtered` 等；文本摘要不算。 */
    internal fun isCaptureName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("btsnoop") && !lower.endsWith(".txt") && !lower.endsWith(".zip")
    }

    private fun analyzeCapture(
        name: String,
        input: InputStream,
        cancelled: () -> Boolean,
    ): BtSnoopFileAnalysis {
        val analyzer = BtSnoopAnalyzer()
        val stats = BtSnoopFile.read(input, cancelled) { analyzer.accept(it) }
        return BtSnoopFileAnalysis(name, stats, analyzer.finish())
    }

    private fun peek(
        input: BufferedInputStream,
        count: Int,
    ): ByteArray {
        input.mark(count)
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val got = input.read(bytes, read, count - read)
            if (got < 0) break
            read += got
        }
        input.reset()
        return if (read == count) bytes else bytes.copyOf(read)
    }

    private fun startsWith(
        bytes: ByteArray,
        prefix: ByteArray,
    ): Boolean = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    /** 读完一个条目后 zip 流必须继续可用：内层读取者不得关闭它。 */
    private class NonClosing(
        input: InputStream,
    ) : FilterInputStream(input) {
        @Throws(IOException::class)
        override fun close() = Unit
    }
}
