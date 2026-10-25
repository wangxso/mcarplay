package com.mcarplay.app.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MFi 挑战长度的按车机持久化记忆。
 *
 * 背景（2026-10-19 奥迪 MMI 3315 实测）：20B 挑战该车机静默 6 秒无应答，32B 才在
 * 0.64 秒回签名。不同接收端偏好相反（DiPlay 只认 32B、MMI 6318 认 20B），所以
 * 记忆必须按车机身份分开存，且冷启动仍要有合理基线。
 */
class MfiLengthMemoryTest {
    private val baselineCar = listOf(20, 32, 16) // 真车系：20B 优先
    private val baselineTestBench = listOf(32, 20, 16) // 测试台：32B 优先

    @Test
    fun receiverKeyIsStableAndFilesystemFriendly() {
        // 0x1D01 的 name + model + manufacturer 拼接（会带空格与大小写差异）
        val a = MfiLengthMemory.receiverKey("audi_mmi_3315 audi 5163 delphi")
        val b = MfiLengthMemory.receiverKey("Audi_MMI_3315  AUDI 5163 DELPHI")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        // 不同车机必须落到不同键（3315 与 6318 不能互相污染记忆）
        assertFalse(a == MfiLengthMemory.receiverKey("audi_mmi_6318 audi 3260 delphi"))
    }

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(baselineCar, MfiLengthMemory.orderAttempts(baselineCar, MfiLengthMemory.Entry()))
        assertEquals(
            baselineTestBench,
            MfiLengthMemory.orderAttempts(baselineTestBench, MfiLengthMemory.Entry()),
        )
    }

    @Test
    fun rememberedSuccessGoesFirst() {
        // MMI 3315 记住了 32B 成功 → 下一轮直接 32B，不再先撞 20B 的 6 秒静默
        val entry = MfiLengthMemory.Entry(succeeded = 32)
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineCar, entry))
        // 测试台场景同理（32B 本来就在最前，顺序不变）
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineTestBench, entry))
    }

    @Test
    fun silentAndRejectedLengthsSinkButStayAsFallback() {
        val entry = MfiLengthMemory.Entry(silent = setOf(20), rejected = setOf(16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        // 成功档位没有；20B 静默、16B 被拒都排到最后，32B 提前
        assertEquals(listOf(32, 20, 16), order)
        // 不丢档位：记忆只影响顺序，不会让某个长度永远不被尝试
        assertEquals(baselineCar.toSet(), order.toSet())
    }

    @Test
    fun allRejectedFallsBackToTryingEverything() {
        // 记忆与现状矛盾（换了固件/同一身份的不同车）：全部被拒过也要照常试，避免永久卡死
        val entry = MfiLengthMemory.Entry(rejected = setOf(20, 32, 16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(baselineCar, order)
    }

    @Test
    fun onlySucceededLengthIsDroppedFromDuplicateCandidates() {
        val entry = MfiLengthMemory.Entry(succeeded = 20)
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(20, order.first())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun succeededAndRejectedContradictionDemotesMemory() {
        // 2026-10-24 DiPlay 实测：迟到应答把 20B 误记成功，随后每轮 20B 被断链。
        // 矛盾态（成功 ∧ 被拒）下 20B 不得再排第一——降级参与正常排序
        val entry = MfiLengthMemory.Entry(succeeded = 20, rejected = setOf(20))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals("被硬拒绝的档位不得再排第一", 32, order.first())
        assertTrue("矛盾档位仍在候选内（固件可能变）", 20 in order)
    }

    @Test
    fun describeShowsWhatWasRemembered() {
        assertEquals("无", MfiLengthMemory.describe(MfiLengthMemory.Entry()))
        val text =
            MfiLengthMemory.describe(
                MfiLengthMemory.Entry(succeeded = 32, silent = setOf(20), rejected = setOf(16)),
            )
        assertTrue(text.contains("成功=32B"))
        assertTrue(text.contains("静默=20B"))
        assertTrue(text.contains("被拒=16B"))
    }
}
