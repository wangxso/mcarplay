package com.mcarplay.app.bt

import android.content.Context
import com.mcarplay.app.diagnostics.DiagnosticCode

/**
 * 计入自动重连熔断的结束原因：接收端/会话链路失败，而不是用户操作或权限/组件问题。
 * 用户手动断开（USER_STOP）与新尝试替代（SUPERSEDED）不计。
 */
internal val AUTO_RECONNECT_FAILURE_CODES =
    setOf(
        DiagnosticCode.BT_LINK_CLOSED,
        DiagnosticCode.VIDEO_CLOSED,
        DiagnosticCode.WIFI_LOST,
        DiagnosticCode.WIFI_JOIN_FAILED,
        DiagnosticCode.PAIRING_FAILED,
        DiagnosticCode.SETUP_FAILED,
        DiagnosticCode.CONNECTION_INCOMPLETE,
    )

/**
 * 只把「短命且零帧」的轮次计入熔断。主屏归车机时 0.1.6 的门控会主动丢帧，
 * 长时间停在原车界面（例如倒车影像）后正常结束的会话不应被当成车机故障。
 */
internal const val AUTO_RECONNECT_FAST_FAILURE_MAX_MS = 60_000L

/**
 * 一轮结束是否应计入自动重连熔断。
 *
 * SETUP/PAIRING/WIFI_JOIN 失败不经过 [com.mcarplay.app.SessionController.disconnect]，
 * 必须由各自的失败出口在结束时间线后调用本判定，否则反复快速失败会绕过熔断无限重连。
 */
internal fun countsAsAutoReconnectFailure(
    code: DiagnosticCode?,
    videoFrames: Long,
    elapsedMs: Long,
): Boolean =
    code != null &&
        code in AUTO_RECONNECT_FAILURE_CODES &&
        videoFrames == 0L &&
        elapsedMs < AUTO_RECONNECT_FAST_FAILURE_MAX_MS

/** 两个**已知**地址是否为同一接收端（大小写不敏感）；任一未知返回 false。 */
internal fun sameKnownPeer(
    a: String?,
    b: String?,
): Boolean = !a.isNullOrBlank() && !b.isNullOrBlank() && a.equals(b, ignoreCase = true)

/**
 * 地址未知时无法判定为「另一台车机」，按兼容处理；只有两边都已知且不同才算冲突。
 * 用于迟到链路断开、自动收摊等不能误伤既有路径的判断。
 */
internal fun peerUnknownOrMatches(
    a: String?,
    b: String?,
): Boolean = a.isNullOrBlank() || b.isNullOrBlank() || a.equals(b, ignoreCase = true)

/**
 * 自动重连失败熔断（2026-11-12 宝马 MGU-86 报告）。
 *
 * 车机侧如果反复在出画前拆链，每次 ACL 连上都会重新拉起一整轮 iAP2/AirPlay/CarWith，
 * 车机又把每一轮当成一次新的连接尝试并再次弹出「连接失败」。连续 [FAILURES_TO_TRIP]
 * 轮零帧失败后暂停自动重连 [COOLDOWN_MILLIS]，让用户先在车机上重试或重新配对；用户
 * 手动点「连接」始终放行并清空计数。
 *
 * 只拦自动路径（ACL 拉起 / 服务队列 / 自动 startConnection）；计数按目标地址隔离，
 * 状态写进 `settings` SharedPreferences，进程被杀后仍然生效。**没有对端地址时既不计数
 * 也不命中熔断**——否则一台车的失败会拦下默认「自动选择目标」模式里所有车机的启动。
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
        // 没有对端地址就无法按接收端隔离：不计数，也不把这次失败归到其它车机头上。
        if (address.isNullOrBlank()) return Failure(state, tripped = false)
        val samePeer = sameKnownPeer(state.address, address)
        // 另一台车机的熔断仍在冷却：单槽状态不能为了记 B 的失败而抹掉 A 的熔断，
        // 否则 A 的自动重连会在冷却期内被重新放开（跨接收端泄漏的另一方向）。
        if (!samePeer && isBlockActive(state, nowMs)) return Failure(state, tripped = false)
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
                    address = address,
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
     * 熔断中返回 true。只拦**同一个**目标地址；存储或查询的地址为 null 一律不匹配，
     * 避免未选定目标（自动模式）时把一台车机的连续失败扩散到所有接收端。
     * 剩余时间超过 [cooldownMillis] 视为系统时钟回拨，直接放行，避免一个永久熔断。
     */
    fun isBlocked(
        state: State,
        address: String?,
        nowMs: Long,
    ): Boolean {
        if (!isBlockActive(state, nowMs)) return false
        val stored = state.address ?: return false
        val queried = address ?: return false
        return stored.equals(queried, ignoreCase = true)
    }

    /** 当前是否处于熔断期（不区分接收端；UI 展示用，不用于放行判断）。 */
    fun isBlockActive(
        state: State,
        nowMs: Long,
    ): Boolean = state.blockedUntilMs - nowMs in 1..cooldownMillis

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
        // 不知道是哪台车机出画时不能清空计数：可能把另一台车的连续失败一起抹掉。
        if (address.isNullOrBlank()) return
        val current = read(context)
        if (current.address != null && !sameKnownPeer(current.address, address)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = breaker.onSuccess(current)
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

    /** 首页展示用：只要当前存在熔断就返回（不按地址过滤，UI 提示用）。 */
    @Synchronized
    fun activeBlock(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Block? {
        val state = read(context)
        if (!breaker.isBlockActive(state, nowMs)) return null
        return Block(state.failures, state.blockedUntilMs, breaker.remainingMillis(state, nowMs))
    }

    /** 记录一轮零帧失败；返回非 null 表示本次刚刚触发熔断。 */
    @Synchronized
    fun recordFastFailure(
        context: Context,
        address: String?,
        attempt: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Block? {
        // 没有对端地址无法按接收端隔离，不写计数（null 绝不能命中所有接收端）。
        if (address.isNullOrBlank()) return null
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
