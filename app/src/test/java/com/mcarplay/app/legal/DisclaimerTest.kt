package com.mcarplay.app.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerPolicyTest {
    @Test
    fun contentNotScrollableOnlyNeedsCountdown() {
        // 文案一屏放得下（无需滚动）：只等倒计时
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = false, scrolledToBottom = false))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = false, scrolledToBottom = false))
    }

    @Test
    fun scrollableContentRequiresBothCountdownAndBottom() {
        assertFalse(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = false))
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = true))
    }

    @Test
    fun shorterElapsedButAtBottomStillBlocked() {
        // 滑到底了但没等够 10 秒：仍不可接受（两个条件是并列的）
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
    }
}

class DisclaimerVersionTest {
    @Test
    fun firstRunNeedsDisclaimer() {
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 0, currentVersion = 1))
    }

    @Test
    fun acceptedCurrentVersionNeedsNothing() {
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 1))
        // 老版本接受过（1）但当前是 2：文案变了必须重新确认
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 2))
        // 已接受更高版本（回退安装旧 APK）：不再打扰
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 3, currentVersion = 2))
    }
}

class DisclaimerHintTest {
    @Test
    fun hintsExplainWhyAcceptIsBlocked() {
        // 两者都没满足：提示同时给出倒计时与滑动要求
        assertEquals(
            DisclaimerBlock.SCROLL_AND_WAIT,
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = true,
                scrolledToBottom = false,
            ),
        )
        // 只有倒计时
        assertEquals(
            DisclaimerBlock.WAIT,
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = false,
                scrolledToBottom = false,
            ),
        )
        // 只有滚动
        assertEquals(
            DisclaimerBlock.SCROLL,
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = false,
            ),
        )
        // 已可接受：无提示
        assertNull(
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = true,
            ),
        )
    }
}

class DisclaimerContentTest {
    @Test
    fun contentCoversMandatoryTopics() {
        val text = DisclaimerContent.sections.joinToString("\n") { it.title + "\n" + it.body }
        // 分享出去必须讲清的三类内容：测试范围、驾驶风险、系统权限
        assertTrue(text.contains("本轮 CarWith 桥接测试组合"))
        assertTrue(text.contains("小米 17 Pro Max"))
        assertTrue(text.contains("真实车机仍待验收"))
        assertTrue(text.contains("驾驶安全"))
        assertTrue(text.contains("Shizuku"))
        // 非官方声明（避免被误认为与 Apple 有关联）
        assertTrue(text.contains("非官方"))
        assertTrue(text.contains("商标"))
    }

    @Test
    fun englishContentMirrorsChineseSections() {
        // 两版逐节对应：缺节或多节说明改了一版忘了另一版
        assertEquals(DisclaimerContent.sections.size, DisclaimerContent.sectionsEn.size)
        val text = DisclaimerContent.sectionsEn.joinToString("\n") { it.title + "\n" + it.body }
        assertTrue(text.contains("Xiaomi 17 Pro Max"))
        assertTrue(text.contains("Driving safety"))
        assertTrue(text.contains("Shizuku"))
        assertTrue(text.contains("unofficial"))
        assertTrue(text.contains("trademarks"))
        assertFalse(text.contains("**"))
    }

    @Test
    fun contentHasNoMarkdownMarkers() {
        // 正文直接进 TextView：`**`/`#` 会原样显示，属排版事故
        val text = DisclaimerContent.sections.joinToString("") { it.body }
        assertFalse(text.contains("**"))
        assertFalse(text.contains("##"))
        assertFalse(text.contains("`"))
    }
}
