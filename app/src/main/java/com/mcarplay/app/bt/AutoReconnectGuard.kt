package com.mcarplay.app.bt

import android.content.Context
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.json.JSONObject
import java.util.Locale

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
 * 只拦自动路径（ACL 拉起 / 服务队列 / 自动 startConnection）；每个接收端地址一份
 * [State]（见 [ReconnectCircuitBreakerStore]），一台车的计数/熔断不会影响另一台。
 * **没有对端地址时既不计数也不命中熔断**——否则默认「自动选择目标」模式会把一台车的
 * 失败扩散到所有车机。
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
        /** 最近一次计入本接收端失败的诊断 attempt（跨路径去重）。 */
        val recordedAttempt: String? = null,
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
        val base = if (sameKnownPeer(state.address, address)) state else State(address = address)
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
        return sameKnownPeer(state.address, address)
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
 * 多接收端熔断状态机（纯内存，可直接单测）：按地址各存一份 [ReconnectCircuitBreaker.State]。
 *
 * 不同地址的计数与熔断完全独立——A 冷却期间 B 的失败照常累计、到时照常熔断，
 * 既不会因为 B 失败而解除 A，也不会因为 A 熔断而忽略 B。
 */
internal class ReconnectCircuitBreakerStore(
    private val breaker: ReconnectCircuitBreaker = ReconnectCircuitBreaker(),
) {
    data class Block(
        val failures: Int,
        val untilMs: Long,
        val remainingMs: Long,
    )

    /** 持久化键：地址大小写不敏感。 */
    fun keyOf(address: String): String = address.trim().uppercase(Locale.ROOT)

    /**
     * 记录一轮零帧失败。返回更新后的状态表与「本地址刚触发熔断」的块。
     * 同一 attempt 对同一地址只计一次。
     */
    fun recordFailure(
        states: Map<String, ReconnectCircuitBreaker.State>,
        address: String,
        attempt: String?,
        nowMs: Long,
    ): Pair<Map<String, ReconnectCircuitBreaker.State>, Block?> {
        val key = keyOf(address)
        val current = states[key] ?: ReconnectCircuitBreaker.State(address = address)
        if (!attempt.isNullOrBlank() && current.recordedAttempt == attempt) return states to null
        val failure = breaker.onFastFailure(current, address, nowMs)
        val updated = failure.state.copy(address = address, recordedAttempt = attempt)
        val next = states.toMutableMap().apply { put(key, updated) }
        val block =
            if (failure.tripped) {
                Block(updated.failures, updated.blockedUntilMs, breaker.remainingMillis(updated, nowMs))
            } else {
                null
            }
        return next to block
    }

    /** 成功出画：只清空该接收端的计数，不影响其它车机。 */
    fun recordHealthy(
        states: Map<String, ReconnectCircuitBreaker.State>,
        address: String,
    ): Map<String, ReconnectCircuitBreaker.State> = states - keyOf(address)

    /** 该地址是否处于熔断；只匹配地址本身。 */
    fun block(
        states: Map<String, ReconnectCircuitBreaker.State>,
        address: String,
        nowMs: Long,
    ): Block? {
        val state = states[keyOf(address)] ?: return null
        if (!breaker.isBlocked(state, address, nowMs)) return null
        return Block(state.failures, state.blockedUntilMs, breaker.remainingMillis(state, nowMs))
    }

    /** 任一接收端处于熔断（首页展示用，不用于放行判断）。 */
    fun activeBlock(
        states: Map<String, ReconnectCircuitBreaker.State>,
        nowMs: Long,
    ): Block? {
        val state = states.values.firstOrNull { breaker.isBlockActive(it, nowMs) } ?: return null
        return Block(state.failures, state.blockedUntilMs, breaker.remainingMillis(state, nowMs))
    }
}

/** 把多接收端状态编码成单个 SharedPreferences 字符串。 */
internal fun encodeAutoReconnectStates(states: Map<String, ReconnectCircuitBreaker.State>): String {
    val root = JSONObject()
    states.forEach { (key, state) ->
        root.put(
            key,
            JSONObject()
                .put("address", state.address ?: JSONObject.NULL)
                .put("failures", state.failures)
                .put("firstFailureAtMs", state.firstFailureAtMs)
                .put("lastFailureAtMs", state.lastFailureAtMs)
                .put("blockedUntilMs", state.blockedUntilMs)
                .put("recordedAttempt", state.recordedAttempt ?: JSONObject.NULL),
        )
    }
    return root.toString()
}

