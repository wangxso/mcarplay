package com.mcarplay.app.video

/**
 * 主屏视频输出门控：主屏归车机时停止发送所有视频帧，归还手机后等新的 IDR 再恢复。
 *
 * 帕萨特 2025（VW4211）2026-11-06 报告：车机通过 `changeModes` 拿走主屏后，mcarplay 仍持续发送
 * CarWith 视频帧；倒车影像期间车机并不显示这路视频。这个门控只影响屏幕视频帧，不关闭 CarPlay、
 * CarWith、音频或屏幕流 socket，keepAlive 仍由 [VideoSender] 照常发送。
 *
 * 非线程安全：由 [VideoSender] 串行调用。
 */
internal class ScreenOutputGate(
    private val keyFrameRetryMs: Long = DEFAULT_KEY_FRAME_RETRY_MS,
) {
    enum class Transition {
        /** 主屏刚归车机，开始暂停视频帧。 */
        PAUSED,

        /** 主屏刚归还手机，等待新的 IDR。 */
        RESUMING,

        /** 归还后的首个 IDR 已放行。 */
        RESUMED,
    }

    data class Decision(
        val send: Boolean,
        val transition: Transition? = null,
        val skippedThisPause: Long = 0L,
        val skippedTotal: Long = 0L,
        /** 调用方现在应请求一个新的 IDR；请求失败时，后续 P 帧会按间隔再次触发。 */
        val requestKeyFrame: Boolean = false,
        /** 本次暂停中的第几次关键帧请求（0 = 本帧不请求）。 */
        val keyFrameRequestAttempt: Int = 0,
    )

    private enum class State {
        ACTIVE,
        SUSPENDED,
        AWAIT_KEY_FRAME,
    }

    private var state = State.ACTIVE
    private var skippedThisPause = 0L
    private var skippedTotal = 0L
    private var lastKeyFrameRequestAtMs: Long? = null
    private var keyFrameRequestAttempt = 0

    init {
        require(keyFrameRetryMs > 0) { "keyFrameRetryMs must be positive" }
    }

    val skippedFramesTotal: Long get() = skippedTotal

    /**
     * 已确认主屏归车机并处于暂停。转码路径可据此停止向编码器喂帧以省功耗；
     * AWAIT_KEY_FRAME 必须继续喂帧，否则编码器无法产出恢复所需的新 IDR。
     */
    val isSuspended: Boolean get() = state == State.SUSPENDED

    fun admit(
        phoneOwnsScreen: Boolean,
        keyFrame: Boolean,
        nowMs: Long,
    ): Decision {
        if (!phoneOwnsScreen) {
            val transition =
                if (state == State.SUSPENDED) {
                    null
                } else {
                    state = State.SUSPENDED
                    skippedThisPause = 0L
                    lastKeyFrameRequestAtMs = null
                    keyFrameRequestAttempt = 0
                    Transition.PAUSED
                }
            skip()
            return Decision(false, transition, skippedThisPause, skippedTotal)
        }
        if (state == State.SUSPENDED) {
            state = State.AWAIT_KEY_FRAME
            if (keyFrame) {
                state = State.ACTIVE
                return Decision(true, Transition.RESUMED, skippedThisPause, skippedTotal)
            }
            skip()
            return requestKeyFrame(Transition.RESUMING, nowMs)
        }
        if (state == State.AWAIT_KEY_FRAME) {
            if (keyFrame) {
                state = State.ACTIVE
                return Decision(true, Transition.RESUMED, skippedThisPause, skippedTotal)
            }
            skip()
            val request = shouldRequestKeyFrame(nowMs)
            return Decision(
                send = false,
                skippedThisPause = skippedThisPause,
                skippedTotal = skippedTotal,
                requestKeyFrame = request,
                keyFrameRequestAttempt = if (request) keyFrameRequestAttempt else 0,
            )
        }
        return Decision(true, null, skippedThisPause, skippedTotal)
    }

    fun summary(): String =
        "主屏归车机时累计跳过 $skippedTotal 帧，最近一次暂停 $skippedThisPause 帧，" +
            "最近一次暂停请求关键帧 $keyFrameRequestAttempt 次"

    private fun skip() {
        skippedThisPause++
        skippedTotal++
    }

    private fun requestKeyFrame(
        transition: Transition,
        nowMs: Long,
    ): Decision {
        lastKeyFrameRequestAtMs = nowMs
        keyFrameRequestAttempt++
        return Decision(
            send = false,
            transition = transition,
            skippedThisPause = skippedThisPause,
            skippedTotal = skippedTotal,
            requestKeyFrame = true,
            keyFrameRequestAttempt = keyFrameRequestAttempt,
        )
    }

    private fun shouldRequestKeyFrame(nowMs: Long): Boolean {
        val last = lastKeyFrameRequestAtMs ?: return true
        if (nowMs - last < keyFrameRetryMs) return false
        lastKeyFrameRequestAtMs = nowMs
        keyFrameRequestAttempt++
        return true
    }

    companion object {
        /** 等待 IDR 时重试关键帧请求的最小间隔。 */
        const val DEFAULT_KEY_FRAME_RETRY_MS = 1_000L
    }
}
