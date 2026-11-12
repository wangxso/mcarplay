package com.mcarplay.app.bt

import android.content.Context

/**
 * 自动重连失败熔断（2026-11-12 宝马 MGU-86 报告）。
 *
 * 车机侧如果反复在出画前拆链，每次 ACL 连上都会重新拉起一整轮 iAP2/AirPlay/CarWith，
 * 车机又把每一轮当成一次新的连接尝试并再次弹出「连接失败」。连续 [FAILURES_TO_TRIP]
 * 轮零帧失败后暂停自动重连 [COOLDOWN_MILLIS]，让用户先在车机上重试或重新配对；用户
 * 手动点「连接」始终放行并清空计数。
 *
 * 只拦自动路径（ACL 拉起 / 服务队列 / 自动 startConnection）；计数按目标地址隔离，
 * 状态写进 `settings` SharedPreferences，进程被杀后仍然生效。
 */
internal class ReconnectCircuitBreaker(
    private val failuresToTrip: Int = FAILURES_TO_TRIP,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
) {
    data class State(
        val address: String? = null,
        val failures: Int = 0,
        val firstFailureAtMs: Long = 0L,
        val lastFailureAtMs: Long = 0L,
        val blockedUntilMs: Long = 0L,
    )

    /** [tripped] 只在本轮把计数推到阈值、**刚进入**熔断时为 true。 */
    data class Failure(
        val state: State,
        val tripped: Boolean,
    )

    fun onFastFailure(
        state: State,
        address: String?,
        nowMs: Long,
    ): Failure {
        val samePeer = state.address == null || address == null || state.address.equals(address, ignoreCase = true)
        val base = if (samePeer) state else State(address = address)
        val blockExpired = base.blockedUntilMs > 0L && nowMs >= base.blockedUntilMs
        val withinWindow =
            !blockExpired &&
                base.failures > 0 &&
                nowMs - base.firstFailureAtMs in 0..windowMillis
        val failures = if (withinWindow) base.failures + 1 else 1
        val firstFailureAtMs = if (withinWindow) base.firstFailureAtMs else nowMs
        val blockedUntilMs =
            when {
                failures >= failuresToTrip -> nowMs + cooldownMillis
                base.blockedUntilMs > nowMs -> base.blockedUntilMs
                else -> 0L
            }
        return Failure(
            state =
                State(
                    address = base.address ?: address,
                    failures = failures,
                    firstFailureAtMs = firstFailureAtMs,
                    lastFailureAtMs = nowMs,
                    blockedUntilMs = blockedUntilMs,
                ),
            tripped = failures >= failuresToTrip && base.blockedUntilMs <= nowMs,
        )
    }

    fun onSuccess(state: State): State = State(address = state.address)

    /**
     * 熔断中返回 true。只拦同一个目标地址；剩余时间超过 [cooldownMillis] 视为系统时钟
     * 回拨，直接放行，避免一个永久熔断。
     */
    fun isBlocked(
        state: State,
        address: String?,
        nowMs: Long,
    ): Boolean {
        val remaining = state.blockedUntilMs - nowMs
        if (remaining !in 1..cooldownMillis) return false
        if (state.address == null || address == null) return true
        return state.address.equals(address, ignoreCase = true)
    }

    fun remainingMillis(
        state: State,
        nowMs: Long,
    ): Long = (state.blockedUntilMs - nowMs).coerceIn(0L, cooldownMillis)

    companion object {
        /** 3 轮零帧失败即熔断：宝马日志每轮 6–13 秒，3 轮足以排除偶发一次。 */
        const val FAILURES_TO_TRIP = 3

        /** 计数窗口：5 分钟内连续 3 轮才算循环。 */
        const val WINDOW_MILLIS = 5 * 60_000L

        /** 熔断时长：3 分钟内不再自动重连；手动连接不受限。 */
        const val COOLDOWN_MILLIS = 3 * 60_000L
    }
}

/**
 * [ReconnectCircuitBreaker] 的持久化入口。
 *
 * 只在**零帧**失败时计数；成功出画（≥1 帧）或用户手动连接会清空。同一个诊断 attempt
 * 重复上报（onEnded、disconnect、BT 链路断开）只记一次。
 */