/** 解码 [encodeAutoReconnectStates] 的结果；损坏或空内容按无状态处理。 */
internal fun decodeAutoReconnectStates(raw: String?): Map<String, ReconnectCircuitBreaker.State> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val root = JSONObject(raw)
        buildMap {
            root.keys().forEach { key ->
                val item = root.optJSONObject(key) ?: return@forEach
                put(
                    key,
                    ReconnectCircuitBreaker.State(
                        address = item.optString("address").takeIf { it.isNotBlank() },
                        failures = item.optInt("failures"),
                        firstFailureAtMs = item.optLong("firstFailureAtMs"),
                        lastFailureAtMs = item.optLong("lastFailureAtMs"),
                        blockedUntilMs = item.optLong("blockedUntilMs"),
                        recordedAttempt = item.optString("recordedAttempt").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }.getOrElse { emptyMap() }
}

/**
 * [ReconnectCircuitBreakerStore] 的持久化入口。
 *
 * 只在**零帧**失败时计数；成功出画（≥1 帧）或用户手动连接会清空对应接收端。同一个
 * 诊断 attempt 重复上报（onEnded、disconnect、BT 链路断开）只记一次。状态按地址分槽
 * 写进 `settings` SharedPreferences，进程被杀后仍然生效，且不同车机互不影响。
 */
internal object AutoReconnectGuard {
    private const val PREFS = "settings"
    private const val KEY_STATES = "auto_reconnect_guard_states"

    // 旧版单槽键：只用于清理/迁移，避免升级后残留一台车机的熔断状态。
    private const val LEGACY_KEY_ADDRESS = "auto_reconnect_guard_address"
    private const val LEGACY_KEY_FAILURES = "auto_reconnect_guard_failures"
    private const val LEGACY_KEY_FIRST_FAILURE = "auto_reconnect_guard_first_failure_at"
    private const val LEGACY_KEY_LAST_FAILURE = "auto_reconnect_guard_last_failure_at"
    private const val LEGACY_KEY_BLOCKED_UNTIL = "auto_reconnect_guard_blocked_until"
    private const val LEGACY_KEY_RECORDED_ATTEMPT = "auto_reconnect_guard_recorded_attempt"

    private val store = ReconnectCircuitBreakerStore()

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
        val peer = address?.takeIf { it.isNotBlank() } ?: return null
        return store.block(read(context), peer, nowMs)?.toBlock()
    }

    /** 成功出画：只清空这台接收端的计数。 */
    @Synchronized
    fun recordHealthy(
        context: Context,
        address: String?,
        attempt: String?,
    ) {
        // 不知道是哪台车机出画时不能清空任何计数。
        val peer = address?.takeIf { it.isNotBlank() } ?: return
        val states = read(context)
        val next = store.recordHealthy(states, peer)
        if (next != states) write(context, next)
    }

    /** 首页展示用：只要任一接收端处于熔断就返回（UI 提示用，不用于放行）。 */
    @Synchronized
    fun activeBlock(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Block? = store.activeBlock(read(context), nowMs)?.toBlock()

    /** 记录一轮零帧失败；返回非 null 表示本接收端刚触发熔断。 */
    @Synchronized
    fun recordFastFailure(
        context: Context,
        address: String?,
        attempt: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Block? {
        // 没有对端地址无法按接收端隔离，不写计数（null 绝不能命中所有接收端）。
        val peer = address?.takeIf { it.isNotBlank() } ?: return null
        val states = read(context)
        val (next, block) = store.recordFailure(states, peer, attempt, nowMs)
        if (next != states) write(context, next)
        return block?.toBlock()
    }

    /** 用户手动连接/手动断开：清空所有接收端的熔断状态。 */
    @Synchronized
    fun clear(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STATES)
            .remove(LEGACY_KEY_ADDRESS)
            .remove(LEGACY_KEY_FAILURES)
            .remove(LEGACY_KEY_FIRST_FAILURE)
            .remove(LEGACY_KEY_LAST_FAILURE)
            .remove(LEGACY_KEY_BLOCKED_UNTIL)
            .remove(LEGACY_KEY_RECORDED_ATTEMPT)
            .apply()
    }

    private fun read(context: Context): Map<String, ReconnectCircuitBreaker.State> =
        decodeAutoReconnectStates(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATES, null))

    private fun write(
        context: Context,
        states: Map<String, ReconnectCircuitBreaker.State>,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATES, encodeAutoReconnectStates(states))
            .apply()
    }

    private fun ReconnectCircuitBreakerStore.Block.toBlock(): Block = Block(failures, untilMs, remainingMs)
}
