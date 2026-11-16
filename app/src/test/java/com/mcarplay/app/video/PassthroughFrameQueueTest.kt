package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 透传队列的容量与溢出语义。
 *
 * 容量 8 是延迟上界（30 fps ≈ 267 ms）的来源；溢出行为决定恢复点：
 * 普通帧丢弃、关键帧替换旧帧，绝不能把更早的旧关键帧留在队首冒充新恢复边界。
 * 丢帧后的断流标记必须与后续帧一起入队、随帧取出——否则消费方可能先拿到新 IDR、
 * 再被迟到的标记逼着多等一个 IDR（2026-11-16 审查）。
 */
class PassthroughFrameQueueTest {
    private fun keyFrame(tag: Int): ExternalVideoFrame =
        ExternalVideoFrame(byteArrayOf(0x65, tag.toByte()), keyFrame = true, timestampUs = tag.toLong())

    private fun pFrame(tag: Int): ExternalVideoFrame =
        ExternalVideoFrame(byteArrayOf(0x41, tag.toByte()), keyFrame = false, timestampUs = tag.toLong())

    @Test
    fun defaultCapacityBoundsTheBacklog() {
        assertEquals(8, PassthroughFrameQueue.DEFAULT_CAPACITY)
        val queue = PassthroughFrameQueue()
        repeat(
            PassthroughFrameQueue.DEFAULT_CAPACITY,
        ) { assertEquals(PassthroughFrameQueue.Outcome.ACCEPTED, queue.offer(pFrame(it)).outcome) }
        assertEquals(PassthroughFrameQueue.DEFAULT_CAPACITY, queue.depth)
    }

    @Test
    fun fullQueueDropsAPFrameAndKeepsTheBacklogIntact() {
        val queue = PassthroughFrameQueue(capacity = 2)
        queue.offer(pFrame(1))
        queue.offer(pFrame(2))

        val offer = queue.offer(pFrame(3))

        assertEquals(PassthroughFrameQueue.Outcome.DROPPED_FULL, offer.outcome)
        assertEquals(2, offer.depth)
        assertEquals(0, offer.evicted)
        assertEquals(listOf(1, 2), queue.snapshot().map { it.frame.timestampUs.toInt() })
    }

    @Test
    fun keyFrameReplacesStaleFramesSoTheNewIdrIsTheRecoveryPoint() {
        val queue = PassthroughFrameQueue(capacity = 3)
        queue.offer(pFrame(1))
        queue.offer(keyFrame(2))
        queue.offer(pFrame(3))

        val offer = queue.offer(keyFrame(4))

        assertEquals(PassthroughFrameQueue.Outcome.REPLACED_STALE, offer.outcome)
        assertEquals(3, offer.evicted)
        assertEquals(1, offer.depth)
        assertTrue("the replacement IDR must carry the discontinuity", offer.discontinuityBefore)
        val remaining = queue.snapshot()
        assertEquals(1, remaining.size)
        assertTrue("The newest IDR must be the only queued frame", remaining.single().frame.keyFrame)
        assertEquals(
            4,
            remaining
                .single()
                .frame.timestampUs
                .toInt(),
        )
        assertTrue("the newest IDR replaces stale frames, so it is the recovery point", remaining.single().discontinuityBefore)
    }

    @Test
    fun dequeuePreservesSubmissionOrder() {
        val queue = PassthroughFrameQueue(capacity = 4)
        queue.offer(keyFrame(1))
        queue.offer(pFrame(2))
        queue.offer(pFrame(3))

        assertEquals(
            listOf(1L, 2L, 3L),
            listOf(queue.poll(0)?.frame?.timestampUs, queue.poll(0)?.frame?.timestampUs, queue.poll(0)?.frame?.timestampUs),
        )
        assertEquals(null, queue.poll(0))
    }

    @Test
    fun clearDropsEverythingOnShutdown() {
        val queue = PassthroughFrameQueue()
        repeat(4) { queue.offer(pFrame(it)) }

        queue.clear()

        assertEquals(0, queue.depth)
        assertTrue(queue.snapshot().isEmpty())
        assertFalse("clearing must also forget an armed discontinuity", queue.hasPendingDiscontinuity())
    }

