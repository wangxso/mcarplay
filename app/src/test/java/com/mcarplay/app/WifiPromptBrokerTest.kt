package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WifiPromptBrokerTest {
    private data class Token(
        val ssid: String,
    )

    private fun broker() = WifiPromptBroker<String, Token> { a, b -> a.ssid == b.ssid }

    @Test
    fun concurrentIdenticalQuestionSharesTheOwnersAnswer() {
        val broker = broker()
        val car = Token("CarAP")
        val owner = broker.claim("use-current", car) as WifiPromptBroker.Claim.Owner
        val follower = broker.claim("use-current", car)

        assertTrue(follower is WifiPromptBroker.Claim.Follower)
        val followerClaim = follower as WifiPromptBroker.Claim.Follower<Token>
        assertSame(owner.future, followerClaim.future)
        assertTrue(followerClaim.matches)

        broker.finish(owner, answer = true, cache = true)

        assertEquals(true, broker.await(follower.future, 1_000))
        // 之后到达的相同问题、同一张网络也不会再弹第二次。
        assertEquals(WifiPromptBroker.Claim.Answered<Token>(true), broker.claim("use-current", car))
    }

    @Test
    fun answerForOneNetworkIsNotReusedAfterThePhoneSwitches() {
        // P1：答案只按弹窗文案缓存时，A 的确认会被静默套到 B 上。
        val broker = broker()
        broker.finish(broker.claim("use-current", Token("CarAP")) as WifiPromptBroker.Claim.Owner, true, cache = true)

        val switched = broker.claim("use-current", Token("HomeAP"))
        assertTrue("换网后必须重新确认", switched is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun aDialogShownForAnotherNetworkIsNotJoined() {
        // P1：为 A 展示中的弹窗不能被 B 的请求当成自己的答案。
        val broker = broker()
        val owner = broker.claim("use-current", Token("CarAP")) as WifiPromptBroker.Claim.Owner
        val follower = broker.claim("use-current", Token("HomeAP")) as WifiPromptBroker.Claim.Follower

        assertFalse(follower.matches)
        broker.finish(owner, answer = true, cache = true)
        assertEquals(true, broker.await(follower.future, 1_000))
        // 调用方看到 matches=false 会丢弃该答案；A 的答案也没有被记到 B 名下。
        assertTrue(broker.claim("use-current", Token("HomeAP")) is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun differentQuestionsAreNotMerged() {
        val broker = broker()
        val car = Token("CarAP")
        assertTrue(broker.claim("use-current", car) is WifiPromptBroker.Claim.Owner)
        assertTrue(broker.claim("forget-saved-network", car) is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun retryAfterASwitchAsksAgainInsteadOfReusingTheOldAnswer() {
        val broker = broker()
        broker.finish(broker.claim("use-current", Token("CarAP")) as WifiPromptBroker.Claim.Owner, true, cache = true)
        assertTrue(broker.claim("use-current", Token("CarAP")) is WifiPromptBroker.Claim.Answered)
        assertTrue(broker.claim("use-current", Token("OtherAP")) is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun unansweredPromptIsNotRememberedAsANo() {
        val broker = broker()
        val car = Token("CarAP")
        broker.finish(broker.claim("use-current", car) as WifiPromptBroker.Claim.Owner, answer = null, cache = false)
        // UI 不在前台等「无人应答」不能变成用户答案，下一次仍要询问。
        assertTrue(broker.claim("use-current", car) is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun staleCallbackCannotWriteIntoTheCurrentRound() {
        // P2：clear() 之后旧弹窗才被点掉，不能把上一轮的答案写进当前记录。
        val broker = broker()
        val car = Token("CarAP")
        val stale = broker.claim("use-current", car) as WifiPromptBroker.Claim.Owner
        broker.clear()

        val fresh = broker.claim("use-current", car) as WifiPromptBroker.Claim.Owner
        broker.finish(stale, answer = true, cache = true)

        assertFalse("过期回调不能兑现新的在途请求", fresh.future.isDone)
        // 新一轮必须重新询问，且旧答案没有落账。
        broker.timeout(fresh)
        assertTrue(broker.claim("use-current", car) is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun staleCallbackAfterTimeoutCannotWriteIntoTheCurrentRound() {
        // P2：超时作废后旧弹窗的答案也不能落账。
        val broker = broker()
        val car = Token("CarAP")
        val stale = broker.claim("use-current", car) as WifiPromptBroker.Claim.Owner
        broker.timeout(stale)
        broker.finish(stale, answer = true, cache = true)

        assertTrue(broker.claim("use-current", car) is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun ownerTimeoutReleasesIdenticalWaiters() {
        val broker = broker()
        val car = Token("CarAP")
        val owner = broker.claim("use-current", car) as WifiPromptBroker.Claim.Owner
        val follower = broker.claim("use-current", car) as WifiPromptBroker.Claim.Follower
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
        assertTrue(broker.claim("use-current", car) is WifiPromptBroker.Claim.Owner)
        waiter.join(5_000)
    }

    @Test
    fun clearReleasesBlockedWaitersAndInvalidatesLateCallbacks() {
        val broker = broker()
        val car = Token("CarAP")
        val owner = broker.claim("use-current", car) as WifiPromptBroker.Claim.Owner
        val follower = broker.claim("use-current", car) as WifiPromptBroker.Claim.Follower

        broker.clear()

        // 取消后仍开着的弹窗不能把调用线程卡满 90 秒：等待者立刻收到「没有答案」。
        assertTrue(follower.future.isDone)
        assertNull(broker.await(follower.future, 1_000))
        assertTrue(owner.future.isDone)
        // 迟到的点击仍不能落账，且新一轮必须重新询问。
        broker.finish(owner, answer = true, cache = true)
        assertTrue(broker.claim("use-current", car) is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun awaitStopsPromptlyWhenTheRoundIsCancelled() {
        val broker = broker()
        val owner = broker.claim("use-current", Token("CarAP")) as WifiPromptBroker.Claim.Owner
        val cancelled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        var answer: Boolean? = true
        val waiter =
            Thread {
                answer = broker.await(owner.future, 30_000) { cancelled.get() }
            }
        waiter.start()
        Thread.sleep(200)
        cancelled.set(true)
        waiter.join(3_000)
        assertFalse("取消后必须立即返回，而不是等满超时", waiter.isAlive)
        assertNull(answer)
    }

    @Test
    fun retryIsSkippedWhenTheRoundChanged() {
        // 换网后的重试不得为已结束的轮次补弹旧框：generation 变化后调用方会直接放弃。
        val broker = broker()
        val round = broker.currentGeneration()
        broker.clear()
        assertTrue(broker.currentGeneration() != round)
    }

    @Test
    fun concurrentSnapshotLessRequestsKeepTheirOwnDialogsAndAnswers() {
        // P2：两条链路都可能同时问同一个「没有网络身份」的问题，不能互相覆盖。
        val broker = broker()
        val first = broker.claim("use-current", null) as WifiPromptBroker.Claim.Owner
        val second = broker.claim("use-current", null) as WifiPromptBroker.Claim.Owner

        broker.finish(first, answer = true, cache = true)
        broker.finish(second, answer = false, cache = true)

        assertEquals("先展示的弹窗答案必须生效", true, broker.await(first.future, 1_000))
        assertEquals(false, broker.await(second.future, 1_000))
    }

    @Test
    fun aRequestForAnotherNetworkCanClaimAfterTheOldFlightEnds() {
        // P2：为 A 展示中的弹窗不能让 B 的请求永远拿不到答案；旧请求收尾后 B 能重新提问。
        val broker = broker()
        val ownerA = broker.claim("use-current", Token("CarAP")) as WifiPromptBroker.Claim.Owner
        val followerB = broker.claim("use-current", Token("HomeAP")) as WifiPromptBroker.Claim.Follower
        assertFalse(followerB.matches)

        broker.finish(ownerA, answer = true, cache = true)
        assertEquals(true, broker.await(followerB.future, 1_000))

        val retryB = broker.claim("use-current", Token("HomeAP"))
        assertTrue("换网后必须重新询问 B", retryB is WifiPromptBroker.Claim.Owner)
    }

    @Test
    fun questionsWithoutANetworkSnapshotAreNeverMergedOrCached() {
        val broker = broker()
        val first = broker.claim("no-network", null) as WifiPromptBroker.Claim.Owner
        broker.finish(first, answer = true, cache = true)
        // 没有网络身份时不能把确认留给下一张网络。
        assertTrue(broker.claim("no-network", null) is WifiPromptBroker.Claim.Owner)
        assertTrue(broker.claim("with-network", Token("CarAP")) is WifiPromptBroker.Claim.Owner)
    }
}
