package com.mcarplay.app

import com.mcarplay.app.airplay.ModeArbiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重进 CarPlay（`requestUI`）时主音频归属的回归测试。
 *
 * 审查发现：`audioEnabledForSession` 只代表用户选了声音，音频协商失败（无共享密钥、
 * 无投屏授权或全部档位 SETUP 失败）后发送器并不存在；旧实现仍把主音频（resourceID=2）
 * 声明给手机，车机据此停掉自己的音频，而 CarPlay 没有发送端接替——重进后静音或抢走
 * 车机本地音频。
 */
class RequestUiAudioDecisionTest {
    private fun entities(arbiter: ModeArbiter) = arbiter.resourcesPayload().associate { it["resourceID"] to it["entity"] }

    private fun expected(
        screen: ModeArbiter.Entity,
        audio: ModeArbiter.Entity,
    ) = mapOf(
        ModeArbiter.SCREEN to screen.code,
        ModeArbiter.MAIN_AUDIO to audio.code,
    )

    private fun audioResource(
        type: Long,
        constraint: Long,
    ) = mapOf(
        "resourceID" to ModeArbiter.MAIN_AUDIO,
        "transferType" to type,
        "transferPriority" to ModeArbiter.PRIORITY_USER_INITIATED.toLong(),
        "takeConstraint" to constraint,
    )

    @Test
    fun failedAudioNegotiationKeepsMainAudioWithTheHeadUnit() {
        // 用户选了声音，但本轮音频流没有建立：发送器不存在。
        val decision =
            requestUiAudioDecision(
                audioEnabled = true,
                audioStreamRunning = false,
                audioClaimAwaitingReturn = false,
            )
        assertFalse("音频协商失败时不得声明主音频归手机", decision.takeMainAudio)
        assertFalse(decision.retryAfterPendingClaim)

        // 返回车机后重进：主屏回到手机，主音频仍归车机——车机本地音频不被抢走。
        val arbiter = ModeArbiter()
        arbiter.onUserReturnedToCar()
        val request = arbiter.onUserRequestedUi(takeAudio = decision.takeMainAudio)
        assertTrue(request.allowed)
        assertEquals(
            expected(ModeArbiter.Entity.CONTROLLER, ModeArbiter.Entity.ACCESSORY),
            entities(arbiter),
        )
    }

    @Test
    fun runningAudioStreamTakesMainAudioBackOnReentry() {
        // 音频流确实在跑：重进时同时拿回主屏与主音频（0x10021 返回车机后的既有修复）。
        val decision =
            requestUiAudioDecision(
                audioEnabled = true,
                audioStreamRunning = true,
                audioClaimAwaitingReturn = false,
            )
        assertTrue(decision.takeMainAudio)
        assertFalse(decision.retryAfterPendingClaim)

        val arbiter = ModeArbiter()
        arbiter.onUserReturnedToCar()
        arbiter.onUserRequestedUi(takeAudio = decision.takeMainAudio)
        assertEquals(
            expected(ModeArbiter.Entity.CONTROLLER, ModeArbiter.Entity.CONTROLLER),
            entities(arbiter),
        )
    }

    @Test
    fun pendingClaimIsRetriedInsteadOfDeclaredWithoutASender() {
        // SETUP 因车机高约束占用主音频而推迟：requestUI 不声明归属，改为后台重试；
        // 重试由 onAudioStreamStarting 按当时的约束决定（归还后才接管）。
        val decision =
            requestUiAudioDecision(
                audioEnabled = true,
                audioStreamRunning = false,
                audioClaimAwaitingReturn = true,
            )
        assertFalse(decision.takeMainAudio)
        assertTrue(decision.retryAfterPendingClaim)

        val arbiter = ModeArbiter()
        arbiter.onChangeModes(
            mapOf("resources" to listOf(audioResource(ModeArbiter.TRANSFER_TAKE.toLong(), ModeArbiter.CONSTRAINT_NEVER.toLong()))),
        )
        assertFalse(arbiter.onAudioStreamStarting().claimed)
        assertTrue(arbiter.audioClaimAwaitingReturn)

        // 车机归还主音频后才允许重试接管。
        arbiter.onChangeModes(
            mapOf("resources" to listOf(audioResource(ModeArbiter.TRANSFER_UNTAKE.toLong(), ModeArbiter.CONSTRAINT_ANYTIME.toLong()))),
        )
        assertTrue(arbiter.consumeAudioClaimRetry())
        assertTrue(arbiter.onAudioStreamStarting().claimed)
        assertTrue(arbiter.phoneOwnsAudio)
    }

    @Test
    fun audioDisabledNeverTakesOrRetriesMainAudio() {
        val decision =
            requestUiAudioDecision(
                audioEnabled = false,
                audioStreamRunning = false,
                audioClaimAwaitingReturn = true,
            )
        assertFalse(decision.takeMainAudio)
        assertFalse(decision.retryAfterPendingClaim)
    }

    @Test
    fun requestUiAndChangeModesConsumeThePendingClaimOnlyOnce() {
        // requestUI 与 changeModes 都看到 pending claim：先原子消费的一方调度重试，
        // 后到的一方不得再起一条音频流（配合 startAudioChannel 的单飞闸门）。
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(
            mapOf("resources" to listOf(audioResource(ModeArbiter.TRANSFER_TAKE.toLong(), ModeArbiter.CONSTRAINT_NEVER.toLong()))),
        )
        assertFalse(arbiter.onAudioStreamStarting().claimed)
        assertTrue(arbiter.audioClaimAwaitingReturn)
        arbiter.onChangeModes(
            mapOf("resources" to listOf(audioResource(ModeArbiter.TRANSFER_UNTAKE.toLong(), ModeArbiter.CONSTRAINT_ANYTIME.toLong()))),
        )

        assertTrue("requestUI consumes the pending claim", arbiter.consumeAudioClaimRetry())
        assertFalse("changeModes must not schedule a second retry", arbiter.consumeAudioClaimRetry())
    }
}
