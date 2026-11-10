package com.mcarplay.app.architecture

import java.io.File

/**
 * 架构/契约测试读取仓库源码的入口。
 *
 * Gradle 单测的工作目录是 `app/`，IDE 里可能是仓库根：向上找到同时含 `settings.gradle.kts` 与
 * `app/src/main/java` 的目录。找不到就失败——护栏测试静默通过比不跑更糟。
 */
internal object SourceTree {
    val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main/java").isDirectory }
            ?: error("找不到仓库根目录（从 ${File("").absolutePath} 向上查找 settings.gradle.kts 与 app/src/main/java）")
    }

    /** `app/src/main/java/com/mcarplay/app`。 */
    val mainRoot: File by lazy { File(repoRoot, "app/src/main/java/com/mcarplay/app").also { check(it.isDirectory) } }

    /** 主源码中的全部 Kotlin 文件，键为相对 [mainRoot] 的路径（`/` 分隔）。 */
    val mainKotlinFiles: Map<String, File> by lazy {
        mainRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associateBy { it.relativeTo(mainRoot).invariantSeparatorsPath }
            .toSortedMap()
            .also { check(it.isNotEmpty()) { "主源码目录下没有 Kotlin 文件：$mainRoot" } }
    }

    private val scans = HashMap<String, KotlinSource.Scan>()

    fun scan(path: String): KotlinSource.Scan =
        synchronized(scans) {
            scans.getOrPut(path) { KotlinSource.scan(mainKotlinFiles.getValue(path).readText()) }
        }

    /** 相对路径的第一级目录即顶层包（`airplay/AirPlayClient.kt` → `airplay`）；根包文件返回空串。 */
    fun topLevelPackage(path: String): String = path.substringBefore('/', missingDelimiterValue = "")
}

/**
 * 极简 Kotlin 词法拆分：把源码分成「代码」与「字符串字面量内容」，丢弃注释。
 *
 * 只服务于架构测试：识别行注释、可嵌套块注释、普通/原始字符串、字符字面量，以及字符串模板
 * `${...}`（其中又可嵌套字符串）与 `$name`。模板里的表达式算代码。
 */
internal object KotlinSource {
    class Scan(
        /** 去掉注释与字符串内容后的代码（字符串留下一对空引号占位）。 */
        val code: String,
        /** 各字符串字面量的文本片段（在模板处切开）。 */
        val strings: List<String>,
    )

    private sealed class Frame {
        /** 普通代码；[template] 为 true 时是 `${...}` 内部，[depth] 记录未闭合的 `{`。 */
        class Code(
            val template: Boolean,
        ) : Frame() {
            var depth = 0
        }

        class Str(
            val raw: Boolean,
        ) : Frame() {
            val fragment = StringBuilder()
        }
    }

    fun scan(text: String): Scan {
        val code = StringBuilder(text.length)
        val strings = ArrayList<String>()
        val stack = ArrayDeque<Frame>().apply { addLast(Frame.Code(template = false)) }
        var i = 0

        fun flush(str: Frame.Str) {
            if (str.fragment.isNotEmpty()) strings.add(str.fragment.toString())
            str.fragment.setLength(0)
        }

        while (i < text.length) {
            val c = text[i]
            when (val top = stack.last()) {
                is Frame.Code ->
                    when {
                        text.startsWith("//", i) -> {
                            val end = text.indexOf('\n', i)
                            i = if (end < 0) text.length else end
                        }

                        text.startsWith("/*", i) -> {
                            var depth = 0
                            while (i < text.length) {
                                if (text.startsWith("/*", i)) {
                                    depth++
                                    i += 2
                                } else if (text.startsWith("*/", i)) {
                                    depth--
                                    i += 2
                                    if (depth == 0) break
                                } else {
                                    if (text[i] == '\n') code.append('\n')
                                    i++
                                }
                            }
                            code.append(' ')
                        }

                        text.startsWith("\"\"\"", i) -> {
                            stack.addLast(Frame.Str(raw = true))
                            code.append("\"\"")
                            i += 3
                        }

                        c == '"' -> {
                            stack.addLast(Frame.Str(raw = false))
                            code.append("\"\"")
                            i++
                        }

                        c == '\'' -> {
                            // 字符字面量：'a'、'\n'、'A'、'\''
                            var j = i + 1
                            if (j < text.length && text[j] == '\\') j += 2
                            while (j < text.length && text[j] != '\'' && text[j] != '\n') j++
                            code.append("' '")
                            i = j + 1
                        }

                        c == '{' -> {
                            if (top.template) top.depth++
                            code.append(c)
                            i++
                        }

                        c == '}' && top.template && top.depth == 0 -> {
                            stack.removeLast()
                            code.append(' ')
                            i++
                        }

                        else -> {
                            if (c == '}' && top.template) top.depth--
                            code.append(c)
                            i++
                        }
                    }

                is Frame.Str ->
                    when {
                        top.raw && text.startsWith("\"\"\"", i) -> {
                            // 原始字符串以连续引号里的最后三个结束，多出来的引号属于内容
                            var end = i + 3
                            while (end < text.length && text[end] == '"') end++
                            repeat(end - i - 3) { top.fragment.append('"') }
                            flush(top)
                            stack.removeLast()
                            i = end
                        }

                        !top.raw && c == '"' -> {
                            flush(top)
                            stack.removeLast()
                            i++
                        }

                        !top.raw && c == '\\' && i + 1 < text.length -> {
                            top.fragment.append(text[i + 1])
                            i += 2
                        }

                        !top.raw && c == '\n' -> {
                            // 未闭合的普通字符串：按行结束，避免吞掉后续代码
                            flush(top)
                            stack.removeLast()
                            code.append('\n')
                            i++
                        }

                        c == '$' && i + 1 < text.length && text[i + 1] == '{' -> {
                            flush(top)
                            stack.addLast(Frame.Code(template = true))
                            code.append(' ')
                            i += 2
                        }

                        c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_') -> {
                            flush(top)
                            var j = i + 1
                            while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                            code.append(' ').append(text, i + 1, j).append(' ')
                            i = j
                        }

                        else -> {
                            top.fragment.append(c)
                            i++
                        }
                    }
            }
        }
        (stack.last() as? Frame.Str)?.let(::flush)
        return Scan(code.toString(), strings)
    }

    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val word = Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+[0-9]*|[A-Z]+[0-9]*|[0-9]+")

    /** 代码里出现的标识符按驼峰/下划线拆成小写单词（`PassatReturnStrategy` → passat、return、strategy）。 */
    fun identifierWords(code: String): Set<String> =
        identifier
            .findAll(code)
            .flatMap { match -> word.findAll(match.value).map { it.value.lowercase() } }
            .toSet()
}
