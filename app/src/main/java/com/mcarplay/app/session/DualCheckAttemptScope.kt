package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Phase 3a 并行双判包装器：权威返回值始终取自 [legacy]，同步对照 [real] 并在出现分歧时去重记录诊断日志。
 *
 * 零行为变化保证：[isCurrent] 与 [isActive] 永远返回 [legacy] 的求值结果；生命周期动作由 [real] 执行。
 *
 * 双判覆盖全部入口：`checkActive`、`own`、`await`、`sleep` 在执行前同样比较两侧 `isActive`，
 * 因此 Phase 3b 把 `isActive` 守卫换成这些成员时，分歧仍会被记录而不是悄悄跟随 [real]。
 */
internal class DualCheckAttemptScope(
    private val legacy: AttemptScope,
    private val real: RealAttemptScope,
    private val onMismatch: (String) -> Unit,
) : AttemptScope {
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override val id: String
        get() = legacy.id

    override val observation: ReceiverObservation?
        get() = legacy.observation

    override val cancelReason: DiagnosticCode?
        get() = real.cancelReason

    override val isCurrent: Boolean
        get() {
            val legacyValue = legacy.isCurrent
            val realValue = real.isCurrent
            if (legacyValue != realValue) {
                report("isCurrent", legacyValue, realValue)
            }
            return legacyValue
        }

    override val isActive: Boolean
        get() {
            val legacyValue = legacy.isActive
            val realValue = real.isActive
            if (legacyValue != realValue) {
                report("isActive", legacyValue, realValue)
            }
            return legacyValue
        }

    override fun cancel(reason: DiagnosticCode) {
        real.cancel(reason)
    }

    override fun checkActive() {
        reportActivityGuard("checkActive")
        real.checkActive()
    }

    override fun <T : AutoCloseable> own(resource: T): T {
        reportActivityGuard("own")
        return real.own(resource)
    }

    override fun await(
        latch: CountDownLatch,
        timeoutMs: Long,
    ): Boolean {
        reportActivityGuard("await")
        return real.await(latch, timeoutMs)
    }

    override fun sleep(ms: Long): Boolean {
        reportActivityGuard("sleep")
        return real.sleep(ms)
    }

    /** 四个活动性守卫入口共用的双判：只比较不改变返回值，执行侧仍是 [real]。 */
    private fun reportActivityGuard(member: String) {
        val legacyValue = legacy.isActive
        val realValue = real.isActive
        if (legacyValue != realValue) {
            report(member, legacyValue, realValue)
        }
    }

    private fun report(
        prop: String,
        legacyValue: Boolean,
        realValue: Boolean,
    ) {
        val reason = real.cancelReason?.name ?: "none"
        val superseded = !real.isCurrent
        val key = "$prop:$legacyValue:$realValue:$reason:$superseded"
        if (reported.add(key)) {
            onMismatch(
                "SESSION: SCOPE_MISMATCH attempt=$id prop=$prop legacy=$legacyValue real=$realValue " +
                    "cancelReason=$reason superseded=$superseded obsNull=${observation == null}",
            )
        }
    }
}
