package com.mcarplay.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * PR #78 复审 P2：画布尺寸与「是否转码」必须原子地一起更新。
 *
 * 回归背景：旧实现里 CarWith 源线程与转码线程用不同的锁。交错顺序
 * 「源线程读到未转码 → 转码线程发布固定画布 → 源线程用 SPS 尺寸覆盖画布」会让画布
 * 与编码器实际输出不一致，而 VideoConfig 快照本身仍是自洽的，于是实验把「未生效」
 * 误报成「已生效」。这些用例直接打在 [VideoCanvasState] 上——也就是两条真实路径
 * （`setVideoDimensions()` / `startTranscoding()` 发布）现在共用的那套同步规则。
 */
class VideoCanvasStateTest {
    private val sourceSize = 2288 to 1728
    private val encodedSize = 1920 to 1080

    @Test
    fun sourceSizeBeforeTranscodingUpdatesTheCanvas() {
        val canvas = VideoCanvasState(1280, 720)
        assertTrue(canvas.applySourceSize(sourceSize.first, sourceSize.second))
        assertEquals(VideoCanvasState.Size(sourceSize.first, sourceSize.second), canvas.read())
        assertFalse(canvas.isTranscoding())
    }

    @Test
    fun sourceSizeAfterTranscodeStaysOutOfTheEncodedCanvas() {
        val canvas = VideoCanvasState(1280, 720)
        assertTrue(canvas.publishEncodedCanvas(encodedSize.first, encodedSize.second))
        // 转码后画布归编码器所有：CarWith 源尺寸只能记录，不能覆盖。
        assertFalse(canvas.applySourceSize(sourceSize.first, sourceSize.second))
        assertEquals(VideoCanvasState.Size(encodedSize.first, encodedSize.second), canvas.read())
        assertTrue(canvas.isTranscoding())
    }

    @Test
    fun transcodedCanvasIsNotRepublished() {
        val canvas = VideoCanvasState(1280, 720)
        assertTrue(canvas.publishEncodedCanvas(encodedSize.first, encodedSize.second))
        // 第二次发布不覆盖（保持首次编码器尺寸）。
        assertFalse(canvas.publishEncodedCanvas(1024, 768))
        assertEquals(VideoCanvasState.Size(encodedSize.first, encodedSize.second), canvas.read())
    }

    /**
     * 交错顺序正是评审描述的那一种：源尺寸更新与转码发布从同一屏障同时出发，跑很多轮。
     *
     * 不变式：只要转码标记已置位，画布就必须是编码器尺寸。两种合法顺序都满足它——
     * 源尺寸先到（随后被编码器画布覆盖），或编码器画布先到（源尺寸被拒绝）；
     * 唯一被禁止的是「编码器已发布，画布却被源尺寸改回去」。
     *
     * 每轮只让两个线程各执行一次并共享屏障，避免自旋抢锁把线程饿死（那样测的是调度而非规则）。
     */
    @Test
    fun concurrentSourceUpdatesNeverClobberThePublishedEncodedCanvas() {
        val rounds = 5_000
        val violations = AtomicInteger(0)
        repeat(rounds) {
            val canvas = VideoCanvasState(1280, 720)
            val barrier = java.util.concurrent.CyclicBarrier(3)
            val source =
                Thread({
                    barrier.await()
                    canvas.applySourceSize(sourceSize.first, sourceSize.second)
                }).apply { isDaemon = true }
            val transcoder =
                Thread({
                    barrier.await()
                    canvas.publishEncodedCanvas(encodedSize.first, encodedSize.second)
                }).apply { isDaemon = true }
            try {
                source.start()
                transcoder.start()
                barrier.await()
                source.join(5_000)
                transcoder.join(5_000)
                assertFalse("源线程未退出", source.isAlive)
                assertFalse("转码线程未退出", transcoder.isAlive)
                val (size, transcoding) = canvas.readState()
                if (!transcoding) violations.incrementAndGet()
                if (size != VideoCanvasState.Size(encodedSize.first, encodedSize.second)) violations.incrementAndGet()
            } finally {
                // 断言失败/超时也不能留下等屏障的线程。
                source.interrupt()
                transcoder.interrupt()
            }
        }
        assertEquals("转码画布被源尺寸覆盖 $violations 次", 0, violations.get())
    }

