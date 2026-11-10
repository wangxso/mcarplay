package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation

/**
 * 一轮「端点 → 绑网 → 配对 → SETUP → 推流」的归属判断（docs/ARCHITECTURE-REFACTOR.md 3.3）。
 *
 * 编排代码和后续抽出的模块只经它问「这一轮还该继续吗」，不再各自读全局取消标志或检测 epoch。
 * 两个判断要分开：用户取消后本轮仍 [isCurrent]（由它负责释放「会话建立中」互斥、记诊断），但已不 [isActive]。
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

/**
 * Phase 1a 的过渡实现：直接读现有的全局取消标志与检测 epoch。
 *
 * 与重构前 `onCarPlayEndpoint` 里的两个局部函数逐字等价：
 * `ownsConnection()` = [isCurrent]，`wasCancelled()` = `!`[isActive]（先看取消标志，再看归属）。
 * Phase 3 换成真正持有取消状态的实现。
 */
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
