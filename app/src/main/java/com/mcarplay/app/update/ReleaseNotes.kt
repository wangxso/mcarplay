package com.mcarplay.app.update

import com.mcarplay.app.i18n.AppLanguage

/**
 * 解析 CHANGELOG.md / GitHub 发行说明里的一个版本条目，并按界面语言取出对应一半。
 *
 * 条目格式（见 CHANGELOG.md 与 tools/release_notes.py）：`## [tag] - date` 下面先是
 * 英文、再是中文的 `>` 摘要各一行，然后 `### English` 与 `### 简体中文` 两组 `- ` 条目，
 * 最后一行 `Full changes / 完整变更` 对比链接。验证条目（测试数、检查项）只对开发者有意义，
 * 给用户看的版本略去。
 */
object ReleaseNotes {
    const val UNRELEASED = "Unreleased"

    data class Localized(
        val summary: String?,
        val items: List<String>,
    )

    /** `## [tag]` 段落正文；找不到或为空时返回 null。 */
    fun entry(
        changelog: String,
        tag: String,
    ): String? {
        val lines = changelog.lines()
        val start = lines.indexOfFirst { it.startsWith("## [$tag]") }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines
            .subList(start + 1, end)
            .joinToString("\n")
            .trim()
            .ifEmpty { null }
    }

    /** 取 [body] 中 [language] 那一半；没有语言分节（旧格式或手写说明）时返回 null。 */
    fun localize(
        body: String,
        language: AppLanguage.Language,
    ): Localized? {
        val heading = if (language == AppLanguage.Language.ZH) ZH_HEADING else EN_HEADING
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim() == heading }
        if (start < 0) return null
        val items = mutableListOf<String>()
        for (line in lines.drop(start + 1)) {
            if (line.startsWith("#") || line.startsWith("Full changes")) break
            val trimmed = line.trim()
            when {
                trimmed.startsWith("- ") -> items += trimmed.removePrefix("- ").trim()
                trimmed.isNotEmpty() && items.isNotEmpty() && line.startsWith(" ") ->
                    items[items.lastIndex] = items.last() + " " + trimmed
            }
        }
        val visible = items.filterNot { item -> VALIDATION_PREFIXES.any { item.startsWith(it) } }
        val summaries = lines.filter { it.startsWith(">") }.map { it.removePrefix(">").trim() }.filter { it.isNotEmpty() }
        val summary =
            summaries.firstOrNull { hasCjk(it) == (language == AppLanguage.Language.ZH) }
        if (visible.isEmpty() && summary == null) return null
        return Localized(summary, visible)
    }

    /**
     * 行内 Markdown 简化：去掉 `**粗体**` 与 `` `代码` `` 标记，返回纯文本和粗体区间
     * （[IntRange] 为纯文本中的下标，含两端），交给界面加粗。
     */
    fun inline(text: String): Pair<String, List<IntRange>> {
        val out = StringBuilder()
        val bold = mutableListOf<IntRange>()
        var boldStart = -1
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    if (boldStart < 0) {
                        boldStart = out.length
                    } else {
                        if (out.length > boldStart) bold += boldStart until out.length
                        boldStart = -1
                    }
                    i += 2
                }
                text[i] == '`' -> i++
                else -> out.append(text[i++])
            }
        }
        return out.toString() to bold
    }

    private const val EN_HEADING = "### English"
    private const val ZH_HEADING = "### 简体中文"
    private val VALIDATION_PREFIXES = listOf("Validation:", "验证：", "验证:")

    private fun hasCjk(text: String): Boolean = text.any { it in '一'..'鿿' }
}
