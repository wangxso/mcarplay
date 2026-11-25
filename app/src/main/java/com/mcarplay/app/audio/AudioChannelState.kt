package com.mcarplay.app.audio

/** 音频归因的三个独立 volatile 槽；发布在真正 start 前，清理保留接收端键。 */
internal class AudioChannelState : ActiveAudioSlots {
    @Volatile
    override var profile: AudioProfile? = null

    @Volatile
    override var receiverKey: String = ""

    @Volatile
    override var startedAtMs: Long = 0L
}
