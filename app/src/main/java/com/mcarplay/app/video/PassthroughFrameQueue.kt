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
 *    （[Outcome.REPLACED_STALE]），再把新关键帧入队。旧帧全部产生于这个 IDR 之前，
 *    丢掉它们不会造成参考帧缺失；新 IDR 因此成为唯一、最新的恢复边界，而不是把某个
 *    旧关键帧误当恢复点。被替换的帧不计入 `队满丢帧`（那个计数只表示入队失败）。
 *
 * **断流标记随帧入队**：丢帧之后第一个入队的帧带着 [Entry.discontinuityBefore]。标记与帧
 * 在同一个同步块里写入、由消费方随帧一起取出，因此不存在「帧已经可以被取走、标记还没打上」
 * 的窗口——消费方看到帧时一定也看到了它前面的断流，不会让新 IDR 先被当成正常帧喂进去、
 * 随后又因迟到的标记多等一个 IDR（2026-11-16 审查）。
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

    /** 队列中的一帧；[discontinuityBefore] 表示入队前已经丢过帧，解码链在此断开。 */
    data class Entry(
        val frame: ExternalVideoFrame,
        val discontinuityBefore: Boolean = false,
    )

    data class Offer(
        val outcome: Outcome,
        /** 本次为关键帧腾位而丢弃的已排队帧数。 */
        val evicted: Int = 0,
        /** 处理后的队列深度。 */
        val depth: Int = 0,
        /** 入队的这一帧是否携带断流标记（[Outcome.ACCEPTED] / [Outcome.REPLACED_STALE] 才有意义）。 */
        val discontinuityBefore: Boolean = false,
    )

    private val frames = LinkedBlockingQueue<Entry>(capacity)

    /** 已发生丢帧、但还没有后续帧入队时暂存的标记；下一个入队的帧继承它。 */
    private var armNextFrame = false

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    val depth: Int get() = frames.size

    /** 入队（非阻塞）；返回本次结果与为关键帧腾位而丢弃的旧帧数。 */
    fun offer(frame: ExternalVideoFrame): Offer =
        synchronized(this) {
            val admitted = Entry(frame, discontinuityBefore = armNextFrame)
            if (frames.offer(admitted)) {
                armNextFrame = false
                return@synchronized Offer(
                    Outcome.ACCEPTED,
                    depth = frames.size,
                    discontinuityBefore = admitted.discontinuityBefore,
                )
            }
            if (!frame.keyFrame) {
                // 丢弃的帧之后的所有帧都缺参考：标记留给第一个真正入队的帧。
                armNextFrame = true
                return@synchronized Offer(Outcome.DROPPED_FULL, depth = frames.size)
            }
            // 关键帧晚于队列里的所有帧：清掉旧帧不会破坏解码，反而让新 IDR 立即成为恢复点。
            val evicted = ArrayList<Entry>(capacity)
            frames.drainTo(evicted)
            frames.offer(Entry(frame, discontinuityBefore = true))
            armNextFrame = false
            Offer(Outcome.REPLACED_STALE, evicted.size, frames.size, discontinuityBefore = true)
        }

    /** 取一帧；超时返回 null（推流线程据此发 keepAlive）。标记随帧返回，不可分离。 */
    fun poll(timeoutMillis: Long): Entry? = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    fun clear() {
        synchronized(this) {
            frames.clear()
            armNextFrame = false
        }
    }

    /** 当前排队帧（仅诊断/测试；顺序即发送顺序）。 */
    fun snapshot(): List<Entry> =
        synchronized(this) {
            frames.toList()
        }

    /**
     * 是否还有「已丢帧但恢复点尚未被消费」的状态：队列里有带标记的帧，或标记还在等下一个帧。
     *
     * 测试/诊断用。透传路径不消费标记的语义已由「随帧取出」取代，读到的 true 表示
     * 恢复点仍在队列里等推流线程。
     */
    fun hasPendingDiscontinuity(): Boolean =
        synchronized(this) {
            armNextFrame || frames.any { it.discontinuityBefore }
        }

    companion object {
        /** 约 267 ms @30 fps / 133 ms @60 fps 的积压上界。 */
        const val DEFAULT_CAPACITY = 8
    }
}
