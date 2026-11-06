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
internal class ScreenOutputGate {
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
    )

    private enum class State {
        ACTIVE,
        SUSPENDED,
        AWAIT_KEY_FRAME,
    }

    private var state = State.ACTIVE
    private var skippedThisPause = 0L
    private var skippedTotal = 0L

    fun admit(
        phoneOwnsScreen: Boolean,
        keyFrame: Boolean,
    ): Decision {
        if (!phoneOwnsScreen) {
            val transition =
                if (state == State.SUSPENDED) {
                    null
                } else {
                    state = State.SUSPENDED
                    skippedThisPause = 0L
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
            return Decision(false, Transition.RESUMING, skippedThisPause, skippedTotal)
        }
        if (state == State.AWAIT_KEY_FRAME) {
            if (keyFrame) {
                state = State.ACTIVE
                return Decision(true, Transition.RESUMED, skippedThisPause, skippedTotal)
            }
            skip()
            return Decision(false, null, skippedThisPause, skippedTotal)
        }
        return Decision(true, null, skippedThisPause, skippedTotal)
    }

    fun summary(): String = "主屏归车机时累计跳过 $skippedTotal 帧，最近一次暂停 $skippedThisPause 帧"

    private fun skip() {
        skippedThisPause++
        skippedTotal++
    }
}
