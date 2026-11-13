package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WifiPromptBrokerTest {
    private val broker = WifiPromptBroker<String>()

    @Test
    fun concurrentIdenticalQuestionSharesTheOwnersAnswer() {
        val owner = broker.claim("use-current") as WifiPromptBroker.Claim.Owner
        val follower = broker.claim("use-current")
        assertTrue(follower is WifiPromptBroker.Claim.Follower)
        assertSame(owner.future, (follower as WifiPromptBroker.Claim.Follower).future)

        broker.finish(owner, answer = true, cache = true)

        assertEquals(true, broker.await(follower.future, 1_000))
        // 之后到达的相同问题也不会再弹第二次。
        assertEquals(WifiPromptBroker.Claim.Answered(true), broker.claim("use-current"))
    }

    @Test
    fun differentQuestionsAreNotMerged() {
        val first = broker.claim("use-current")
        val second = broker.claim("forget-saved-network")
        assertTrue(first is WifiPromptBroker.Claim.Owner)
        assertTrue(second is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun retryCanBypassTheCachedAnswer() {
        broker.finish(broker.claim("use-current") as WifiPromptBroker.Claim.Owner, answer = true, cache = true)
        val retry = broker.claim("use-current", allowCachedAnswer = false)
        assertTrue(retry is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun unansweredPromptIsNotRememberedAsANo() {
        val owner = broker.claim("use-current") as WifiPromptBroker.Claim.Owner
        broker.finish(owner, answer = null, cache = false)
        // UI 不在前台等「无人应答」不能变成用户答案，下一次仍要询问。
        assertTrue(broker.claim("use-current") is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun ownerTimeoutReleasesIdenticalWaiters() {
        val owner = broker.claim("use-current") as WifiPromptBroker.Claim.Owner
        val follower = broker.claim("use-current") as WifiPromptBroker.Claim.Follower
        val answered = CountDownLatch(1)
        var followerAnswer: Boolean? = true
        val waiter =
            Thread {
                followerAnswer = broker.await(follower.future, 5_000)
                answered.countDown()
            }
        waiter.start()

        broker.timeout(owner)

        assertTrue(answered.await(5, TimeUnit.SECONDS))
        assertNull(followerAnswer)
        // 超时也算本轮结束：下一次同类问题重新询问，而不是永远挂起。
        assertTrue(broker.claim("use-current") is WifiPromptBroker.Claim.Owner)
        waiter.join(5_000)
    }

    @Test
    fun clearForgetsAnswersFromThePreviousRound() {
        broker.finish(broker.claim("use-current") as WifiPromptBroker.Claim.Owner, answer = false, cache = true)
        broker.clear()
        assertTrue(broker.claim("use-current") is WifiPromptBroker.Claim.Owner)
    }
}
