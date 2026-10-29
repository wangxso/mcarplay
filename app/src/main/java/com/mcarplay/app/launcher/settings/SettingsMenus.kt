package com.mcarplay.app.launcher.settings

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.mcarplay.app.R

/** 车机设置菜单注册表：车机双栏与手机二级菜单共用（菜单项/标题/Fragment 工厂）。 */
object SettingsMenus {
    const val DESKTOP = 0
    const val SUPERSAMPLE = 1
    const val ORIENTATION = 2
    const val DOCK = 3
    const val DEFAULT_MAP = 4
    const val DISPLAY = 5

    data class Item(
        val id: Int,
        @StringRes val title: Int,
        @StringRes val desc: Int,
        val iconRes: Int,
    )

    val ALL =
        listOf(
            Item(DESKTOP, R.string.lay_manage_apps, R.string.sm_desktop_desc, R.drawable.ic_menu_grid),
            Item(SUPERSAMPLE, R.string.lay_supersample, R.string.sm_supersample_desc, R.drawable.ic_menu_zoom),
            Item(ORIENTATION, R.string.lay_landscape, R.string.sm_orientation_desc, R.drawable.ic_screen_rotation),
            Item(DOCK, R.string.lay_dock, R.string.sm_dock_desc, R.drawable.ic_menu_dock),
            Item(DEFAULT_MAP, R.string.lay_default_map, R.string.sm_map_desc, R.drawable.ic_menu_map),
            Item(DISPLAY, R.string.lay_display, R.string.sm_display_desc, R.drawable.ic_menu_display),
        )

    @StringRes
    fun titleOf(id: Int): Int = ALL.first { it.id == id }.title

    fun createFragment(id: Int): Fragment =
        when (id) {
            DESKTOP -> DesktopPanelFragment()
            SUPERSAMPLE -> SupersamplePanelFragment()
            ORIENTATION -> OrientationPanelFragment()
            DOCK -> DockPanelFragment()
            DISPLAY -> DisplayPanelFragment()
            else -> DefaultMapPanelFragment()
        }
}
