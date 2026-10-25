package com.mcarplay.app.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UserService 绑定重发策略（2026-10-22 Redmi K60E「触控注入必然死锁」的回归钉子）。
 *
 * 实测现场：视频正常推流、触摸事件到达手机，但注入从第一次起就失败，失败计数涨到
 * 1700+；日志每 2~3 秒刷一次「绑定卡死状态已解除（binding 超时未回调），立即重试」。
 *
 * 根因是判定窗口（1.5s）与正常回调延迟同量级，且判死后会 unbindUserService(remove=true)
 * ——该参数会**杀掉远端服务进程**。于是「发起 → 1.5s 判死 → 杀进程 → 重新 spawn →
 * 回调又晚到」形成自持抖动，永不收敛。
 */
class BindRebindPolicyTest {
    private val timeout = 8_000L

    @Test
    fun idleStateIsReadyToRebind() {
        // 当前没有进行中的绑定 → 可以直接发起
        assertTrue(shouldRebind(binding = false, waitedMs = 0, stuckTimeoutMs = timeout))
    }

    @Test
    fun freshlyIssuedBindingKeepsWaiting() {
        // 刚发起（含旧实现会误判的 1.5s 点）都要继续等——这正是死锁的根源
        listOf(0L, 100L, 1_500L, 1_501L, 3_000L, 7_999L).forEach { waited ->
            assertFalse(
                "等待 ${waited}ms 不应判卡死（旧实现在 1.5s 就判死并杀服务进程）",
                shouldRebind(binding = true, waitedMs = waited, stuckTimeoutMs = timeout),
            )
        }
    }

    @Test
    fun onlyGenuinelyStuckBindingIsRebound() {
        assertTrue(shouldRebind(binding = true, waitedMs = timeout, stuckTimeoutMs = timeout))
        assertTrue(shouldRebind(binding = true, waitedMs = timeout * 3, stuckTimeoutMs = timeout))
    }

    @Test
    fun zeroBindingSinceIsTreatedAsJustIssued() {
        // bindingSince 未记录（0）时不应当成「已等很久」而立刻重发
        assertFalse(shouldRebind(binding = true, waitedMs = 0, stuckTimeoutMs = timeout))
    }
}
