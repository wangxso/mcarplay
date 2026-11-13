package com.mcarplay.app

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 同一轮连接内「同一个问题」的弹窗单飞。
 *
 * 发起连接的 0x5703（绑定热点）与 0x4301（端点交接）是两条并发后台链路，二者都可能
 * 走到「使用当前 Wi-Fi？」确认。此前它们各自 [show] 一个框：用户点掉第一个后，
 * 第二个同样的框立刻又弹出来（2026-11 用户反馈「弹出两次」）。
 *
 * 这里只合并**完全相同**的问题（标题/正文/按钮都一致）：
 * - 并发的第二个请求成为 [Claim.Follower]，等第一个的答案并复用，不再叠框；
 * - 第一个结束之后才到的相同请求，直接复用本轮的答案（[Claim.Answered]）；
 * - 网络在弹窗期间变化时，调用方以 `allowCachedAnswer = false` 重新询问，
 *   不会被上一次的答案压住；
 * - 不同问题互不影响，仍由用户分别确认。
 *
 * 线程安全；答案的生命周期 = 一轮连接（[clear] 由 startConnection 重置）。
 */
internal class WifiPromptBroker<K : Any> {
    sealed interface Claim<out K> {
        /** 本轮已经问过同样的问题：复用答案，不再打扰用户。 */
        data class Answered(
            val answer: Boolean?,
        ) : Claim<Nothing>

        /** 本请求负责展示弹窗，并在结束时通过 [finish] 交回答案。 */
        data class Owner<K : Any>(
            val key: K,
            val future: CompletableFuture<Boolean?>,
        ) : Claim<K>

        /** 相同问题正在展示：等它的答案，不叠第二个框。 */
        data class Follower(
            val future: CompletableFuture<Boolean?>,
        ) : Claim<Nothing>
    }

    private val answers = HashMap<K, Boolean?>()
    private val inFlight = HashMap<K, CompletableFuture<Boolean?>>()

    @Synchronized
    fun claim(
        key: K,
        allowCachedAnswer: Boolean = true,
    ): Claim<K> {
        if (allowCachedAnswer) answers[key]?.let { return Claim.Answered(it) }
        inFlight[key]?.let { return Claim.Follower(it) }
        val future = CompletableFuture<Boolean?>()
        inFlight[key] = future
        return Claim.Owner(key, future)
    }

    /** 等待展示结果；超时/中断按「无人应答」返回 null。 */
    fun await(
        future: CompletableFuture<Boolean?>,
        timeoutMs: Long,
    ): Boolean? =
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        }

    /**
     * 展示结束：`cache = true` 表示这是用户的真实选择，同一轮内可复用；
     * `cache = false` 用于没有 UI 宿主等「无人应答」场景，不把 null 当成用户答案。
     */
    fun finish(
        owner: Claim.Owner<K>,
        answer: Boolean?,
        cache: Boolean,
    ) {
        synchronized(this) {
            if (cache) answers[owner.key] = answer
            if (inFlight[owner.key] === owner.future) inFlight.remove(owner.key)
        }
        owner.future.complete(answer)
    }

    /** 所有者等待超时：不缓存答案，但唤醒相同的等待者，避免后续请求永远挂在队列里。 */
    fun timeout(owner: Claim.Owner<K>) {
        synchronized(this) {
            if (inFlight[owner.key] === owner.future) inFlight.remove(owner.key)
        }
        owner.future.complete(null)
    }

    /** 新一轮连接开始：上一轮的问答不再代表用户此刻的选择。 */
    @Synchronized
    fun clear() {
        answers.clear()
        inFlight.clear()
    }
}
