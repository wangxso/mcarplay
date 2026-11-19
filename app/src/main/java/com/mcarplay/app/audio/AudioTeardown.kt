package com.mcarplay.app.audio

import android.content.Context

/** 音频拆链归因需要的单一音频状态。 */
internal data class AudioTeardownState(
    val profile: AudioProfile?,
    val startedAtMs: Long,
    val receiverKey: String,
)

/** MIB3 看门狗当前状态；读取保持调用时刻语义。 */
internal data class Mib3LinkWatchdog(
    val armed: Boolean,
    val controlSessionDone: Boolean,
) {
    val shouldOwnTeardown: Boolean get() = armed && !controlSessionDone
}

/** 音频归因的记忆与 UI 边界；不因测试新增全局 hook 或 Android 依赖。 */
internal interface AudioTeardownMemory {
    fun recordWorking(
        context: Any?,
        receiverKey: String,
        profileKey: String,
    )

    fun recordRejected(
        context: Any?,
        receiverKey: String,
        profileKey: String,
        allCandidates: Set<String>,
    ): AudioProfileMemory.Entry

    fun load(
        context: Any?,
        receiverKey: String,
    ): AudioProfileMemory.Entry
}

/** 音频归因的文本边界；诊断文案与 UI 资源仍由编排层提供。 */
internal interface AudioTeardownOutput {
    fun linkTeardown(livedMillis: Long)

    fun working(
        profile: AudioProfile,
        livedMillis: Long,
    )

    fun rejected(
        profile: AudioProfile,
        livedMillis: Long,
        allDisabled: Boolean,
    )

    fun hint(allDisabled: Boolean)
}

/** [AudioTeardownMemory] 的真实 SharedPreferences/内存实现。 */
internal class DefaultAudioTeardownMemory : AudioTeardownMemory {
    override fun recordWorking(
        context: Any?,
        receiverKey: String,
        profileKey: String,
    ) {
        AudioProfileMemory.recordWorking(context as Context?, receiverKey, profileKey)
    }

    override fun recordRejected(
        context: Any?,
        receiverKey: String,
        profileKey: String,
        allCandidates: Set<String>,
    ): AudioProfileMemory.Entry {
        AudioProfileMemory.recordRejected(context as Context?, receiverKey, profileKey, allCandidates)
        return AudioProfileMemory.load(context as Context?, receiverKey)
    }

    override fun load(
        context: Any?,
        receiverKey: String,
    ): AudioProfileMemory.Entry = AudioProfileMemory.load(context as Context?, receiverKey)
}

/**
 * SessionController 音频拆链归因的窄封装。
 *
 * controller 只保留音频状态写入与真实叶子接线；helper 负责原控制流的顺序、
 * MIB3 看门狗短路、20 秒阈值和 rejected 后的提示分支。
 */
internal class AudioTeardown(
    private val probeMillis: Long,
    private val memory: AudioTeardownMemory = DefaultAudioTeardownMemory(),
    private val state: () -> AudioTeardownState,
    private val clearState: () -> Unit,
    private val watchdog: () -> Mib3LinkWatchdog?,
    private val nowMs: () -> Long,
    private val context: () -> Any?,
    private val allCandidateKeys: (String) -> Set<String>,
    private val output: AudioTeardownOutput,
) {
    fun attribute() {
        val profile = state().profile ?: return
        val startedAtMs = state().startedAtMs
        val receiverKey = state().receiverKey
        val lived = if (startedAtMs > 0) nowMs() - startedAtMs else -1L
        watchdog()?.let { watchdog ->
            if (watchdog.shouldOwnTeardown) {
                output.linkTeardown(lived)
                clear()
                return
            }
        }
        clear()
        if (startedAtMs == 0L || receiverKey.isBlank()) return
        val context = context()
        if (lived >= probeMillis) {
            memory.recordWorking(context, receiverKey, profile.key)
            output.working(profile, lived)
            return
        }
        val allKeys = allCandidateKeys(receiverKey)
        val after = memory.recordRejected(context, receiverKey, profile.key, allKeys)
        output.rejected(profile, lived, after.disabled)
        output.hint(after.disabled)
    }

    private fun clear() {
        clearState()
    }
}
