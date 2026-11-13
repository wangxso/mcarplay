package com.mcarplay.app

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 同一轮连接内「同一个问题、同一个网络」的弹窗单飞。
 *
 * 发起连接的 0x5703（绑定热点）与 0x4301（端点交接）是两条并发后台链路，二者都可能
 * 走到「使用当前 Wi-Fi？」确认。此前它们各自弹一个框：用户点掉第一个后，第二个
 * 同样的框立刻又弹出来（2026-11 用户反馈「弹出两次」）。
 *
 * 合并的前提是**两次问的是同一张网络**：[T] 是调用方在弹窗前采样的网络快照
 * （见 [WifiNetworkToken]）。手机在两次询问之间从 A 换到 B 时，B 的请求不会复用
 * A 的答案，也不会并入正为 A 展示的弹窗——否则用户对 A 的确认会被悄悄套到 B 上。
 * 没有快照（`token = null`）的问题既不合并也不缓存，保持一次一问。
 *
 * 答案的生命周期 = 一轮连接（[clear] 由新一轮连接/用户取消重置）。
 * 线程安全。
 */
internal class WifiPromptBroker<K : Any, T : Any>(
    /** 两个网络快照是否指同一张网络。 */
    private val tokensMatch: (T, T) -> Boolean = { a, b -> a == b },
) {
    sealed interface Claim<out K, out T> {
        /** 本轮已经为同一张网络问过同样的问题：复用答案，不再打扰用户。 */
        data class Answered<T>(
            val answer: Boolean?,
        ) : Claim<Nothing, T>

        /** 本请求负责展示弹窗，并在结束时通过 [finish] 交回答案。 */
        data class Owner<K : Any, T>(
            val key: K,
            val token: T?,
            val future: CompletableFuture<Boolean?>,
        ) : Claim<K, T>

        /**
         * 相同问题正在展示。`matches` = 展示中的网络与本次请求是同一张；
         * false 表示复用它会把 A 的确认套到 B 上，答案只能丢弃。
         */
        data class Follower<T>(
            val future: CompletableFuture<Boolean?>,
            val matches: Boolean,
        ) : Claim<Nothing, T>
    }

    private data class Flight<T>(
        val token: T?,
        val future: CompletableFuture<Boolean?>,
    )

    private data class Cached<T>(
        val token: T,
        val answer: Boolean?,
    )

    private val answers = HashMap<K, Cached<T>>()
    private val inFlight = HashMap<K, Flight<T>>()

    @Synchronized
    fun claim(
        key: K,
        token: T?,
    ): Claim<K, T> {
        if (token != null) {
            answers[key]?.let { cached ->
                if (tokensMatch(cached.token, token)) return Claim.Answered(cached.answer)
            }
            inFlight[key]?.let { flight ->
                val matches = flight.token != null && tokensMatch(flight.token, token)
                return Claim.Follower(flight.future, matches)
            }
        }
        val future = CompletableFuture<Boolean?>()
        inFlight[key] = Flight(token, future)
        return Claim.Owner(key, token, future)
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
     * 展示结束。只有仍是当前在途请求时才算数：被 [clear]/[timeout] 作废的旧回调
     * （取消后仍开着的弹窗、上一轮的迟到应答）不得把旧答案写进当前记录。
     *
     * @param cache true = 用户的真实选择，同一轮同一网络内可复用；
     *   false = 没有 UI 宿主等「无人应答」场景，只唤醒等待者，不当作答案。
     */
    fun finish(
        owner: Claim.Owner<K, T>,
        answer: Boolean?,
        cache: Boolean,
    ) {
        synchronized(this) {
            val flight = inFlight[owner.key]
            if (flight == null || flight.future !== owner.future) return
            inFlight.remove(owner.key)
            val token = owner.token
            if (cache && token != null) answers[owner.key] = Cached(token, answer)
        }
        owner.future.complete(answer)
    }

    /** 所有者等待超时：作废本次在途请求，唤醒相同等待者，但不缓存答案。 */
    fun timeout(owner: Claim.Owner<K, T>) {
        synchronized(this) {
            val flight = inFlight[owner.key]
            if (flight == null || flight.future !== owner.future) return
            inFlight.remove(owner.key)
        }
        owner.future.complete(null)
    }

    /** 新一轮连接开始/用户取消：上一轮的问答（含仍在等待的）全部作废。 */
    fun clear() {
        val pending =
            synchronized(this) {
                answers.clear()
                val futures = inFlight.values.map { it.future }
                inFlight.clear()
                futures
            }
        pending.forEach { it.complete(null) }
    }
}
