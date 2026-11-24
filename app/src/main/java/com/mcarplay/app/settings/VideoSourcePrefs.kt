package com.mcarplay.app.settings

import android.content.Context
import com.mcarplay.app.R

internal enum class VideoSource(
    val id: String,
    val label: Int,
) {
    CAR_WITH("carwith", R.string.car_source_carwith),
    DASHBOARD("dashboard", R.string.car_source_dashboard),
    ;

    companion object {
        fun fromId(id: String?): VideoSource = entries.firstOrNull { it.id == id } ?: CAR_WITH
    }
}

internal object VideoSourcePrefs {
    private const val KEY = "video_source"

    fun get(context: Context): VideoSource = VideoSource.fromId(prefs(context).getString(KEY, null))

    fun set(
        context: Context,
        source: VideoSource,
    ) {
        prefs(context).edit().putString(KEY, source.id).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
