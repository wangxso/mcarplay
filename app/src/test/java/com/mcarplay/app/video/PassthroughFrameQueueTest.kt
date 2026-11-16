package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 透传队列的容量与溢出语义。
 *
 * 容量 8 是延迟上界（30 fps ≈ 267 ms）的来源；溢出行为决定恢复点：
 * 普通帧丢弃、关键帧替换旧帧，绝不能把更早的旧关键帧留在队首冒充新恢复边界。
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
        assertEquals(listOf(1, 2), queue.snapshot().map { it.timestampUs.toInt() })
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
        val remaining = queue.snapshot()
        assertEquals(1, remaining.size)
        assertTrue("The newest IDR must be the only queued frame", remaining.single().keyFrame)
        assertEquals(4, remaining.single().timestampUs.toInt())
    }

    @Test
    fun dequeuePreservesSubmissionOrder() {
        val queue = PassthroughFrameQueue(capacity = 4)
        queue.offer(keyFrame(1))
        queue.offer(pFrame(2))
        queue.offer(pFrame(3))

        assertEquals(listOf(1L, 2L, 3L), listOf(queue.poll(0)?.timestampUs, queue.poll(0)?.timestampUs, queue.poll(0)?.timestampUs))
        assertEquals(null, queue.poll(0))
    }

    @Test
    fun clearDropsEverythingOnShutdown() {
        val queue = PassthroughFrameQueue()
        repeat(4) { queue.offer(pFrame(it)) }

        queue.clear()

        assertEquals(0, queue.depth)
        assertTrue(queue.snapshot().isEmpty())
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
