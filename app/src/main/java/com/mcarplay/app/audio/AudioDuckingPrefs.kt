package com.mcarplay.app.audio

import android.content.Context

/**
 * 「车机设置 → 显示」里的「导航时音乐音量」：提示期间音乐压低到多少 dB。
 *
 * 0dB = 不压低；默认 -15dB（2026-10-30 WinPlay 1.2.5 台架听感确认可用）。会话中调整
 * 立即生效（见 SessionController.applyDuckDb），不必重连。
 */
object AudioDuckingPrefs {
    const val DUCK_DB_MIN = -30
    const val DUCK_DB_MAX = 0
    const val DUCK_DB_DEFAULT = -15

    private const val PREFS_NAME = "settings"
    private const val KEY_DUCK_DB = "audio_duck_db"

    fun duckDb(context: Context): Int =
        clamp(
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_DUCK_DB, DUCK_DB_DEFAULT),
        )

    fun setDuckDb(
        context: Context,
        db: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DUCK_DB, clamp(db))
            .apply()
    }

    /** 本次会话使用的参数（其余时长保持默认）。 */
    fun config(context: Context): AudioDuckingConfig = AudioDuckingConfig(duckDb = duckDb(context).toFloat())

    fun clamp(db: Int): Int = db.coerceIn(DUCK_DB_MIN, DUCK_DB_MAX)

    /** 滑条 progress（0..30）↔ dB（0..-30）：滑条越往右压得越低。 */
    fun dbOfProgress(progress: Int): Int = clamp(-progress)

    fun progressOfDb(db: Int): Int = -clamp(db)
}
