package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import java.util.IdentityHashMap

/**
 * 进程级轮次注册表（Phase 3a 极小前置缝）：维护当前活跃 [RealAttemptScope] 槽位及按对象身份（`===`）索引的
 * `client -> scope` 绑定。
 *
 * 锁序不变量：内部私有 [lock] 仅保护状态切换；所有 `cancel()` 与 `supersede()` 调用均在释放 [lock] 之后执行，
 * 绝不持锁触发任何外部 `AutoCloseable.close()`。
 */
internal class AttemptRegistry {
    private val lock = Any()
    private var active: RealAttemptScope? = null
    private val clientScopes = IdentityHashMap<Any, RealAttemptScope>()

    /** 激活新一轮：将旧活跃轮次移出槽位并在锁外调用其 `supersede()`，同时清理已失效的 client 映射。 */
    fun activate(scope: RealAttemptScope) {
        val previous =
            synchronized(lock) {
                val prev = active
                active = scope
                if (prev != null && prev !== scope) {
                    clientScopes.values.removeIf { it === prev || !it.isCurrent }
                    prev
                } else {
                    clientScopes.values.removeIf { !it.isCurrent }
                    null
                }
            }
        previous?.supersede()
    }

    /** 仅当 [scope] 仍占活跃槽时将其摘除，不取消也不取代（专供空端点早退释放交接使用）。 */
    fun detachIfActive(scope: RealAttemptScope) {
        synchronized(lock) {
            if (active === scope) {
                active = null
            }
        }
    }

    /** 若当前活跃轮次属于 [observation]，在锁外取消它并保留其 `isCurrent == true`（由其完成交接释放与收尾）。 */
    fun cancelIfObservation(
        observation: ReceiverObservation?,
        reason: DiagnosticCode,
    ): Boolean {
        val target =
            synchronized(lock) {
                active?.takeIf { it.observation == observation }
            } ?: return false
        target.cancel(reason)
        return true
    }

    /** 在锁外取消当前活跃轮次并保留其 `isCurrent == true`，直至后续 `supersedeAll()` 统一取代。 */
    fun cancelActive(reason: DiagnosticCode) {
        val target = synchronized(lock) { active }
        target?.cancel(reason)
    }

    /** 清空活跃槽与全部 client 映射，并在锁外逐一调用 `supersede()`。 */
    fun supersedeAll() {
        val targets =
            synchronized(lock) {
                val collected = ArrayList<RealAttemptScope>(clientScopes.size + 1)
                active?.let { collected.add(it) }
                active = null
                for (scope in clientScopes.values) {
                    if (collected.none { it === scope }) {
                        collected.add(scope)
                    }
                }
                clientScopes.clear()
                collected
            }
        for (scope in targets) {
            scope.supersede()
        }
    }

    /** 按客户端身份摘除绑定并在锁外先 `cancel(reason)` 再 `supersede()`（专供推流中邀请重连）。 */
    fun supersedeClient(
        client: Any,
        reason: DiagnosticCode,
    ) {
        val target =
            synchronized(lock) {
                val bound = clientScopes.remove(client)
                if (bound != null) {
                    clientScopes.values.removeIf { it === bound }
                    if (active === bound) {
                        active = null
                    }
                }
                bound
            }
        if (target != null) {
            target.cancel(reason)
            target.supersede()
        }
    }

    /** 按对象身份（`===`）绑定当前轮次客户端与作用域；已取代的作用域不再登记。 */
    fun bindClient(
        client: Any,
        scope: RealAttemptScope,
    ) {
        synchronized(lock) {
            clientScopes.values.removeIf { !it.isCurrent }
            if (scope.isCurrent) {
                clientScopes[client] = scope
            }
        }
    }

    /** 按对象身份（`===`）查询客户端对应的轮次作用域。 */
    fun scopeForClient(client: Any): RealAttemptScope? =
        synchronized(lock) {
            clientScopes[client]
        }
}
