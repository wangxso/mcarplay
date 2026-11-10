package com.mcarplay.app.architecture

import com.mcarplay.app.diagnostics.ConnectionStage
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 应用与 `tools/logbot/rules.py` 之间的契约（docs/ARCHITECTURE-REFACTOR.md 第 4 节）。
 *
 * logbot 按日志文本、阶段名和错误码诊断用户上传的报告。Python 侧的 RulesSyncTest 只在改 `tools/` 时由钩子运行；
 * 这里让只改 Kotlin 的提交（例如重构搬移日志行）同样会因契约破坏而失败。
 */
class LogContractTest {
    /** rules.py 中依赖应用自身日志措辞的规则 → 应用必须输出的短语（含建议里点名让用户附上的行）。 */
    private val appPhrases =
        mapOf(
            "refused" to listOf("CARLIFE:"),
            "carlife_refused" to listOf("CARLIFE:"),
            "video_eof" to listOf("VIDEO: 车机关闭了屏幕数据口", "VIDEO: CarWith 参数集"),
            "screen_off" to listOf("STREAM_END_SCREEN_OFF"),
            "battery_opt" to listOf("POWER: 未豁免电池优化"),
            "transcode" to listOf("VIDEO: 转码统计"),
        )

    /** 只匹配系统/运行时异常文本的规则，不受应用措辞影响。 */
    private val systemTextRules = setOf("oom", "enonet", "reset", "timeout")

    private val rules: String by lazy {
        val file = File(SourceTree.repoRoot, "tools/logbot/rules.py")
        check(file.isFile) { "找不到 $file" }
        file.readText()
    }

    /** `LOG_PATTERNS = [...]` 中每个 `LogPattern(...)` 的 key → 该块原文。 */
    private val logPatterns: Map<String, String> by lazy {
        val body =
            Regex("""^LOG_PATTERNS = \[\n(.*?)^]""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                .find(rules)
                ?.groupValues
                ?.get(1) ?: error("rules.py 中找不到 LOG_PATTERNS")
        body
            .split(Regex("""^    LogPattern\(""", RegexOption.MULTILINE))
            .drop(1)
            .associate { block ->
                val key = Regex("""^\s*"(\w+)",""").find(block)?.groupValues?.get(1) ?: error("无法解析 LogPattern：$block")
                key to block
            }
    }

    private val mainStrings: List<String> by lazy {
        SourceTree.mainKotlinFiles.keys.flatMap { SourceTree.scan(it).strings }
    }

    @Test
    fun everyLogRuleIsClassified() {
        assertTrue("LOG_PATTERNS 解析为空", logPatterns.isNotEmpty())
        assertEquals(
            "rules.py 的 LOG_PATTERNS 有增删：在本测试里把新规则归入 appPhrases 或 systemTextRules",
            (appPhrases.keys + systemTextRules).sorted(),
            logPatterns.keys.sorted(),
        )
    }

    @Test
    fun appPhrasesAreStillReferencedByTheirRules() {
        appPhrases.forEach { (key, phrases) ->
            val block = logPatterns[key] ?: return@forEach
            phrases.forEach { phrase ->
                assertTrue("rules.py 规则 $key 已不再引用「$phrase」，同步更新本测试", block.contains(phrase))
            }
        }
    }

    @Test
    fun appStillEmitsEveryPhraseLogbotMatches() {
        val missing =
            appPhrases.values
                .flatten()
                .distinct()
                .filter { phrase -> mainStrings.none { it.contains(phrase) } }
        assertTrue(
            "以下 logbot 依赖的日志短语已不在应用的字符串字面量中（恢复原措辞，或同一提交修改 tools/logbot/rules.py 与测试）：\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    /**
     * `tools/compare_diagnostics.py` 靠这些格式切分报告、给日志行定日期：报告头的导出时间、两个分节标题，
     * 以及每次进程启动写入日志的 `DIAGNOSTICS: schema=… process=… timeMs=…` 行。
     */
    @Test
    fun reportLayoutThatTheCompareScriptParsesIsUnchanged() {
        val anchors =
            listOf(
                "mcarplay support report / ",
                "generatedAt=",
                " timezone=",
                "=== CONNECTION TIMELINE / ",
                "=== RUNTIME LOG / ",
                "DIAGNOSTICS: schema=1 process=",
                " timeMs=",
            )
        val missing = anchors.filter { anchor -> mainStrings.none { it.contains(anchor) } }
        assertTrue(
            "以下报告/日志格式已变，tools/compare_diagnostics.py 与其测试需同一提交更新：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun stageLabelsMatchConnectionStage() {
        val labels =
            Regex("""^STAGE_LABELS = \{\n(.*?)^}""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                .find(rules)
                ?.groupValues
                ?.get(1) ?: error("rules.py 中找不到 STAGE_LABELS")
        val parsed = Regex(""""([A-Z_]+)": "([^"]*)"""").findAll(labels).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(ConnectionStage.entries.associate { it.name to it.label }, parsed)
    }

    @Test
    fun codeAdviceCoversEveryDiagnosticCode() {
        val advice =
            Regex("""^CODE_ADVICE: [^\n]*= \{\n(.*?)^}""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                .find(rules)
                ?.groupValues
                ?.get(1) ?: error("rules.py 中找不到 CODE_ADVICE")
        val keys = Regex("""^    "([A-Z_]+)":""", RegexOption.MULTILINE).findAll(advice).map { it.groupValues[1] }.toSet()
        assertEquals(DiagnosticCode.entries.map { it.name }.toSet(), keys)
    }
}
