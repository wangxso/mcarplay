package com.mcarplay.app.audio

/**
 * 外部 PCM 源（CarWith 原生音频）的发包节拍：按墙钟排帧槽，上游断流时补静音，
 * 让 RTP 时间戳始终跟墙钟走。纯逻辑，时钟由调用方传入（单测可注入）。
 *
 * 问题（2026-10-30 WinPlay 1.2.5 / AAC-LC 48kHz / latency 250ms 台架）：旧节拍只在
 * 「有 PCM 才发包」，RTP 时间戳按已发样本数累加；上游停顿超过 250ms 时又把发送时刻
 * 重置为当前时刻。于是每次断流都会在时间轴上留下一个永久的洞：导航提示后丢 500ms
 * 音乐的版本，第一次提示后接收端就把之后每个包都判为迟到，整个会话无声，而手机侧
 * 照常按 48kHz 推流。
 *
 * 做法：第一个真实帧发出后，帧槽 k 的截止时刻固定为 `起点 + k × 帧时长`。
 *  - 截止时刻到了且有整帧 → 发真实帧（[Step.Send]）；还没到 → 等（[Step.Wait]），
 *    与旧节拍一样按实时速率发，队列吸收突发。
 *  - 截止时刻已过 [fillAfterNanos] 仍没有整帧 → 补一帧静音（[Step.Fill]），并连续补到
 *    追上墙钟；断流期间之后的帧槽一到点就补。上游恢复时若仍落后一帧以上，先把落后的
 *    帧槽补完再发真实帧，真实帧因此落回准点的帧槽。宽限内的抖动不补静音，只是晚发，
 *    否则晚到的数据会在队列里永久积压。静音和真实 PCM 走同一条编码/加密路径，
 *    接收端看到的是一条不断的流。
 *  - 发送线程本身被拖住超过 [maxLateNanos]（不是上游断流）→ 一次跳过落后的帧槽
 *    （[Step.Skip]），调用方让 seq 与时间戳一起前进，等同网络丢了这些包，避免成百包
 *    补发的突发。
 *
 * 为什么补静音而不是恢复时把时间戳跳过断流时长：
 *  - 采集通路（AudioPlaybackCapture）本来就是连续送样本、没声音时送全零，奥迪与
 *    Honda/Alpine 真车出声的都是这种连续流；补静音让外部 PCM 通路与之同形。
 *  - 同步包每秒把「当前时间戳」配上「当前 NTP」（[AudioSyncPacket]）。断流期间时间戳
 *    不动而 NTP 照走，锚点自相矛盾；补静音后两者始终一致。
 *  - seq 连续而时间戳跳变、或在没有 FLUSH 时置 Marker，接收端如何处理没有任何证据；
 *    补静音不引入新的线上形态。
 *
 * CarWith 的显式 MEDIA STOP/PAUSE 不单独处理，同样补静音：换曲时 CarWith 发
 * STOP/INIT，中间的空档可能就在亚秒级，正是会让 WinPlay 卡在「每包迟到」的区间；
 * WinPlay 在 8.3 秒暂停后能重新对齐是一次观察，阈值未知，其他接收端没有证据。
 * 只有第一个真实帧之后才开始补（[started]）：会话建立到 CarWith 首帧之间仍不发包，
 * 首包 Marker 与「共发 0 包」的诊断含义不变。
 */
