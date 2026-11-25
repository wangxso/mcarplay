package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Phase 3a 并行双判包装器：权威返回值始终取自 [legacy]，同步对照 [real] 并在出现分歧时去重记录诊断日志。
 *
 * 零行为变化保证：[isCurrent] 与 [isActive] 永远返回 [legacy] 的求值结果；其余生命周期动作委托给 [real]。
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
        real.checkActive()
    }

    override fun <T : AutoCloseable> own(resource: T): T = real.own(resource)

    override fun await(
        latch: CountDownLatch,
        timeoutMs: Long,
    ): Boolean = real.await(latch, timeoutMs)

    override fun sleep(ms: Long): Boolean = real.sleep(ms)

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
