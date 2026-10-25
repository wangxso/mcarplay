package com.mcarplay.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxHintParseTest {
    /** 真机（荣耀 Magic7 Pro，CarPlayMap #511）dumpsys 片段，只保留相关行。 */
    private val realDump =
        """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #511 (activities from top to bottom):
          * Task{d47b965 #34012 type=standard A=10241:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen translucent=false embedded=false sz=1}
            isSleeping=false
            topResumedActivity=ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
            * Hist  #0: ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
              packageName=com.autonavi.minimap processName=com.autonavi.minimap
              CurrentConfiguration={1.0 460mcc1mnc [zh_CN_#Hans] masculine ldltr sw494dp w494dp h518dp 200dpi nrml compactNeeded port -touch winConfig={ mBounds=Rect(312, 0 - 929, 648) mAppBounds=Rect(312, 0 - 929, 648) mMaxBounds=Rect(0, 0 - 1241, 648) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen}}
              bounds=Rect(312, 0 - 929, 648)
              overrideOrientation=SCREEN_ORIENTATION_PORTRAIT
              requestedOrientation=SCREEN_ORIENTATION_PORTRAIT
              areBoundsLetterboxed=true
              isLetterboxRunning=true
                letterboxReason=FIXED_ORIENTATION
          Resumed activities in task display areas (from top to bottom):
            Resumed: ActivityRecord{15928233 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34012}
        Display #510 (activities from top to bottom):
          * Task{74f1ce1 #33999 type=standard A=10296:com.mcarplay.app U=0 visible=true visibleRequested=true mode=fullscreen translucent=true embedded=false sz=1}
            topResumedActivity=ActivityRecord{199877690 u0 com.mcarplay.app/.launcher.GridActivity t33999}
            * Hist  #0: ActivityRecord{199877690 u0 com.mcarplay.app/.launcher.GridActivity t33999}
              CurrentConfiguration={1.0 460mcc1mnc [zh_CN_#Hans] masculine ldltr sw431dp w825dp h431dp 361dpi nrml long compactNeeded land winConfig={ mBounds=Rect(0, 0 - 1861, 972) mAppBounds=Rect(0, 0 - 1861, 972) mMaxBounds=Rect(0, 0 - 1861, 972) mDisplayRotation=ROTATION_0 mWindowingMode=fullscreen}}
              bounds=Rect(0, 0 - 1861, 972)
              areBoundsLetterboxed=false
              isLetterboxRunning=false
        """.trimIndent()

    @Test
    fun parsesLetterboxRectOfTopActivityOnTargetDisplay() {
        val activity = parseTopLetterboxActivity(511, realDump, 1241, 648)!!
        assertEquals("com.autonavi.minimap", activity.packageName)
        assertEquals("com.autonavi.minimap/com.autonavi.map.activity.SplashActivity", activity.component)
        assertEquals(511, activity.displayId)
        assertTrue(activity.letterboxed)
        assertEquals("FIXED_ORIENTATION", activity.letterboxReason)
        // 内窗与外框都取自 dumpsys 实测值（不是估算）：左右各 312px
        assertEquals(PixelBounds(312, 0, 929, 648), activity.inner)
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.full)
        assertEquals(312, activity.inner.left - activity.full.left)
        assertEquals(312, activity.full.right - activity.inner.right)
    }

    @Test
    fun picksTopActivityOfRequestedDisplayNotFirstSection() {
        val activity = parseTopLetterboxActivity(510, realDump, 1861, 972)!!
        assertEquals("com.mcarplay.app", activity.packageName)
        assertFalse(activity.letterboxed)
        assertNull(activity.letterboxReason)
    }

    @Test
    fun fallsBackToDisplaySizeWhenMaxBoundsMissing() {
        val dump =
            """
            Display #509 (activities from top to bottom):
              * Task{aaa #1 type=standard A=10241:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen translucent=false embedded=false sz=1}
                topResumedActivity=ActivityRecord{bbb u0 com.autonavi.minimap/.MainActivity t1}
                * Hist  #0: ActivityRecord{bbb u0 com.autonavi.minimap/.MainActivity t1}
                  bounds=Rect(312, 0 - 929, 648)
                  areBoundsLetterboxed=true
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
            """.trimIndent()
        val activity = parseTopLetterboxActivity(509, dump, 1241, 648)!!
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.full)
        assertEquals(312, activity.inner.left)
    }

    @Test
    fun ignoresDumpWithoutRequestedDisplay() {
        assertNull(parseTopLetterboxActivity(123, realDump, 1241, 648))
        assertNull(parseTopLetterboxActivity(511, "", 1241, 648))
    }

    @Test
    fun ignoresOtherActivityHistBlocks() {
        val dump =
            """
            Display #511 (activities from top to bottom):
              * Task{aaa #1 type=standard A=10241:com.a U=0 visible=true visibleRequested=true mode=fullscreen translucent=false embedded=false sz=1}
                topResumedActivity=ActivityRecord{bbb u0 com.a/.Main t1}
                * Hist  #0: ActivityRecord{bbb u0 com.a/.Main t1}
                  bounds=Rect(0, 0 - 1241, 648)
                  areBoundsLetterboxed=true
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
                * Hist  #1: ActivityRecord{ccc u0 com.b/.Main t1}
                  bounds=Rect(312, 0 - 929, 648)
                  isLetterboxRunning=true
                    letterboxReason=FIXED_ORIENTATION
            """.trimIndent()
        val activity = parseTopLetterboxActivity(511, dump, 1241, 648)!!
        assertEquals("com.a", activity.packageName)
        assertEquals(PixelBounds(0, 0, 1241, 648), activity.inner)
    }
}

