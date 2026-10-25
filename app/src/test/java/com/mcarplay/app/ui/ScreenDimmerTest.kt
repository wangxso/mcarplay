package com.mcarplay.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动暗幕的生效条件。
 *
 * 2026-10-19 用户反馈：「暗幕开启后，即使会话已经断开，仍然会过一会儿启动暗幕」。
 * 根因是应用启动时 attach 就排了 45 秒定时器，而车机侧断开走的是
 * `VideoSender.onEnded`——它不释放 VirtualLauncher，定时器照常触发。
 *
 * 现在自动暗幕必须同时满足：设置开启 + 会话活跃 + 已附着（有悬浮窗权限）。
 * 任意一条不满足都不得弹暗幕；手动「假息屏」不受会话限制（用户显式操作）。
 */
class ScreenDimmerTest {
    @Test
    fun autoDimRequiresActiveSession() {
        assertFalse(
            "会话结束后绝不能再弹暗幕（本次 bug）",
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = false, attached = true),
        )
        assertTrue(
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = true),
        )
    }

    @Test
    fun autoDimRequiresSettingEnabled() {
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = false, sessionActive = true, attached = true))
    }

    @Test
    fun autoDimRequiresAttachment() {
        // 缺悬浮窗权限时 windowManager 为 null，showDim 也加不上窗口
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = false))
    }

    @Test
    fun allConditionsMustHold() {
        val cases =
            listOf(
                Triple(true, true, true) to true,
                Triple(true, true, false) to false,
                Triple(true, false, true) to false,
                Triple(true, false, false) to false,
                Triple(false, true, true) to false,
                Triple(false, true, false) to false,
                Triple(false, false, true) to false,
                Triple(false, false, false) to false,
            )
        cases.forEach { (input, expected) ->
            val (enabled, session, attached) = input
            assertTrue(
                "enabled=$enabled session=$session attached=$attached 期望 $expected",
                ScreenDimmer.shouldAutoDim(enabled, session, attached) == expected,
            )
        }
    }
}
