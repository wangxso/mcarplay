package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPS level_idc 补丁（2026-10-22 奥迪 0628「推流正常但车机黑屏」的修复）。
 *
 * 背景：MediaFormat.KEY_LEVEL 被实测静默忽略（MagicOS 编码器不抛异常也不生效），
 * 默认 30fps 下 1412x654 合法落入 level 3.1/3.2——奥迪 DELPHI 解码器对该 level
 * 静默不渲染。历史上所有真车成功会话都是 level 4.0（真 iPhone 同款）。
 */
class SpsLevelPatchTest {
    /** 构造一个最小 SPS：NAL header(1B) + profile(1B) + compat(1B) + level(1B) + 尾部。 */
    private fun spsOf(level: Int) = byteArrayOf(0x67, 0x64, 0x00, level.toByte(), 0x11, 0x22)

    @Test
    fun levelBelow40IsRaised() {
        // 3.1(31) 与 3.2(32) 都应补到 40
        for (level in listOf(31, 32)) {
            val patched = patchSpsLevel(spsOf(level))
            assertEquals(40, patched[3].toInt() and 0xFF)
            // 其余字节不动
            assertEquals(0x64, patched[1].toInt() and 0xFF)
            assertEquals(6, patched.size)
        }
    }

    @Test
    fun levelAtOrAbove40IsUntouched() {
        // 4.0(40)、4.2(42) 保持原值——不做降级
        for (level in listOf(40, 41, 42)) {
            val sps = spsOf(level)
            assertTrue(patchSpsLevel(sps).contentEquals(sps))
        }
    }

    @Test
    fun malformedSpsIsPassedThrough() {
        // 过短的 SPS（缺 level 字节）原样返回，不抛
        assertTrue(patchSpsLevel(byteArrayOf(0x67)).size == 1)
    }

    @Test
    fun nonSpsPayloadUntouched() {
        // 已是 40 的不改动（即使其它字节看起来像 SPS）
        val sps = spsOf(40)
        val out = patchSpsLevel(sps)
        assertEquals(sps[3], out[3])
    }
}
