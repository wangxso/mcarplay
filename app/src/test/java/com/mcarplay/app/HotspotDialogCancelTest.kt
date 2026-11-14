package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 热点确认弹窗的等待必须随取消立刻退出。
 *
 * 背景（issue #64，0.1.6 保时捷 MIB2+/ALPINE 报告）：车机在会话建立前拆掉 BT ACL 后，
 * 控制层会置 `connectCancelled` 并记 `BT_LINK_CLOSED`；但确认弹窗的等待此前只等
 * `CountDownLatch`（最长 90 秒），确认当前网络路径还会连问 3 次——报告里
 * `WIFI:WIFI_JOIN` 卡到 228 秒，界面上同时留着无人消费的「正在加入车机热点」。
 * 这里钉住「取消优先、尽快返回」，防止改动退回只看超时的等待。
 */
class HotspotDialogCancelTest {
    @Test
    fun answerIsReturnedEvenWhenOtherPollWindowsElapseFirst() {
        // 用户在第 3 个轮询窗应答；此前两窗都没有取消、也没到截止时刻。
        var remainingPolls = 2
        val outcome =
            SessionController.awaitDialogChoice(
                isCancelled = { false },
                deadlineMs = 90_000,
                awaitSignal = { remainingPolls-- <= 0 },
                now = { 0 },
            )
        assertEquals(SessionController.DialogWait.ANSWERED, outcome)
    }

    @Test
    fun cancellationAbortsOnTheFirstPollEvenThoughTheDialogIsStillOpen() {
        // 弹窗仍然开着（永远没有应答），取消必须在下一次轮询立刻返回，而不是等满 90 秒。
        val cancelAfterFirstPoll = AtomicBoolean(false)
        val outcome =
            SessionController.awaitDialogChoice(
                isCancelled = { cancelAfterFirstPoll.get() },
                deadlineMs = 90_000,
                awaitSignal = {
                    // 第一次轮询期间蓝牙链路断开：控制层置 connectCancelled。
                    cancelAfterFirstPoll.set(true)
                    false
                },
                now = { 0 },
            )
        assertEquals(SessionController.DialogWait.CANCELLED, outcome)
    }

    @Test
    fun cancellationWinsOverTimeoutAtTheSameMoment() {
        // 蓝牙拆链恰好发生在截止时刻：必须记「取消」(BT_LINK_CLOSED)，不能记成「用户没应答」。
        val outcome =
            SessionController.awaitDialogChoice(
                isCancelled = { true },
                deadlineMs = 0,
                awaitSignal = { false },
                now = { 0 },
            )
        assertEquals(SessionController.DialogWait.CANCELLED, outcome)
    }

    @Test
    fun unansweredDialogTimesOutInsteadOfBlockingForever() {
        val now = AtomicLong(0)
        val outcome =
            SessionController.awaitDialogChoice(
                isCancelled = { false },
                deadlineMs = 500,
                awaitSignal = { false },
                now = { now.addAndGet(60) },
            )
        assertEquals(SessionController.DialogWait.TIMED_OUT, outcome)
    }

    @Test
    fun cancellationIsCheckedRepeatedlyNotJustOnce() {
        // 取消发生在第二窗：只查第一次的实现会一直等到超时。
        var polls = 0
        val cancelled = AtomicBoolean(false)
        val outcome =
            SessionController.awaitDialogChoice(
                isCancelled = cancelled::get,
                deadlineMs = 90_000,
                awaitSignal = {
                    polls++
                    if (polls == 2) cancelled.set(true)
                    false
                },
                now = { 0 },
            )
        assertEquals(SessionController.DialogWait.CANCELLED, outcome)
        assertTrue("取消检查必须发生在每一轮等待之后", polls >= 2)
    }
}
