package com.mcarplay.app.media

/**
 * 「上车自动播放音乐」关闭时的抑制窗口。
 *
 * 连接建立后：
 *  - [KEY_WINDOW_MILLIS] 内丢弃车机发来的播放/播放暂停键（多为车机恢复上次媒体）；
 *  - [WATCH_WINDOW_MILLIS] 内若媒体自行开始播放（CarWith 或音乐应用自动恢复），
 *    允许调用方暂停一次。
 *
 * 连接前已经在播放的媒体不抑制（[arm] 传 alreadyPlaying = true）：用户主动播放的
 * 音乐不因上车而中断。用户之后从车机手动按播放时，调用方先 [disarm] 再转发。
 *
 * 车机事件线程与主线程都会访问，方法内部串行化。
 */
internal class AutoPlaySuppressor(
    private val keyWindowMillis: Long = KEY_WINDOW_MILLIS,
    private val watchWindowMillis: Long = WATCH_WINDOW_MILLIS,
) {
    private var armedAtMs = -1L
    private var pausedOnce = false

    @Synchronized
    fun arm(
        alreadyPlaying: Boolean,
        nowMs: Long,
    ) {
        pausedOnce = false
        armedAtMs = if (alreadyPlaying) -1L else nowMs
    }

    @Synchronized
    fun disarm() {
        armedAtMs = -1L
        pausedOnce = false
    }

    /** 仍处于抑制观察窗口内。 */
    @Synchronized
    fun isArmed(nowMs: Long): Boolean = armedAtMs >= 0 && nowMs - armedAtMs <= watchWindowMillis

    /** 车机的自动播放键是否应丢弃（只在连接后的短窗口内）。 */
    @Synchronized
    fun shouldDropPlayKey(nowMs: Long): Boolean = armedAtMs >= 0 && nowMs - armedAtMs <= keyWindowMillis

    /** 媒体刚开始播放：返回 true 表示应暂停一次（每个连接窗口只触发一次）。 */
    @Synchronized
    fun shouldPauseStartedPlayback(nowMs: Long): Boolean {
        if (armedAtMs < 0 || pausedOnce) return false
        if (nowMs - armedAtMs > watchWindowMillis) return false
        pausedOnce = true
        return true
    }

    companion object {
        const val KEY_WINDOW_MILLIS = 5_000L
        const val WATCH_WINDOW_MILLIS = 15_000L
    }
}
