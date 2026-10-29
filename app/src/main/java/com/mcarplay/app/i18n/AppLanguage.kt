package com.mcarplay.app.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 界面语言（首期：简体中文、English；默认中文，与系统语言无关）。
 *
 * Android 13+ 直接用系统的按应用语言（[LocaleManager]，系统持久化，也能在系统「应用语言」
 * 里改，切换后 Activity 自动重建）。Android 12 没有该接口：偏好自存，进程启动时交给
 * AppCompat（只作用于 AppCompatActivity），服务通知等非 Activity 文案用 [localized] 取资源。
 * 不经 AppCompat 走 13+：它在没有存活 Activity 时（Application.onCreate）读写都是空操作。
 *
 * 日志、诊断报告与协议字段不随语言变化：它们是给开发者对照的证据。
 */
object AppLanguage {
    enum class Language(
        val tag: String,
    ) {
        ZH("zh-CN"),
        EN("en"),
    }

    val DEFAULT = Language.ZH

    private const val PREFS = "app_language"
    private const val KEY_LANGUAGE = "language"

    /** 进程启动时调用（[com.mcarplay.app.SenderApp.onCreate]）：未设置过时落到默认中文。 */
    fun applyAtStartup(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = localeManager(context)
            if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags(stored(context).tag)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(stored(context).tag))
        }
    }

    fun current(context: Context): Language =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(context).applicationLocales[0]?.let { fromTag(it.toLanguageTag()) } ?: stored(context)
        } else {
            stored(context)
        }

    /** 保存并切换；已打开的 Activity 由 AppCompat 重建。 */
    fun set(
        context: Context,
        language: Language,
    ) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager(context).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
        }
    }

    /** 按当前界面语言取资源的 Context（服务通知、Application 级提示用）。 */
    fun localized(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale(current(context)))
        return context.createConfigurationContext(configuration)
    }

    fun locale(language: Language): Locale = Locale.forLanguageTag(language.tag)

    /** `zh`、`zh-CN`、`zh-Hans-CN` → 中文；`en*` → English；其他返回 null（不认识的语言不猜）。 */
    fun fromTag(tag: String?): Language? {
        val primary = tag?.substringBefore('-')?.substringBefore('_')?.lowercase(Locale.ROOT)
        return when (primary) {
            "zh" -> Language.ZH
            "en" -> Language.EN
            else -> null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun localeManager(context: Context): LocaleManager = context.getSystemService(LocaleManager::class.java)

    private fun stored(context: Context): Language = fromTag(prefs(context).getString(KEY_LANGUAGE, null)) ?: DEFAULT

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
