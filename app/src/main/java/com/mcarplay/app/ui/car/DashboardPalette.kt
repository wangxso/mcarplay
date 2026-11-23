package com.mcarplay.app.ui.car

internal class DashboardPalette(
    val backdrop: IntArray,
    val dock: Int,
    val card: Int,
    val control: Int,
    val text: Int,
    val muted: Int,
    val track: Int,
    val accent: Int,
    val onAccent: Int,
    val charging: Int,
    val ripple: Int,
) {
    companion object {
        val LIGHT =
            DashboardPalette(
                backdrop = intArrayOf(0xffaec2da.toInt(), 0xffe7eff8.toInt(), 0xffcfdded.toInt()),
                dock = 0xffeaf2fa.toInt(),
                card = 0xfff3f7fc.toInt(),
                control = 0xffdce9f4.toInt(),
                text = 0xff162c45.toInt(),
                muted = 0xff4e6074.toInt(),
                track = 0xff9eafc0.toInt(),
                accent = 0xff205d87.toInt(),
                onAccent = 0xfff7fbff.toInt(),
                charging = 0xff25865f.toInt(),
                ripple = 0x30426491,
            )

        val DARK =
            DashboardPalette(
                backdrop = intArrayOf(0xff101923.toInt(), 0xff1b2c3d.toInt(), 0xff132130.toInt()),
                dock = 0xff1b2938.toInt(),
                card = 0xff1d2c3b.toInt(),
                control = 0xff2c4055.toInt(),
                text = 0xffe8f2fc.toInt(),
                muted = 0xffafc2d5.toInt(),
                track = 0xff6d8298.toInt(),
                accent = 0xff9ed1ff.toInt(),
                onAccent = 0xff112e47.toInt(),
                charging = 0xff5dd6a1.toInt(),
                ripple = 0x45c0ddff,
            )
    }
}
