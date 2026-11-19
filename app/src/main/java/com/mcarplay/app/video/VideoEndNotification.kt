package com.mcarplay.app.video

import java.util.concurrent.atomic.AtomicBoolean

/** 每个 VideoSender 实例独立的结束通知域。 */
internal class VideoEndNotification {
    private val endedNotified = AtomicBoolean(false)

    fun afterDrain(
        stop: () -> Unit,
        notify: () -> Unit,
    ) {
        stop()
        if (endedNotified.compareAndSet(false, true)) runCatching { notify() }
    }
}
