package com.mcarplay.app.video

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 透传帧的有界队列（容量 [DEFAULT_CAPACITY] = 8）。
 *
 * 为什么是 8：64 帧在 30 fps 下允许约 2.1 s 积压。车机或链路变慢时旧实现不丢帧，而是越排越长，
 * 表现为「延迟越来越大」——比丢帧更难排查、体感也更差。8 帧给出可计算的上界：30 fps ≈ 267 ms，
 * 60 fps ≈ 133 ms。超过上界就丢帧并重新等 IDR，延迟不再累积。
 *
 * 两种溢出结果：
 *  - 普通帧：入队失败，[Outcome.DROPPED_FULL]。调用方（`SessionController`）会重置入流门控、
 *    等下一个 IDR；[VideoSender] 另在后台补要一次关键帧。
 *  - 关键帧：**不能丢**——它是恢复解码链的唯一入口。队列满时先清掉已排队的旧帧
 *    （[Outcome.REPLACED_STALE]），再把新关键帧放到队首。旧帧全部产生于这个 IDR 之前，
 *    丢掉它们不会造成参考帧缺失；新 IDR 因此成为唯一、最新的恢复边界，而不是把某个
 *    旧关键帧误当恢复点。被替换的帧不计入 `队满丢帧`（那个计数只表示入队失败）。
 *
 * 入队方是 CarLife 的读线程，出队方是推流线程；本类只做内存操作，不做任何 I/O。
 */
internal class PassthroughFrameQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    enum class Outcome {
        /** 直接入队。 */
        ACCEPTED,

        /** 队列满且新帧是关键帧：旧帧被替换，关键帧已入队。 */
        REPLACED_STALE,

        /** 队列满且新帧不是关键帧：需要等下一个 IDR。 */
        DROPPED_FULL,
    }

    data class Offer(
        val outcome: Outcome,
        /** 本次为关键帧腾位而丢弃的已排队帧数。 */
        val evicted: Int = 0,
        /** 处理后的队列深度。 */
        val depth: Int = 0,
    )

    private val frames = LinkedBlockingQueue<ExternalVideoFrame>(capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    val depth: Int get() = frames.size

    /** 入队（非阻塞）；返回本次结果与为关键帧腾位而丢弃的旧帧数。 */
    fun offer(frame: ExternalVideoFrame): Offer =
        synchronized(this) {
            if (frames.offer(frame)) return@synchronized Offer(Outcome.ACCEPTED, depth = frames.size)
            if (!frame.keyFrame) return@synchronized Offer(Outcome.DROPPED_FULL, depth = frames.size)
            // 关键帧晚于队列里的所有帧：清掉旧帧不会破坏解码，反而让新 IDR 立即成为恢复点。
            val evicted = ArrayList<ExternalVideoFrame>(capacity)
            frames.drainTo(evicted)
            frames.offer(frame)
            Offer(Outcome.REPLACED_STALE, evicted.size, frames.size)
        }

    /** 取一帧；超时返回 null（推流线程据此发 keepAlive）。 */
    fun poll(timeoutMillis: Long): ExternalVideoFrame? = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    fun clear() {
        frames.clear()
    }

    /** 当前排队帧（仅诊断/测试；顺序即发送顺序）。 */
    fun snapshot(): List<ExternalVideoFrame> = frames.toList()

    companion object {
        /** 约 267 ms @30 fps / 133 ms @60 fps 的积压上界。 */
        const val DEFAULT_CAPACITY = 8
    }
}
