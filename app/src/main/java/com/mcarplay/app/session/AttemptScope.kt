package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 编排层专用的轮次取消控制流异常（严禁泄漏到引擎包）。 */
internal class AttemptCancelled(
    val reason: DiagnosticCode?,
) : RuntimeException(reason?.name ?: "Attempt cancelled")

/**
 * 一轮「端点 → 绑网 → 配对 → SETUP → 推流」的归属判断与取消作用域（docs/ARCHITECTURE-REFACTOR.md 3.3）。
 *
 * 编排代码只经它问「这一轮还该继续吗」，不再各自读全局取消标志或检测 epoch。
 * 用户取消后本轮仍 [isCurrent]（由它负责释放交接、记诊断），但已不 [isActive]。
 */
interface AttemptScope {
    /** 诊断时间线的 attempt id（`ConnectionDiagnostics.receiverAttempt()`）。 */
    val id: String

    /** 检测侧对本轮端点的观测；不经接收端检测的旧路径为 null。 */
    val observation: ReceiverObservation?

    /** 本轮仍拥有连接：没有被更新的一轮或用户终态取代。每次读取都按当前状态重新判断。 */
    val isCurrent: Boolean

    /** 本轮仍应继续推进：[isCurrent] 且未被取消。每次读取都按当前状态重新判断。 */
    val isActive: Boolean

    /** 首次触发取消的诊断原因；未取消或仅被取代时为 null。 */
    val cancelReason: DiagnosticCode?

    /** 幂等、单调取消：首次 reason 胜出，唤醒所有 sleep/await 并逆序关闭已登记资源。 */
    fun cancel(reason: DiagnosticCode)

    /** 本轮不再活跃时抛出 [AttemptCancelled]。 */
    fun checkActive()

    /** 登记本轮资源；若已取消/已取代则立即关闭（至多关闭一次）。 */
    fun <T : AutoCloseable> own(resource: T): T

    /** 可被 cancel/supersede 即刻打断的等待；true = latch 归零，false = 超时/取消/中断。 */
    fun await(
        latch: CountDownLatch,
        timeoutMs: Long,
    ): Boolean

    /** 可被 cancel/supersede 即刻打断的休眠；true = 睡满，false = 取消或中断。不抛 [AttemptCancelled]。 */
    fun sleep(ms: Long): Boolean
}

/** Phase 1a 的过渡实现：直接读现有的全局取消标志与检测 epoch，不缓存任何判断结果。 */
internal class LegacyAttemptScope(
    override val id: String,
    override val observation: ReceiverObservation?,
    private val cancelled: () -> Boolean,
    private val owns: (ReceiverObservation?) -> Boolean,
) : AttemptScope {
    override val isCurrent: Boolean
        get() = owns(observation)

    override val isActive: Boolean
        get() = !cancelled() && isCurrent

    override val cancelReason: DiagnosticCode?
        get() = null

    override fun cancel(reason: DiagnosticCode) = Unit

    override fun checkActive() {
        if (!isActive) throw AttemptCancelled(null)
    }

    override fun <T : AutoCloseable> own(resource: T): T = resource

    override fun await(
        latch: CountDownLatch,
        timeoutMs: Long,
    ): Boolean {
        if (!isActive) return false
        return try {
            latch.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS) && isActive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun sleep(ms: Long): Boolean {
        if (!isActive) return false
        if (ms <= 0L) return true
        return try {
            Thread.sleep(ms)
            isActive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/**
 * 交接熔断判据：本轮已不活跃，或交接所有权已不属于本轮。
 *
 * 两层都保留短路：本轮已取消/被取代时不问交接归属；归属只对活跃轮次做第二层判断。
 * 交接归属不能折进 [AttemptScope.isCurrent]/[AttemptScope.isActive]——用户取消时本轮仍需
 * 释放交接并记诊断，而交接被接手时本轮也未必已过期。
 */
internal fun attemptHandoffCancelled(
    scope: AttemptScope,
    ownsHandoff: () -> Boolean,
): Boolean = !scope.isActive || !ownsHandoff()
