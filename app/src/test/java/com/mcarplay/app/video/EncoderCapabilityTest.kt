package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码能力只读核对（issue #72 任务 D 的安全子集）。
 *
 * 这些规则**只用于记录**：任何「未知 / 读取失败 / 非精确对齐」都不得变成提前失败。
 * 单测把这条边界钉住，避免后续有人把 [EncoderCapabilityCheck.hardProblems] 之外的
 * 差异误改成 configure 前的拒绝条件。
 */
class EncoderCapabilityTest {
    private fun capability(
        name: String = "c2.android.avc.encoder",
        surfaceInput: Boolean? = true,
        widthAlignment: Int? = 2,
        heightAlignment: Int? = 2,
        minWidth: Int? = 128,
        maxWidth: Int? = 4096,
        minHeight: Int? = 128,
        maxHeight: Int? = 4096,
        maxFrameRate: Double? = 60.0,
        profileLevels: List<Pair<Int, Int>>? = listOf(1 to 2048, 2 to 4096, 8 to 8192),
    ) = EncoderCapability(
        name = name,
        surfaceInput = surfaceInput,
        widthAlignment = widthAlignment,
        heightAlignment = heightAlignment,
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
        maxFrameRate = maxFrameRate,
        profileLevels = profileLevels,
    )

    private fun assess(
        capability: EncoderCapability?,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        baselineProfile: Boolean = true,
    ) = EncoderCapabilityRules.assess(
        capability = capability,
        width = width,
        height = height,
        fps = fps,
        baselineProfile = baselineProfile,
        level4 = true,
    )

    @Test
    fun declaredSupportedSizeAndRateAreReportedWithoutProblems() {
        val check = assess(capability())
        assertEquals(CapabilityState.SUPPORTED, check.size)
        assertEquals(CapabilityState.SUPPORTED, check.frameRate)
        assertEquals(CapabilityState.SUPPORTED, check.surfaceInput)
        assertEquals(CapabilityState.SUPPORTED, check.baseline)
        assertEquals(CapabilityState.SUPPORTED, check.level4)
        assertTrue(check.problems.isEmpty())
        assertTrue(check.hardProblems.isEmpty())
        assertFalse("能力表内的候选不应被当成差异", check.declaredMismatch)
        assertTrue(check.summary.contains("1280x720@30"))
        assertTrue("日志要能看出核对的是哪个编码器", check.summary.contains("c2.android.avc.encoder"))
    }

    @Test
    fun oddDimensionsAreTheOnlyHardProblem() {
        val check = assess(capability(), width = 1281, height = 721)
        assertEquals(CapabilityState.UNSUPPORTED, check.size)
        assertEquals(listOf("尺寸 1281×721 含奇数边（H.264 4:2:0 要求偶数）"), check.hardProblems)
        assertTrue(check.problems.any { it.contains("奇数边") })
    }

    @Test
    fun declaredRangeAndAlignmentMismatchAreRecordedButNeverHardProblems() {
        // 非精确对齐：1272 不是声明对齐 16 的倍数。按对齐向上取整会改掉协商尺寸，所以只记录。
        val misaligned = assess(capability(widthAlignment = 16, heightAlignment = 16), width = 1272, height = 720)
        assertEquals(CapabilityState.UNSUPPORTED, misaligned.size)
        assertTrue(misaligned.problems.any { it.contains("对齐") })
        assertTrue("对齐差异不得成为失败依据", misaligned.hardProblems.isEmpty())

        val outOfRange = assess(capability(maxWidth = 1024, maxHeight = 1024), width = 1280, height = 720)
        assertEquals(CapabilityState.UNSUPPORTED, outOfRange.size)
        assertTrue(outOfRange.problems.any { it.contains("宽度 1280 超出") })
        assertTrue(outOfRange.hardProblems.isEmpty())
    }

    @Test
    fun unknownCapabilityIsNotTreatedAsUnsupported() {
        val check = assess(null)
        assertEquals(CapabilityState.UNKNOWN, check.size)
        assertEquals(CapabilityState.UNKNOWN, check.frameRate)
        assertEquals(CapabilityState.UNKNOWN, check.surfaceInput)
        assertEquals(CapabilityState.UNKNOWN, check.baseline)
        assertEquals(CapabilityState.UNKNOWN, check.level4)
        assertEquals("能力未知 ← 候选 1280x720@30：尺寸 未知；帧率 未知；Surface 输入 未知；Baseline 未知（仅记录，不改变 configure）", check.summary)
    }