    @Test
    fun aDroppedFrameArmsTheDiscontinuityForTheNextQueuedFrame() {
        val queue = PassthroughFrameQueue(capacity = 2)
        queue.offer(keyFrame(1))
        queue.offer(pFrame(2))

        // 队列满且掉的是 P 帧：被丢弃的那一帧本身不回队，但后续帧缺参考。
        assertEquals(PassthroughFrameQueue.Outcome.DROPPED_FULL, queue.offer(pFrame(3)).outcome)
        assertTrue("an overflow with a dropped P frame must arm the next frame", queue.hasPendingDiscontinuity())

        // 下一个真正入队的帧带走标记，且只带走一次。
        val recovered = queue.offer(keyFrame(4))
        assertEquals(PassthroughFrameQueue.Outcome.REPLACED_STALE, recovered.outcome)
        val entry = queue.poll(0)!!
        assertTrue("the recovery IDR must be flagged as a discontinuity", entry.discontinuityBefore)
        assertFalse("the flag must travel with exactly one frame", queue.hasPendingDiscontinuity())

        val next = queue.offer(pFrame(5))
        assertEquals(PassthroughFrameQueue.Outcome.ACCEPTED, next.outcome)
        assertFalse("a later frame must not inherit a consumed discontinuity", queue.poll(0)!!.discontinuityBefore)
    }

    @Test
    fun theDiscontinuityIsPublishedAtomicallyWithTheFrameItBelongsTo() {
        // 审查点：入队与置位分两步时，消费方可能先取走帧、再看到标记。
        // 现在的契约是「帧与标记同一同步块写入」，所以只要队列非空，标记必然已就绪。
        val queue = PassthroughFrameQueue(capacity = 1)
        queue.offer(keyFrame(1))
        queue.offer(pFrame(2)) // 丢弃，armed
        assertEquals(PassthroughFrameQueue.Outcome.REPLACED_STALE, queue.offer(keyFrame(3)).outcome)

        // 模拟消费方在入队返回的同一时刻立即取帧：标记必须已经从帧上可见。
        assertTrue(queue.snapshot().single().discontinuityBefore)
        assertTrue(queue.poll(0)!!.discontinuityBefore)
    }

    @Test
    fun aConsumerNeverSeesARecoveryFrameWithoutItsDiscontinuityUnderContention() {
        // 强制交错：队列已满且已因丢 P 帧而 armed；消费线程随即开始轮询，
        // 生产线程随后换入 IDR。旧实现先入队（唤醒消费方）再置位，消费方可能先拿到
        // 未标记的新 IDR、事后再看到标记，从而多等一个 IDR。
        repeat(200) {
            val queue = PassthroughFrameQueue(capacity = 1)
            queue.offer(pFrame(1))
            assertEquals(PassthroughFrameQueue.Outcome.DROPPED_FULL, queue.offer(pFrame(2)).outcome)
            assertTrue(queue.hasPendingDiscontinuity())

            val sawFlagged =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val sawUnflaggedKeyFrame =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val producerDone = java.util.concurrent.CountDownLatch(1)
            val consumer =
                Thread {
                    // 结束条件是「生产者已完成且队列已空」，不靠 poll 超时判定，
                    // 否则机器负载高时消费者会先超时退出（曾偶发失败）。
                    while (producerDone.count > 0 || queue.depth > 0) {
                        val entry = queue.poll(50) ?: continue
                        if (entry.frame.keyFrame) {
                            if (entry.discontinuityBefore) sawFlagged.set(true) else sawUnflaggedKeyFrame.set(true)
                        }
                    }
                }
            consumer.start()
            assertEquals(PassthroughFrameQueue.Outcome.REPLACED_STALE, queue.offer(keyFrame(3)).outcome)
            producerDone.countDown()
            consumer.join(2_000)
            assertFalse("consumer must terminate", consumer.isAlive)
            assertTrue("the recovery IDR must be flagged when it becomes visible", sawFlagged.get())
            assertFalse("a key frame must never be visible without its discontinuity", sawUnflaggedKeyFrame.get())
        }
    }

    @Test
    fun concurrentProducersAndOneConsumerKeepAFrameCountInvariant() {
        val queue = PassthroughFrameQueue(capacity = 8)
        val producers = 4
        val perProducer = 500
        val accepted =
            java.util.concurrent.atomic
                .AtomicInteger()
        val producersDone = java.util.concurrent.CountDownLatch(producers)
        val threads =
            (0 until producers).map { producer ->
                Thread {
                    repeat(perProducer) { index ->
                        val outcome = queue.offer(pFrame(producer * 1_000 + index)).outcome
                        if (outcome == PassthroughFrameQueue.Outcome.ACCEPTED) accepted.incrementAndGet()
                    }
                    producersDone.countDown()
                }
            }
        val drained =
            java.util.concurrent.atomic
                .AtomicInteger()
        val consumer =
            Thread {
                // 生产者全部结束后再排空：poll 的超时上限无所谓，条件用 latch 判断。
                while (producersDone.count > 0 || queue.depth > 0) {
                    queue.poll(10)?.let { drained.incrementAndGet() }
                }
            }
        threads.forEach { it.start() }
        consumer.start()
        threads.forEach { it.join(5_000) }
        consumer.join(5_000)
        assertTrue("consumer must not hang", !consumer.isAlive)
        assertEquals("every accepted frame must be delivered exactly once", accepted.get(), drained.get())
        assertEquals(0, queue.depth)
    }
}
