package com.mcarplay.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * 锁定 docs/UI-REDESIGN.md §5.1 的令牌：文字与其底色的对比度须达到 WCAG AA（4.5:1），
 * 浅色与深色各一套。读取 res 下的令牌文件，改色时测试会立刻指出哪一对不达标。
 */
class UiTokenContrastTest {
    private val lightTokens = load("src/main/res/values/colors_ui.xml")
    private val nightTokens = load("src/main/res/values-night/colors_ui.xml")

    private data class Rgb(
        val r: Double,
        val g: Double,
        val b: Double,
    )

    private fun load(path: String): Map<String, String> {
        val text = File(path).readText()
        return Regex("""<color name="(ui_\w+)">(#[0-9A-Fa-f]{6,8})</color>""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** 把（可能带透明度的）颜色按 [under] 混合成不透明色。 */
    private fun resolve(
        hex: String,
        under: Rgb? = null,
    ): Rgb {
        val digits = hex.removePrefix("#")
        val alpha = if (digits.length == 8) digits.substring(0, 2).toInt(16) / 255.0 else 1.0
        val rgb = digits.takeLast(6)
        val c =
            Rgb(
                rgb.substring(0, 2).toInt(16).toDouble(),
                rgb.substring(2, 4).toInt(16).toDouble(),
                rgb.substring(4, 6).toInt(16).toDouble(),
            )
        if (alpha >= 1.0 || under == null) return c
        return Rgb(
            c.r * alpha + under.r * (1 - alpha),
            c.g * alpha + under.g * (1 - alpha),
            c.b * alpha + under.b * (1 - alpha),
        )
    }

    private fun luminance(c: Rgb): Double {
        fun lin(v: Double): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    private fun contrast(
        a: Rgb,
        b: Rgb,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun check(
        tokens: Map<String, String>,
        mode: String,
    ) {
        fun color(
            name: String,
            under: Rgb? = null,
        ) = resolve(tokens.getValue(name), under)

        val bg = color("ui_bg")
        val card = color("ui_card")
        val pairs =
            listOf(
                "text/bg" to contrast(color("ui_text"), bg),
                "text/card" to contrast(color("ui_text"), card),
                "text_secondary/bg" to contrast(color("ui_text_secondary"), bg),
                "text_secondary/card" to contrast(color("ui_text_secondary"), card),
                "accent_text/card" to contrast(color("ui_accent_text"), card),
                "ok_text/card" to contrast(color("ui_ok_text"), card),
                "error_text/card" to contrast(color("ui_error_text"), card),
                // 主按钮：白字压在蓝底上
                "on_accent/accent_fill" to contrast(color("ui_on_accent"), color("ui_accent_fill")),
                // 次按钮 / 危险按钮：字压在半透明底与页面底混合后的颜色上
                "accent_text/accent_tint" to contrast(color("ui_accent_text"), color("ui_accent_tint", bg)),
                "error_text/error_tint" to contrast(color("ui_error_text"), color("ui_error_tint", bg)),
                // 徽标：状态文字压在状态色淡底（再叠在卡片上）
                "ok_text/ok_tint" to contrast(color("ui_ok_text"), color("ui_ok_tint", card)),
                "warn_text/card" to contrast(color("ui_warn_text"), card),
                "warn_text/warn_tint" to contrast(color("ui_warn_text"), color("ui_warn_tint", card)),
                "text_secondary/field" to contrast(color("ui_text_secondary"), color("ui_field")),
            )
        val failed = pairs.filter { it.second < 4.5 }
        assertTrue(
            "$mode 对比度不足 4.5:1：" + failed.joinToString { "${it.first}=${"%.2f".format(it.second)}" },
            failed.isEmpty(),
        )
    }

    @Test
    fun lightTokensMeetAa() = check(lightTokens, "light")

    @Test
    fun nightTokensMeetAa() = check(nightTokens, "night")

    @Test
    fun lightAndNightDefineTheSameTokens() {
        assertTrue(lightTokens.keys == nightTokens.keys)
    }
}