    @Test
    fun partiallyDeclaredCapabilityStaysUnknownForTheMissingDimensions() {
        val check = assess(capability(minWidth = null, maxWidth = null, widthAlignment = null))
        // 高度有声明但宽度未知：不能因为一半信息就断言「支持」。
        assertEquals(CapabilityState.UNKNOWN, check.size)
        assertEquals(CapabilityState.SUPPORTED, check.frameRate)
    }

    @Test
    fun anomalousDeclaredFrameRateIsUnknownRatherThanZeroSupport() {
        assertEquals(CapabilityState.UNKNOWN, assess(capability(maxFrameRate = 0.0)).frameRate)
        assertEquals(CapabilityState.UNKNOWN, assess(capability(maxFrameRate = -1.0)).frameRate)
        assertEquals(CapabilityState.UNKNOWN, assess(capability(maxFrameRate = Double.NaN)).frameRate)
        assertEquals(CapabilityState.UNKNOWN, assess(capability(maxFrameRate = Double.POSITIVE_INFINITY)).frameRate)
    }

    @Test
    fun frameRateAboveDeclaredMaxIsRecordedAsDifference() {
        val check = assess(capability(maxFrameRate = 30.0), fps = 60)
        assertEquals(CapabilityState.UNSUPPORTED, check.frameRate)
        assertTrue(check.problems.any { it.contains("帧率 60 超过编码器声明上限 30") })
        assertTrue(check.hardProblems.isEmpty())
        // 小数上限按一位小数记录，固定 Locale.ROOT，避免区域设置把小数点写成逗号。
        assertTrue(assess(capability(maxFrameRate = 59.94), fps = 60).problems.any { it.contains("59.9") })
        assertTrue(assess(capability(maxFrameRate = 60.0), fps = 30).summary.contains("声明上限 60"))
    }

    @Test
    fun surfaceInputMustBeDeclaredForTheTranscodePath() {
        val check = assess(capability(surfaceInput = false))
        assertEquals(CapabilityState.UNSUPPORTED, check.surfaceInput)
        assertTrue(check.problems.any { it.contains("COLOR_FormatSurface") })
        assertEquals(CapabilityState.UNKNOWN, assess(capability(surfaceInput = null)).surfaceInput)
    }

    @Test
    fun baselineProfileIsOnlyRequestedByTheTranscodePath() {
        val local = assess(capability(), baselineProfile = false)
        assertEquals(CapabilityState.NOT_REQUESTED, local.baseline)
        assertEquals("本地画面源不请求 profile，不应产生 profile 差异", CapabilityState.NOT_REQUESTED, local.level4)
        assertTrue(local.problems.isEmpty())

        val highOnly = assess(capability(profileLevels = listOf(8 to 8192)))
        assertEquals(CapabilityState.UNSUPPORTED, highOnly.baseline)
        assertTrue(highOnly.problems.any { it.contains("Baseline profile") })
        assertTrue(highOnly.hardProblems.isEmpty())
    }

    @Test
    fun levelFourIsJudgedOnTheSameProfileLevelPair() {
        // Baseline 只声明到 level 3.2：SPS 仍会补到 4.0，但能力表并不支持该组合，记差异即可。
        val lowLevel = assess(capability(profileLevels = listOf(1 to 1024)))
        assertEquals(CapabilityState.SUPPORTED, lowLevel.baseline)
        assertEquals(CapabilityState.UNSUPPORTED, lowLevel.level4)
        assertTrue(lowLevel.problems.any { it.contains("Baseline@≥4.0") })
        assertTrue(lowLevel.hardProblems.isEmpty())

        // High@4.2 不能当作 Baseline 支持：判断必须看同一对。
        val wrongProfile = assess(capability(profileLevels = listOf(8 to 8192)))
        assertEquals(CapabilityState.UNSUPPORTED, wrongProfile.baseline)
        assertEquals(CapabilityState.UNSUPPORTED, wrongProfile.level4)

        assertEquals(CapabilityState.SUPPORTED, assess(capability(profileLevels = listOf(1 to 2048))).level4)
        assertEquals(CapabilityState.UNKNOWN, assess(capability(profileLevels = null)).level4)
        assertEquals(CapabilityState.UNKNOWN, assess(capability(profileLevels = emptyList())).level4)
    }

    @Test
    fun invalidRequestedDimensionsAreHardProblems() {
        val check = assess(capability(), width = 0, height = 720)
        assertEquals(CapabilityState.UNSUPPORTED, check.size)
        assertTrue(check.hardProblems.any { it.contains("尺寸 0×720 无效") })
    }
}
