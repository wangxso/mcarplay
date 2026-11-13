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
 * A 的答案，也不会并入正为 A 展示的弹窗——它等旧请求收尾后**重新询问**。
 * 没有快照（`token = null`）的问题既不合并也不缓存：每次请求各自独立展示，
 * 互不覆盖（两条链路可能同时问同一个没有网络身份的问题）。
 *
 * 失效按轮次（generation）判定：`clear()` 之后所有旧回调都作废，迟到的点击
 * 不会把上一轮的答案写进当前记录。
 *
 * 答案的生命周期 = 一轮连接（[clear] 由新一轮连接/用户取消重置）。线程安全。
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
            val generation: Int,
            val future: CompletableFuture<Boolean?>,
        ) : Claim<K, T>

        /**
         * 相同问题正在展示。`matches` = 展示中的网络与本次请求是同一张；
         * false 表示那是给另一张网络的答案，调用方应重新询问而不是丢弃请求。
         */
        data class Follower<T>(
            val future: CompletableFuture<Boolean?>,
            val matches: Boolean,
        ) : Claim<Nothing, T>
    }

    private data class Flight<T>(
        val token: T,
        val future: CompletableFuture<Boolean?>,
    )

    private data class Cached<T>(
        val token: T,
        val answer: Boolean?,
    )

    private val answers = HashMap<K, Cached<T>>()
    private val inFlight = HashMap<K, Flight<T>>()

    /** 没有网络快照的请求：彼此独立，不参与合并，也不会互相覆盖。 */
    private val unkeyed = HashSet<CompletableFuture<Boolean?>>()

    private var generation = 0

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
                return Claim.Follower(flight.future, tokensMatch(flight.token, token))
            }
            val future = CompletableFuture<Boolean?>()
            inFlight[key] = Flight(token, future)
            return Claim.Owner(key, token, generation, future)
        }
        // 没有网络身份时不能把确认留给另一张网络，也不能把并发请求合并——
        // 各自展示，并登记以便 clear() 统一作废。
        val future = CompletableFuture<Boolean?>()
        unkeyed.add(future)
        return Claim.Owner(key, null, generation, future)
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
     * 展示结束。只有仍属于当前轮次、且（有快照时）仍是该问题的在途请求时才算数：
     * 被 [clear]/[timeout] 作废的旧回调不得把旧答案写进当前记录。
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
            if (owner.generation != generation) return
            val token = owner.token
            if (token == null) {
                if (!unkeyed.remove(owner.future)) return
            } else {
                val flight = inFlight[owner.key]
                if (flight == null || flight.future !== owner.future) return
                inFlight.remove(owner.key)
                if (cache) answers[owner.key] = Cached(token, answer)
            }
        }
        owner.future.complete(answer)
    }

    /** 所有者等待超时：作废本次在途请求，唤醒相同等待者，但不缓存答案。 */
    fun timeout(owner: Claim.Owner<K, T>) {
        synchronized(this) {
            if (owner.generation != generation) return
            val token = owner.token
            if (token == null) {
                unkeyed.remove(owner.future)
            } else {
                val flight = inFlight[owner.key]
                if (flight == null || flight.future !== owner.future) return
                inFlight.remove(owner.key)
            }
        }
        owner.future.complete(null)
    }

    /**
     * 新一轮连接开始/用户取消：上一轮的问答全部作废，迟到的回调不会再落账。
     *
     * 不在这里替在途请求作答：调用方各自的 `isCancelled`/超时负责收尾，若这里补一个
     * null，会被当成「用户没回答」而中止一次仍在进行的连接。
     */
    @Synchronized
    fun clear() {
        generation++
        answers.clear()
        inFlight.clear()
        unkeyed.clear()
    }
}