    /** 已发布编码器画布后，持续的源尺寸更新不得改变它。 */
    @Test
    fun sourceUpdatesAfterPublishKeepTheEncodedCanvas() {
        val canvas = VideoCanvasState(1280, 720)
        assertTrue(canvas.publishEncodedCanvas(encodedSize.first, encodedSize.second))
        val stop = AtomicBoolean(false)
        val violations = AtomicInteger(0)
        val source =
            Thread({
                // 线程里的断言不会让测试失败，反而会静默杀死线程；改为记录计数。
                while (!stop.get()) {
                    canvas.applySourceSize(sourceSize.first, sourceSize.second)
                    if (canvas.read() != VideoCanvasState.Size(encodedSize.first, encodedSize.second)) {
                        violations.incrementAndGet()
                    }
                }
            }).apply { isDaemon = true }
        try {
            source.start()
            repeat(20_000) {
                if (canvas.read() != VideoCanvasState.Size(encodedSize.first, encodedSize.second)) {
                    violations.incrementAndGet()
                }
            }
        } finally {
            stop.set(true)
            source.join(5_000)
        }
        assertFalse("源线程未退出", source.isAlive)
        assertEquals("已发布画布被源尺寸改动 $violations 次", 0, violations.get())
        assertEquals(VideoCanvasState.Size(encodedSize.first, encodedSize.second), canvas.read())
    }

    /** 没有任何一次转码发布时，源尺寸更新必须照常生效（不能因为加锁变得不可用）。 */
    @Test
    fun sourceUpdatesKeepWorkingWhenNoTranscodeHappens() {
        val canvas = VideoCanvasState(1280, 720)
        repeat(100) { index ->
            val size = 640 + index * 2 to 480 + index * 2
            assertTrue(canvas.applySourceSize(size.first, size.second))
            assertEquals(VideoCanvasState.Size(size.first, size.second), canvas.read())
        }
        assertFalse(canvas.isTranscoding())
    }

    /** 纯决策函数与真实更新保持一致（防止两条路径各自演化出不同规则）。 */
    @Test
    fun decisionsMatchTheAppliedUpdates() {
        val canvas = VideoCanvasState(1280, 720)
        assertEquals(VideoCanvasState.SourceUpdate.APPLY, canvas.decideSourceUpdate(canvas.isTranscoding()))
        assertEquals(VideoCanvasState.CanvasUpdate.APPLY, canvas.decideCanvasUpdate(canvas.isTranscoding()))
        canvas.publishEncodedCanvas(encodedSize.first, encodedSize.second)
        assertEquals(VideoCanvasState.SourceUpdate.KEEP_TRANSCODED, canvas.decideSourceUpdate(canvas.isTranscoding()))
        assertEquals(VideoCanvasState.CanvasUpdate.KEEP, canvas.decideCanvasUpdate(canvas.isTranscoding()))
    }

    /**
     * PR #78 复审 P1：画布更新必须只依赖短锁，**不能**被可能阻塞在网络写入上的生命周期锁拖住。
     *
     * 模拟评审描述的真实锁链：转码发布持有 lifecycleLock 等 writeLock，而 writeLock 正被一个
     * 永不返回的 socket 写占用。此时 CarWith 视频读取线程若还要等 lifecycleLock，就会永远卡住。
     * 这里用一把「已被占用的 lifecycleLock」复现该状态，断言源尺寸更新仍能立即返回。
     */
    @Test
    fun sourceSizeUpdateReturnsWhileTheLifecycleLockIsHeldByABlockedWriter() {
        val lifecycleLock = Any()
        val writeLock = Any()
        val writeEntered = CountDownLatch(1)
        val lifecycleHeld = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val canvas = VideoCanvasState(1280, 720)

        // 阻塞中的 socket 写：占着 writeLock 不放手（真机满窗口时为真）。
        val blockedWriter =
            Thread({
                synchronized(writeLock) {
                    writeEntered.countDown()
                    releaseWrite.await()
                }
            }).apply { isDaemon = true }
        // 转码发布方：先拿 lifecycleLock（发闩锁），再等 writeLock——正是把 lifecycleLock 占住的形态。
        val publishing =
            Thread({
                synchronized(lifecycleLock) {
                    lifecycleHeld.countDown()
                    synchronized(writeLock) {
                        canvas.publishEncodedCanvas(encodedSize.first, encodedSize.second)
                    }
                }
            }).apply { isDaemon = true }

        // 断言失败/超时也必须释放闩锁并回收线程：两个线程都在等 releaseWrite，
        // 否则 Gradle 测试进程会一直挂住（评审 P2）。线程同时设为 daemon 作为二重保险。
        try {
            blockedWriter.start()
            assertTrue("阻塞写线程未能占住 writeLock", writeEntered.await(5, TimeUnit.SECONDS))
            publishing.start()
            assertTrue("转码发布方未能持有 lifecycleLock", lifecycleHeld.await(5, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            val applied = canvas.applySourceSize(sourceSize.first, sourceSize.second)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("源尺寸更新必须在不转码时生效", applied)
            assertTrue("源尺寸更新耗时 ${elapsedMs}ms——不应等待被 socket 写占用的锁", elapsedMs < 500)
            assertEquals(VideoCanvasState.Size(sourceSize.first, sourceSize.second), canvas.read())
        } finally {
            releaseWrite.countDown()
            blockedWriter.join(5_000)
            publishing.join(5_000)
        }
        assertFalse("阻塞写线程未退出", blockedWriter.isAlive)
        assertFalse("转码发布线程未退出", publishing.isAlive)
    }
}
