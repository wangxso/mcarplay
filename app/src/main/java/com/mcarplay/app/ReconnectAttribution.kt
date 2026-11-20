package com.mcarplay.app

import android.content.Context
import com.mcarplay.app.bt.AUTO_RECONNECT_FAILURE_CODES
import com.mcarplay.app.bt.AutoReconnectGuard
import com.mcarplay.app.bt.ReconnectCircuitBreaker
import com.mcarplay.app.bt.ReconnectCircuitBreakerStore
import com.mcarplay.app.bt.countsAsAutoReconnectFailure
import com.mcarplay.app.diagnostics.DiagnosticCode

/** 自动重连记账的当前轮次快照。 */
internal data class ReconnectAttemptSnapshot(
    val id: String,
    val videoFrames: Long,
    val elapsedMs: Long,
)

/** 自动重连记账的状态访问边界；生产实现仍是 SharedPreferences + wall clock。 */
internal interface ReconnectGuardStore {
    fun recordHealthy(
        context: Any?,
        address: String?,
        attempt: String?,
    )

    fun recordFastFailure(
        context: Any?,
        address: String?,
        attempt: String?,
    ): AutoReconnectGuard.Block?
}

/** 自动重连 block 查询与手动清空的访问边界；生产实现仍是 SharedPreferences + wall clock。 */
internal interface ReconnectGuardQuery {
    fun block(
        context: Any?,
        address: String?,
        nowMs: Long,
    ): AutoReconnectGuard.Block?

    fun activeBlock(
        context: Any,
        nowMs: Long,
    ): AutoReconnectGuard.Block?

    fun clear(context: Any)
}

/** [ReconnectGuardStore] 的真实持久化实现。 */
internal class DefaultReconnectGuardStore : ReconnectGuardStore {
    override fun recordHealthy(
        context: Any?,
        address: String?,
        attempt: String?,
    ) {
        if (context != null) AutoReconnectGuard.recordHealthy(context as Context, address, attempt)
    }

    override fun recordFastFailure(
        context: Any?,
        address: String?,
        attempt: String?,
    ): AutoReconnectGuard.Block? = context?.let { AutoReconnectGuard.recordFastFailure(it as Context, address, attempt) }
}

/** [ReconnectGuardQuery] 的真实持久化实现。 */
internal class DefaultReconnectGuardQuery : ReconnectGuardQuery {
    override fun block(
        context: Any?,
        address: String?,
        nowMs: Long,
    ): AutoReconnectGuard.Block? = context?.let { AutoReconnectGuard.block(it as Context, address, nowMs) }

    override fun activeBlock(
        context: Any,
        nowMs: Long,
    ): AutoReconnectGuard.Block? = AutoReconnectGuard.activeBlock(context as Context, nowMs)

    override fun clear(context: Any) {
        AutoReconnectGuard.clear(context as Context)
    }
}

/** 内存实现：复用真实 [ReconnectCircuitBreakerStore]，只为 JVM 测试提供时钟与状态。 */
internal class MemoryReconnectGuardStore(
    private val store: ReconnectCircuitBreakerStore = ReconnectCircuitBreakerStore(),
    private var states: Map<String, ReconnectCircuitBreaker.State> = emptyMap(),
    private var nowMs: () -> Long = { 0L },
) : ReconnectGuardStore,
    ReconnectGuardQuery {
    override fun recordHealthy(
        context: Any?,
        address: String?,
        attempt: String?,
    ) {
        val peer = address?.takeIf { it.isNotBlank() } ?: return
        states = store.recordHealthy(states, peer)
    }

    override fun recordFastFailure(
        context: Any?,
        address: String?,
        attempt: String?,
    ): AutoReconnectGuard.Block? {
        val peer = address?.takeIf { it.isNotBlank() } ?: return null
        val (next, block) = store.recordFailure(states, peer, attempt, nowMs())
        states = next
        return block?.let { block -> AutoReconnectGuard.Block(block.failures, block.untilMs, block.remainingMs) }
    }

    override fun block(
        context: Any?,
        address: String?,
        nowMs: Long,
    ): AutoReconnectGuard.Block? {
        val peer = address?.takeIf { it.isNotBlank() } ?: return null
        return store.block(states, peer, nowMs)?.let { block ->
            AutoReconnectGuard.Block(block.failures, block.untilMs, block.remainingMs)
        }
    }

    override fun activeBlock(
        context: Any,
        nowMs: Long,
    ): AutoReconnectGuard.Block? {
        val block = store.activeBlock(states, nowMs) ?: return null
        return AutoReconnectGuard.Block(block.failures, block.untilMs, block.remainingMs)
    }

    override fun clear(context: Any) {
        states = emptyMap()
    }
}

/**
 * 自动重连熔断的唯一记账接线。
 *
 * controller 只负责读取真实 snapshot/Context 与记账叶子；helper 保持原判据与返回语义。
 */
internal class ReconnectAttribution(
    private val codes: Set<DiagnosticCode> = AUTO_RECONNECT_FAILURE_CODES,
    private val store: ReconnectGuardStore = DefaultReconnectGuardStore(),
    private val query: ReconnectGuardQuery = DefaultReconnectGuardQuery(),
    private val context: () -> Any?,
    private val snapshot: () -> ReconnectAttemptSnapshot?,
    private val appendLog: (String) -> Unit,
) {
    fun record(
        attempt: String?,
        code: DiagnosticCode?,
        address: String?,
    ): Boolean {
        if (code == null || code !in codes) return false
        val app = context() ?: return false
        val snapshot = snapshot() ?: return false
        if (attempt.isNullOrBlank() || snapshot.id != attempt) return false
        if (snapshot.videoFrames > 0L) {
            store.recordHealthy(app, address, attempt)
            return false
        }
        if (!countsAsAutoReconnectFailure(code, snapshot.videoFrames, snapshot.elapsedMs)) return false
        val block = store.recordFastFailure(app, address, attempt) ?: return false
        appendLog(
            "AUTO: 连续 ${block.failures} 次会话在出画前被车机断开——暂停自动重连 ${block.remainingSeconds} 秒" +
                "（可在 App 内手动连接）",
        )
        return true
    }

    /** 自动重连熔断的唯一 block 查询接线；查询本身只读真实 store 状态。 */
    fun block(
        address: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): AutoReconnectGuard.Block? {
        val app = context() ?: return null
        return query.block(app, address, nowMs)
    }

    /** 首页 active block 查询接线；显式 Context 保持在调用点。 */
    fun activeBlock(
        context: Any,
        nowMs: Long = System.currentTimeMillis(),
    ): AutoReconnectGuard.Block? = query.activeBlock(context, nowMs)

    /** 手动连接/停止清空接线；显式 Context 保持在调用点。 */
    fun clear(context: Any) {
        query.clear(context)
    }
}
