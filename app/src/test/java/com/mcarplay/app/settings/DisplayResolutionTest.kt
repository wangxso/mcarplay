package com.mcarplay.app.settings

import com.mcarplay.app.carlife.CarLifeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayResolutionTest {
    @Test
    fun legacyInstallWithoutModeFallsBackToFollowCarAndKeepsScale() {
        val stored = DisplayResolution.fromStorage(null, 75, 0, 0)
        assertEquals(ResolutionMode.FOLLOW_CAR, stored.mode)
        assertEquals(75, stored.scalePercent)
        assertEquals(1280 to 720, stored.customWidth to stored.customHeight)
    }

    @Test
    fun unknownModeFallsBackToFollowCar() {
        assertEquals(ResolutionMode.FOLLOW_CAR, ResolutionMode.fromStorage("future-mode"))
        assertEquals(ResolutionMode.FOLLOW_CAR, ResolutionMode.fromStorage(null))
        assertEquals(ResolutionMode.FULL_HD_1080, ResolutionMode.fromStorage("1080p"))
    }

    @Test
    fun storedValuesAreSanitizedWithoutLosingValidSettings() {
        val stored = DisplayResolution.fromStorage("custom", 999, 1600, 900)
        assertEquals(ResolutionMode.CUSTOM, stored.mode)
        assertEquals(DisplayPrefs.SCALE_MAX, stored.scalePercent)
        assertEquals(1600 to 900, stored.customWidth to stored.customHeight)

        val corrupted = DisplayResolution.fromStorage("custom", 10, 9999, 0)
        assertEquals(DisplayPrefs.SCALE_MIN, corrupted.scalePercent)
        assertEquals(1280 to 720, corrupted.customWidth to corrupted.customHeight)
    }

    @Test
    fun customInputRejectsBlankNonNumericZeroAndNegativeValues() {
        assertEquals(
            DisplayResolution.CustomSizeError.BLANK,
            (DisplayResolution.parseCustomSize("", "720") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.BLANK,
            (DisplayResolution.parseCustomSize("1280", "  ") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.NOT_A_NUMBER,
            (DisplayResolution.parseCustomSize("12a0", "720") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.NOT_A_NUMBER,
            (DisplayResolution.parseCustomSize("1280", "720.5") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.NOT_A_NUMBER,
            (DisplayResolution.parseCustomSize("0", "720") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.NOT_A_NUMBER,
            (DisplayResolution.parseCustomSize("1280", "-1") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
    }

    @Test
    fun customInputEnforcesMinimumAndMaximum() {
        assertEquals(
            DisplayResolution.CustomSizeError.TOO_SMALL,
            (DisplayResolution.parseCustomSize("319", "180") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.TOO_SMALL,
            (DisplayResolution.parseCustomSize("320", "179") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.TOO_LARGE,
            (DisplayResolution.parseCustomSize("1921", "1080") as DisplayResolution.CustomSizeResult.Invalid).error,
        )
        assertEquals(
            DisplayResolution.CustomSizeError.TOO_LARGE,
            (DisplayResolution.parseCustomSize("1920", "1081") as DisplayResolution.CustomSizeResult.Invalid).error,
        )

        val minimum = DisplayResolution.parseCustomSize("320", "180") as DisplayResolution.CustomSizeResult.Valid
        assertEquals(320 to 180, minimum.width to minimum.height)
        assertFalse(minimum.adjusted)

        val maximum = DisplayResolution.parseCustomSize("1920", "1080") as DisplayResolution.CustomSizeResult.Valid
        assertEquals(1920 to 1080, maximum.width to maximum.height)
        assertFalse(maximum.adjusted)
    }

    @Test
    fun customInputAlignsOddDimensionsDownToEven() {
        val result = DisplayResolution.parseCustomSize("1281", "721") as DisplayResolution.CustomSizeResult.Valid
        assertEquals(1280 to 720, result.width to result.height)
        assertTrue(result.adjusted)
    }

    @Test
    fun followCarModeKeepsLegacyPercentageAndEvenAlignment() {
        val plan =
            DisplayResolution.plan(
                displayWidth = 1412,
                displayHeight = 654,
                mode = ResolutionMode.FOLLOW_CAR,
                scalePercent = 100,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 30,
            )
        assertEquals(1412 to 654, plan.streamWidth to plan.streamHeight)
        assertEquals(1412 to 654, plan.carWithRequestWidth to plan.carWithRequestHeight)
        assertFalse(plan.level4FrameClamped)
        assertFalse(plan.level4FpsLimited)

        val odd =
            DisplayResolution.plan(
                displayWidth = 1413,
                displayHeight = 655,
                mode = ResolutionMode.FOLLOW_CAR,
                scalePercent = 100,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 30,
            )
        assertEquals(1412 to 654, odd.streamWidth to odd.streamHeight)

        val half =
            DisplayResolution.plan(
                displayWidth = 1413,
                displayHeight = 655,
                mode = ResolutionMode.FOLLOW_CAR,
                scalePercent = 50,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 30,
            )
        assertEquals(706 to 326, half.streamWidth to half.streamHeight)
    }

    @Test
    fun fixedPresetsIgnorePercentageAndRequestExactSize() {
        val p720 =
            DisplayResolution.plan(
                displayWidth = 1412,
                displayHeight = 654,
                mode = ResolutionMode.HD_720,
                scalePercent = 160,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 60,
            )
        assertEquals(1280 to 720, p720.streamWidth to p720.streamHeight)
        assertEquals(1280 to 720, p720.carWithRequestWidth to p720.carWithRequestHeight)
        assertEquals(60, p720.targetFps)
        assertFalse(p720.level4FpsLimited)

        val p1080 =
            DisplayResolution.plan(
                displayWidth = 1412,
                displayHeight = 654,
                mode = ResolutionMode.FULL_HD_1080,
                scalePercent = 30,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 30,
            )
        assertEquals(1920 to 1080, p1080.streamWidth to p1080.streamHeight)
        assertEquals(1920 to 1080, p1080.carWithRequestWidth to p1080.carWithRequestHeight)
        assertEquals(30, p1080.targetFps)
        assertFalse(p1080.level4FpsLimited)
    }

    @Test
    fun fullHdAtSixtyFpsIsEncodedAtThirtyFpsForLevel4() {
        val plan =
            DisplayResolution.plan(
                displayWidth = 1920,
                displayHeight = 1080,
                mode = ResolutionMode.FULL_HD_1080,
                scalePercent = 100,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 60,
            )
        assertEquals(60, plan.requestedFps)
        assertEquals(30, plan.targetFps)
        assertTrue(plan.level4FpsLimited)
        assertFalse(plan.level4FrameClamped)
    }

    @Test
    fun oversizedCustomInputFallsBackToDefaultInsteadOfReachingEncoder() {
        val plan =
            DisplayResolution.plan(
                displayWidth = 1920,
                displayHeight = 1080,
                mode = ResolutionMode.CUSTOM,
                scalePercent = 100,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 30,
            )
        assertEquals(1280 to 720, plan.streamWidth to plan.streamHeight)
        assertEquals(1280 to 720, plan.carWithRequestWidth to plan.carWithRequestHeight)

        val custom =
            DisplayResolution.plan(
                displayWidth = 1920,
                displayHeight = 1080,
                mode = ResolutionMode.CUSTOM,
                scalePercent = 100,
                customWidth = 1601,
                customHeight = 901,
                requestedFps = 30,
            )
        assertEquals(1600 to 900, custom.streamWidth to custom.streamHeight)
        assertEquals(1600 to 900, custom.carWithRequestWidth to custom.carWithRequestHeight)
    }

    @Test
    fun followCarCanvasIsClampedIntoLevel4FrameLimit() {
        // 2800×1840 是仓库测试里的车机屏幕尺寸；100% 跟随会远超 level 4.0 单帧上限。
        val plan =
            DisplayResolution.plan(
                displayWidth = 2800,
                displayHeight = 1840,
                mode = ResolutionMode.FOLLOW_CAR,
                scalePercent = 100,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 30,
            )
        assertTrue(plan.level4FrameClamped)
        assertTrue(plan.streamWidth % 2 == 0 && plan.streamHeight % 2 == 0)
        assertTrue(
            DisplayResolution.macroblocks(plan.streamWidth, plan.streamHeight) <=
                DisplayResolution.LEVEL4_MAX_FRAME_MACROBLOCKS,
        )
        // 跟随车机仍按接收端屏幕比例请求（旧行为），不会把钳制后的流尺寸当请求。
        assertEquals(
            CarLifeProtocol.videoRequestFor(2800, 1840),
            plan.carWithRequestWidth to plan.carWithRequestHeight,
        )
    }

    @Test
    fun unknownDisplayFallsBackToMinimumCanvas() {
        val plan =
            DisplayResolution.plan(
                displayWidth = 0,
                displayHeight = 0,
                mode = ResolutionMode.FOLLOW_CAR,
                scalePercent = 100,
                customWidth = 0,
                customHeight = 0,
                requestedFps = 30,
            )
        assertEquals(320 to 180, plan.streamWidth to plan.streamHeight)
        assertEquals(1280 to 720, plan.carWithRequestWidth to plan.carWithRequestHeight)
    }

    @Test
    fun level4MacroblockAndFrameRateLimitsAreExplicit() {
        assertEquals(8160, DisplayResolution.macroblocks(1920, 1080))
        assertEquals(30, DisplayResolution.level4MaxFps(1920, 1080))
        assertEquals(68, DisplayResolution.level4MaxFps(1280, 720))
        assertEquals(43, DisplayResolution.level4MaxFps(1600, 900))
        assertNull(DisplayResolution.sanitizeCustom(100, 100))
        assertEquals(1280 to 720, DisplayResolution.sanitizeCustom(1281, 721))
    }

    @Test
    fun heroShowsActualPassthroughOutputWhenCarWithIgnoresRequest() {
        // 请求 1280×720；透传时发送端画布被改成 CarWith 源 1920×1080。
        val size =
            DisplayResolution.describeOutput(
                requestWidth = 1280,
                requestHeight = 720,
                outputWidth = 1920,
                outputHeight = 1080,
                source = 1920 to 1080,
            )
        assertEquals(DisplayResolution.ActiveSizeKind.ACTUAL_WITH_REQUEST, size.kind)
        assertEquals(1920 to 1080, size.outputWidth to size.outputHeight)
        assertEquals(1280 to 720, size.otherWidth to size.otherHeight)
    }

    @Test
    fun heroShowsActualOutputAndSourceWhileTranscoding() {
        // 转码：输出是计划流 1280×720，源仍是 CarWith 1920×1080。
        val size =
            DisplayResolution.describeOutput(
                requestWidth = 1280,
                requestHeight = 720,
                outputWidth = 1280,
                outputHeight = 720,
                source = 1920 to 1080,
            )
        assertEquals(DisplayResolution.ActiveSizeKind.ACTUAL_WITH_SOURCE, size.kind)
        assertEquals(1280 to 720, size.outputWidth to size.outputHeight)
        assertEquals(1920 to 1080, size.otherWidth to size.otherHeight)
    }

    @Test
    fun heroReportsActualSizeWhenCarWithAdoptsTheRequest() {
        val size =
            DisplayResolution.describeOutput(
                requestWidth = 1280,
                requestHeight = 720,
                outputWidth = 1280,
                outputHeight = 720,
                source = 1280 to 720,
            )
        assertEquals(DisplayResolution.ActiveSizeKind.ACTUAL, size.kind)
        assertEquals(1280 to 720, size.outputWidth to size.outputHeight)
    }

    @Test
    fun heroNeverInventsActualSizeBeforeSourceIsKnown() {
        val pending =
            DisplayResolution.describeOutput(
                requestWidth = 1280,
                requestHeight = 720,
                outputWidth = 1280,
                outputHeight = 720,
                source = null,
            )
        assertEquals(DisplayResolution.ActiveSizeKind.SOURCE_PENDING, pending.kind)

        val unknown =
            DisplayResolution.describeOutput(
                requestWidth = 1280,
                requestHeight = 720,
                outputWidth = 0,
                outputHeight = 0,
                source = null,
            )
        // 已发请求但既无输出也无协商值：显示为请求，不冒充实际输出。
        assertEquals(DisplayResolution.ActiveSizeKind.REQUESTED, unknown.kind)
        assertEquals(1280 to 720, unknown.otherWidth to unknown.otherHeight)
        assertEquals(0, unknown.outputWidth)
    }

    @Test
    fun heroDoesNotTreatPlannedCanvasAsOutputWhenOnlyNegotiatedSizeExists() {
        // 透传：VIDEO_INIT_DONE 已到（源 1920×1080），但首个 SPS 帧还没进发送端。
        // 发送端构造画布仍是计划值 1280×720，不能当成实际输出。
        val negotiated =
            DisplayResolution.describeOutput(
                requestWidth = 1280,
                requestHeight = 720,
                outputWidth = 0,
                outputHeight = 0,
                source = 1920 to 1080,
            )
        assertEquals(DisplayResolution.ActiveSizeKind.SOURCE_NEGOTIATED, negotiated.kind)
        assertEquals(0, negotiated.outputWidth)
        assertEquals(1920 to 1080, negotiated.otherWidth to negotiated.otherHeight)

        // 既没有协商值也没有请求：完全未知。
        val nothing =
            DisplayResolution.describeOutput(
                requestWidth = 0,
                requestHeight = 0,
                outputWidth = 0,
                outputHeight = 0,
                source = null,
            )
        assertEquals(DisplayResolution.ActiveSizeKind.UNKNOWN, nothing.kind)
    }
}