internal object AutoReconnectGuard {
    private const val PREFS = "settings"
    private const val KEY_ADDRESS = "auto_reconnect_guard_address"
    private const val KEY_FAILURES = "auto_reconnect_guard_failures"
    private const val KEY_FIRST_FAILURE = "auto_reconnect_guard_first_failure_at"
    private const val KEY_LAST_FAILURE = "auto_reconnect_guard_last_failure_at"
    private const val KEY_BLOCKED_UNTIL = "auto_reconnect_guard_blocked_until"
    private const val KEY_RECORDED_ATTEMPT = "auto_reconnect_guard_recorded_attempt"

    private val breaker = ReconnectCircuitBreaker()

    data class Block(
        val failures: Int,
        val untilMs: Long,
        val remainingMs: Long,
    ) {
        val remainingSeconds: Long get() = ((remainingMs + 999L) / 1_000L).coerceAtLeast(1L)
    }

    @Synchronized
    fun block(
        context: Context,
        address: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Block? {
        val state = read(context)
        if (!breaker.isBlocked(state, address, nowMs)) return null
        return Block(state.failures, state.blockedUntilMs, breaker.remainingMillis(state, nowMs))
    }

    /** 成功出画：清空计数。返回后同一 attempt 再报零帧失败也不会被计数。 */
    @Synchronized
    fun recordHealthy(
        context: Context,
        address: String?,
        attempt: String?,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = breaker.onSuccess(read(context))
        prefs
            .edit()
            .putString(KEY_ADDRESS, address ?: state.address)
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_FIRST_FAILURE, 0L)
            .putLong(KEY_LAST_FAILURE, 0L)
            .putLong(KEY_BLOCKED_UNTIL, 0L)
            .putString(KEY_RECORDED_ATTEMPT, attempt ?: "")
            .apply()
    }

    /** 记录一轮零帧失败；返回非 null 表示本次刚刚触发熔断。 */
    @Synchronized
    fun recordFastFailure(
        context: Context,
        address: String?,
        attempt: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Block? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!attempt.isNullOrBlank() && prefs.getString(KEY_RECORDED_ATTEMPT, null) == attempt) return null
        val failure = breaker.onFastFailure(read(context), address, nowMs)
        prefs
            .edit()
            .putString(KEY_ADDRESS, failure.state.address ?: address)
            .putInt(KEY_FAILURES, failure.state.failures)
            .putLong(KEY_FIRST_FAILURE, failure.state.firstFailureAtMs)
            .putLong(KEY_LAST_FAILURE, failure.state.lastFailureAtMs)
            .putLong(KEY_BLOCKED_UNTIL, failure.state.blockedUntilMs)
            .putString(KEY_RECORDED_ATTEMPT, attempt ?: "")
            .apply()
        if (!failure.tripped) return null
        return Block(
            failures = failure.state.failures,
            untilMs = failure.state.blockedUntilMs,
            remainingMs = breaker.remainingMillis(failure.state, nowMs),
        )
    }

    /** 用户手动连接/手动断开：清空熔断状态。 */
    @Synchronized
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BLOCKED_UNTIL) &&
            !prefs.contains(KEY_FAILURES) &&
            !prefs.contains(KEY_ADDRESS) &&
            !prefs.contains(KEY_RECORDED_ATTEMPT)
        ) {
            return
        }
        prefs
            .edit()
            .remove(KEY_ADDRESS)
            .remove(KEY_FAILURES)
            .remove(KEY_FIRST_FAILURE)
            .remove(KEY_LAST_FAILURE)
            .remove(KEY_BLOCKED_UNTIL)
            .remove(KEY_RECORDED_ATTEMPT)
            .apply()
    }

    private fun read(context: Context): ReconnectCircuitBreaker.State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ReconnectCircuitBreaker.State(
            address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() },
            failures = prefs.getInt(KEY_FAILURES, 0),
            firstFailureAtMs = prefs.getLong(KEY_FIRST_FAILURE, 0L),
            lastFailureAtMs = prefs.getLong(KEY_LAST_FAILURE, 0L),
            blockedUntilMs = prefs.getLong(KEY_BLOCKED_UNTIL, 0L),
        )
    }
}