internal class ExternalAudioPacer(
    /** 每帧样本数（AAC-LC 1024，PCM 352）。 */
    private val samplesPerFrame: Int,
    private val sampleRate: Int,
    /** 截止时刻过后再等多久才补静音。 */
    private val fillAfterNanos: Long = FILL_AFTER_NANOS,
    /** 落后超过这个时长就跳过帧槽而不是逐帧追赶。 */
    private val maxLateNanos: Long = MAX_LATE_NANOS,
) {
    companion object {
        /**
         * 补静音的宽限：CarWith MEDIA 每块 7056B（44.1kHz 立体声约 40ms，见
         * `docs/CARWITH-ENTRY-FINDINGS.md` 2026-10-28 WinPlay 复测），宽限取其 3 倍以吸收
         * 正常抖动；同时远小于 WinPlay 协商的 250ms 延迟，补上的帧仍赶得上播放。
         */
        const val FILL_AFTER_NANOS = 120_000_000L

        /** 发送线程落后超过 1 秒时跳过帧槽（补发的包早已赶不上接收端的播放窗口）。 */
        const val MAX_LATE_NANOS = 1_000_000_000L

        /** 尚未收到第一个真实帧时的轮询间隔（与旧外部循环的 100ms 一致）。 */
        const val IDLE_WAIT_NANOS = 100_000_000L

        private const val NANOS_PER_SECOND = 1_000_000_000L
    }

    sealed interface Step {
        /** 等这么久再来问（有整帧时是等截止时刻，没有时是等 PCM 或补静音时刻）。 */
        data class Wait(
            val nanos: Long,
        ) : Step

        /** 发一个真实帧。 */
        data object Send : Step

        /** 发一帧静音。 */
        data object Fill : Step

        /** 跳过 [frames] 个帧槽：seq 与 RTP 时间戳前进同样帧数，不发包。 */
        data class Skip(
            val frames: Long,
        ) : Step
    }

    init {
        require(samplesPerFrame > 0 && sampleRate > 0) { "samplesPerFrame/sampleRate 必须为正" }
        require(fillAfterNanos in 0..maxLateNanos) { "fillAfterNanos 必须在 0..maxLateNanos 之间" }
    }

    /** 是否已发出第一个真实帧（之前不补静音）。 */
    var started = false
        private set

    /** 已占用的帧槽数（真实 + 静音 + 跳过）；× [samplesPerFrame] = 墙钟对应的样本数。 */
    var slots = 0L
        private set

    var filledFrames = 0L
        private set

    var skippedFrames = 0L
        private set

    private var startAt = 0L

    /** 上一个帧槽补的是静音（断流中）：此时不再等宽限，帧槽一到就补。 */
    private var filling = false

    /** 帧时长（向下取整，只用于估算跳过的帧数）。 */
    private val frameNanos = samplesPerFrame * NANOS_PER_SECOND / sampleRate

    /** 帧槽 [slot] 的截止时刻：按总样本数换算，长时间运行也不累积取整误差。 */
    fun deadline(slot: Long): Long {
        val samples = slot * samplesPerFrame
        return startAt + samples / sampleRate * NANOS_PER_SECOND + samples % sampleRate * NANOS_PER_SECOND / sampleRate
    }

    /**
     * 决定下一步。[now] 为单调时钟纳秒；[frameReady] 表示此刻已有一整帧真实 PCM。
     * 返回 [Step.Send]/[Step.Fill]/[Step.Skip] 时帧槽已经计入，调用方必须照做。
     */
    fun next(
        now: Long,
        frameReady: Boolean,
    ): Step {
        if (!started) {
            if (!frameReady) return Step.Wait(IDLE_WAIT_NANOS)
            started = true
            startAt = now
        }
        val late = now - deadline(slots)
        if (late < 0) return Step.Wait(-late)
        if (late > maxLateNanos) {
            // 跳到「落后不足一帧」的帧槽
            var frames = late / frameNanos
            while (frames > 0 && deadline(slots + frames) > now) frames--
            if (frames > 0) {
                slots += frames
                skippedFrames += frames
                return Step.Skip(frames)
            }
        }
        // 断流后上游恢复时按实时速率从「现在」接着送，不会补回断流期间的样本；
        // 先把落后的帧槽也补成静音，真实帧才能落在准点的帧槽上
        if (frameReady && !(filling && late >= frameNanos)) {
            filling = false
            slots++
            return Step.Send
        }
        if (filling || late >= fillAfterNanos) {
            filling = true
            slots++
            filledFrames++
            return Step.Fill
        }
        return Step.Wait(fillAfterNanos - late)
    }
}