class LetterboxHintPolicyTest {
    private fun activity(
        pkg: String = "com.ss.android.article.news",
        letterboxed: Boolean = true,
        reason: String? = "FIXED_ORIENTATION",
    ) = LetterboxActivity(
        packageName = pkg,
        component = "$pkg/.Main",
        displayId = 510,
        letterboxed = letterboxed,
        letterboxReason = reason,
        inner = PixelBounds(312, 0, 929, 648),
        full = PixelBounds(0, 0, 1241, 648),
    )

    @Test
    fun showsOnlyForForceLandscapePackageWithFixedOrientationLetterbox() {
        val enabled = setOf("com.ss.android.article.news")
        assertTrue(shouldShowLetterboxHint(activity(), enabled, emptySet()))
        // 不在名单：用户本意就是竖窗，别打扰
        assertFalse(shouldShowLetterboxHint(activity(pkg = "com.other"), enabled, emptySet()))
        // 不是方向锁导致的 letterbox（宽高比等）：不是本提示的场景
        assertFalse(shouldShowLetterboxHint(activity(reason = "BOOK_ASPECT_RATIO"), enabled, emptySet()))
        assertFalse(shouldShowLetterboxHint(activity(letterboxed = false), enabled, emptySet()))
    }

    @Test
    fun respectsUserDismissalForCurrentSession() {
        val enabled = setOf("com.ss.android.article.news")
        assertFalse(
            shouldShowLetterboxHint(
                activity(),
                enabled,
                setOf("com.ss.android.article.news"),
            ),
        )
    }

    @Test
    fun prefLeftSideButFallsBackToRightWhenLeftTooNarrow() {
        // 左侧放得下 → 左侧（靠近驾驶位）
        assertEquals(LetterboxSide.LEFT, chooseLetterboxSide(leftPx = 312f, rightPx = 312f, neededPx = 240f))
        // 左侧只有几像素、右侧够 → 右侧
        assertEquals(LetterboxSide.RIGHT, chooseLetterboxSide(leftPx = 8f, rightPx = 312f, neededPx = 240f))
        // 两侧都放不下 → 不显示（黑边窄到与全屏横屏无区别）
        assertNull(chooseLetterboxSide(leftPx = 40f, rightPx = 40f, neededPx = 240f))
        // 左侧更宽但右侧也够：仍优先左侧
        assertEquals(LetterboxSide.LEFT, chooseLetterboxSide(leftPx = 500f, rightPx = 260f, neededPx = 240f))
    }
}

class LetterboxCardGestureTest {
    @Test
    fun tapOnlyWhenPressStartsAndEndsOnCard() {
        // 按下落在卡片上 → 由卡片接管
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, false, true, true))
        // 按住期间 move 一律吞掉，不漏给应用
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, true, true, true))
        // 抬起仍在卡片内 → 算一次点按
        assertEquals(CardGesture.TAP, resolveCardGesture(true, true, false, true))
    }

    @Test
    fun dragOffCardCancelsTapButStillConsumes() {
        // 按住后拖出卡片：抬起时命中=false → 吞掉但不触发
        assertEquals(CardGesture.CONSUME, resolveCardGesture(true, true, false, false))
    }

    @Test
    fun passesThroughWhenPressStartsOutsideCard() {
        // 未命中按下 → 交回普通路由（注入应用）
        assertEquals(CardGesture.NOT_HANDLED, resolveCardGesture(true, false, true, false))
        assertEquals(CardGesture.NOT_HANDLED, resolveCardGesture(true, false, false, false))
    }

    @Test
    fun doesNotStealGestureDraggedInFromApp() {
        // 应用手势进行中（按下时不在卡片上）拖进卡片区域：不能接管，
        // 否则应用收不到抬起、停在按下态
        assertEquals(
            CardGesture.NOT_HANDLED,
            resolveCardGesture(true, false, true, true, appGestureActive = true),
        )
    }

    @Test
    fun releasesStuckHoldWhenCardDisappears() {
        // 卡片已收起但手势仍按住（探测清掉提示）：吞掉这一抬，避免漏成应用点击
        assertEquals(CardGesture.CONSUME, resolveCardGesture(false, true, false, false))
    }
}
