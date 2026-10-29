package com.mcarplay.app.update

import android.content.Context
import com.mcarplay.app.BuildConfig
import com.mcarplay.app.i18n.AppLanguage

/**
 * 「更新内容」弹窗的放行规则与数据来源。
 *
 * 每次覆盖安装（含同版本重装）后首次打开弹一次：以系统记录的 `lastUpdateTime` 为准，
 * 弹过就记下该时刻。全新安装（首次安装时刻 = 最后更新时刻）不弹，那时先看免责声明。
 * 内容来自构建时打包的 CHANGELOG.md：发行构建取 [BuildConfig.RELEASE_TAG] 条目，
 * 本地构建取 `[Unreleased]`；找不到条目就不弹。
 */
object WhatsNew {
    const val CHANGELOG_ASSET = "changelog/CHANGELOG.md"

    private const val PREFS = "whats_new"
    private const val KEY_SHOWN_UPDATE_TIME = "shown_update_time"

    data class Pending(
        val tag: String,
        val notes: ReleaseNotes.Localized,
        val updateTime: Long,
    )

    internal fun shouldShow(
        firstInstallTime: Long,
        lastUpdateTime: Long,
        shownUpdateTime: Long,
    ): Boolean = lastUpdateTime > 0 && lastUpdateTime != firstInstallTime && lastUpdateTime != shownUpdateTime

    internal fun changelogTag(releaseTag: String): String = releaseTag.ifBlank { ReleaseNotes.UNRELEASED }

    /** 需要弹时返回内容；读包信息或资源失败一律视为不弹。 */
    fun pending(context: Context): Pending? =
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val shown = prefs(context).getLong(KEY_SHOWN_UPDATE_TIME, 0L)
            if (!shouldShow(info.firstInstallTime, info.lastUpdateTime, shown)) return@runCatching null
            val tag = changelogTag(BuildConfig.RELEASE_TAG)
            val changelog =
                context.assets
                    .open(CHANGELOG_ASSET)
                    .bufferedReader()
                    .use { it.readText() }
            val notes =
                ReleaseNotes.entry(changelog, tag)?.let { ReleaseNotes.localize(it, AppLanguage.current(context)) }
            if (notes == null) {
                markShown(context, info.lastUpdateTime)
                null
            } else {
                Pending(tag, notes, info.lastUpdateTime)
            }
        }.getOrNull()

    fun markShown(
        context: Context,
        updateTime: Long,
    ) {
        prefs(context).edit().putLong(KEY_SHOWN_UPDATE_TIME, updateTime).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
