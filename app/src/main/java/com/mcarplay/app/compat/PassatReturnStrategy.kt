package com.mcarplay.app.compat

/**
 * 帕萨特 2025（VW4211）「回原车主界面后再进 CarPlay 只是有概率成功」的实验策略。用户在设置里手动选择，
 * 默认关闭，**不按车机型号自动启用**：三种做法都没有实车证据，需要用户逐个上车试（见 docs/CARWITH-ENTRY-FINDINGS.md）。
 */
enum class PassatReturnStrategy(
    val key: String,
) {
    /** 不启用，保持现有行为。 */
    OFF("off"),

    /** 拿回主屏后车机没反应：自动重建一次主屏流（每次点图标最多一次）。 */
    REBUILD_RETRY("rebuild_retry"),

    /** 连续两次拿回主屏车机都没反应：结束本轮，让用户重新连接。 */
    DISCONNECT_AFTER_FAILURES("disconnect"),

    /** 用户点图标时不重建主屏流，只重申归属并在下一个 IDR 补参数集。 */
    NO_REBUILD("no_rebuild"),
    ;

    companion object {
        fun fromKey(key: String?): PassatReturnStrategy = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** 策略决策（纯函数，便于单测）。`SessionController` 只负责执行。 */
object PassatReturnPolicy {
    /** 连续失败多少次后 [PassatReturnStrategy.DISCONNECT_AFTER_FAILURES] 结束本轮。 */
    const val DISCONNECT_FAILURE_THRESHOLD = 2

    /** 本次拿回主屏是否重建主屏流：[PassatReturnStrategy.NO_REBUILD] 一律不重建。 */
    fun rebuildStream(
        strategy: PassatReturnStrategy,
        requested: Boolean,
    ): Boolean = requested && strategy != PassatReturnStrategy.NO_REBUILD

    /** 检查窗口内既无车机触控也无要关键帧 = 车机没理这次归属变化。 */
    fun takeBackFailed(
        touches: Int,
        keyFrameRequests: Int,
    ): Boolean = touches <= 0 && keyFrameRequests <= 0

    /** 失败后是否自动重建重试：每次点图标最多一次。 */
    fun retryRebuild(
        strategy: PassatReturnStrategy,
        failed: Boolean,
        alreadyRetried: Boolean,
    ): Boolean = strategy == PassatReturnStrategy.REBUILD_RETRY && failed && !alreadyRetried

    fun disconnect(
        strategy: PassatReturnStrategy,
        consecutiveFailures: Int,
    ): Boolean = strategy == PassatReturnStrategy.DISCONNECT_AFTER_FAILURES && consecutiveFailures >= DISCONNECT_FAILURE_THRESHOLD
}
