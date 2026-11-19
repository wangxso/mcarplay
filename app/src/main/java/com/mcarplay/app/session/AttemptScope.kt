package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation

/**
 * 一轮「端点 → 绑网 → 配对 → SETUP → 推流」的归属判断（docs/ARCHITECTURE-REFACTOR.md 3.3）。
 *
 * 编排代码只经它问「这一轮还该继续吗」，不再各自读全局取消标志或检测 epoch。
 * 用户取消后本轮仍 [isCurrent]（由它负责释放交接、记诊断），但已不 [isActive]。
 *
 * 取消、资源登记与可打断等待（`cancel`/`own`/`await`/`sleep`）在第一个需要它们的模块抽出时再加入。
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
