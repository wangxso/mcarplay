package com.mcarplay.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test
    fun defaultIsChinese() {
        assertEquals(AppLanguage.Language.ZH, AppLanguage.DEFAULT)
    }

    @Test
    fun tagsMapToSupportedLanguages() {
        assertEquals(AppLanguage.Language.ZH, AppLanguage.fromTag("zh"))
        assertEquals(AppLanguage.Language.ZH, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.Language.ZH, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.Language.ZH, AppLanguage.fromTag("zh_TW"))
        assertEquals(AppLanguage.Language.EN, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.Language.EN, AppLanguage.fromTag("EN-us"))
    }

    @Test
    fun unknownTagsAreNotGuessed() {
        assertNull(AppLanguage.fromTag(null))
        assertNull(AppLanguage.fromTag(""))
        assertNull(AppLanguage.fromTag("ja-JP"))
    }

    @Test
    fun storedTagsRoundTrip() {
        AppLanguage.Language.entries.forEach { assertEquals(it, AppLanguage.fromTag(it.tag)) }
    }
}
