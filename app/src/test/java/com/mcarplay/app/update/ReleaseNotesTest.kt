package com.mcarplay.app.update

import com.mcarplay.app.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val changelog =
        """
        # Changelog

        ## [Unreleased]

        ### English
        - Pending change.

        ### 简体中文
        - 待发布改动。

        ## [v0.1.3-test.3] - 2026-10-29

        > Experimental prerelease: adds the manual address.
        > 实验性预发布：新增手动地址。

        ### English
        - Add **本机蓝牙地址** to the checklist, with `bluetooth.identitySource`.
        - Validation: 546 JVM tests passed.

        ### 简体中文
        - 「连接准备」新增 **本机蓝牙地址**。
          续行补充说明。
        - 验证：546 项 JVM 测试通过。

        Full changes / 完整变更: [v0.1.3-test.2...v0.1.3-test.3](https://example.invalid)

        ## [v0.1.3-test.2] - 2026-10-27

        ### English
        - Older entry.
        """.trimIndent()

    @Test
    fun entryStopsAtNextVersion() {
        val entry = ReleaseNotes.entry(changelog, "v0.1.3-test.3")!!
        assertTrue(entry.startsWith("> Experimental prerelease"))
        assertFalse(entry.contains("Older entry"))
        assertTrue(entry.contains("Full changes"))
    }

    @Test
    fun missingOrEmptyEntryIsNull() {
        assertNull(ReleaseNotes.entry(changelog, "v9.9.9"))
        assertNull(ReleaseNotes.entry("## [v1.0.0] - 2026-01-01\n\n## [v0.9.0]", "v1.0.0"))
    }

    @Test
    fun englishHalfDropsValidationAndChinese() {
        val notes = ReleaseNotes.localize(ReleaseNotes.entry(changelog, "v0.1.3-test.3")!!, AppLanguage.Language.EN)!!
        assertEquals("Experimental prerelease: adds the manual address.", notes.summary)
        assertEquals(listOf("Add **本机蓝牙地址** to the checklist, with `bluetooth.identitySource`."), notes.items)
    }

    @Test
    fun chineseHalfJoinsContinuationAndStopsBeforeCompareLink() {
        val notes = ReleaseNotes.localize(ReleaseNotes.entry(changelog, "v0.1.3-test.3")!!, AppLanguage.Language.ZH)!!
        assertEquals("实验性预发布：新增手动地址。", notes.summary)
        assertEquals(listOf("「连接准备」新增 **本机蓝牙地址**。 续行补充说明。"), notes.items)
    }

    @Test
    fun unreleasedEntryWorksWithoutSummary() {
        val notes = ReleaseNotes.localize(ReleaseNotes.entry(changelog, ReleaseNotes.UNRELEASED)!!, AppLanguage.Language.ZH)!!
        assertNull(notes.summary)
        assertEquals(listOf("待发布改动。"), notes.items)
    }

    @Test
    fun bodyWithoutLanguageSectionsIsNotLocalized() {
        assertNull(ReleaseNotes.localize("Hand-written notes\n- one", AppLanguage.Language.EN))
    }

    @Test
    fun githubReleaseBodyIsLocalizedToo() {
        val body = "# mcarplay v0.1.3-test.3\n\n" + ReleaseNotes.entry(changelog, "v0.1.3-test.3")
        assertEquals(1, ReleaseNotes.localize(body, AppLanguage.Language.EN)!!.items.size)
    }

    @Test
    fun inlineMarkdownBecomesPlainTextWithBoldRanges() {
        val (plain, bold) = ReleaseNotes.inline("Add **本机蓝牙地址** via `x`, then **Go**.")
        assertEquals("Add 本机蓝牙地址 via x, then Go.", plain)
        assertEquals(listOf(4..9, 23..24), bold)
        assertEquals("本机蓝牙地址", plain.substring(bold[0]))
        assertEquals("Go", plain.substring(bold[1]))
    }

    @Test
    fun unterminatedBoldIsDropped() {
        val (plain, bold) = ReleaseNotes.inline("a **b")
        assertEquals("a b", plain)
        assertTrue(bold.isEmpty())
    }

    @Test
    fun bundledChangelogHasBothHalvesForEveryRelease() {
        val file =
            generateSequence(
                java.io.File("").absoluteFile,
            ) { it.parentFile }.map { it.resolve("CHANGELOG.md") }.first { it.exists() }
        val text = file.readText()
        val tags = Regex("^## \\[(v[^]]+)]", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(tags.isNotEmpty())
        tags.take(5).forEach { tag ->
            val entry = ReleaseNotes.entry(text, tag)!!
            AppLanguage.Language.entries.forEach { language ->
                val notes = ReleaseNotes.localize(entry, language)
                assertTrue("$tag $language", notes != null && notes.items.isNotEmpty())
            }
        }
    }
}
