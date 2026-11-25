package com.mcarplay.app.session

import android.app.Application
import com.mcarplay.app.carlife.CarLifeAudioEvent
import com.mcarplay.app.carlife.CarLifeChannel
import com.mcarplay.app.carlife.CarLifeSessionRegistry
import com.mcarplay.app.session.StreamPipelineTestSupport.accessUnit
import com.mcarplay.app.session.StreamPipelineTestSupport.annexB
import com.mcarplay.app.session.StreamPipelineTestSupport.idr
import com.mcarplay.app.session.StreamPipelineTestSupport.idrUnit
import com.mcarplay.app.session.StreamPipelineTestSupport.sender
import com.mcarplay.app.session.StreamPipelineTestSupport.setSessionRunning
import com.mcarplay.app.session.StreamPipelineTestSupport.slice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2g-1 J 层：`StreamPipeline` 的 CarLife 源、租约与输出槽。
 *
 * 直接驱动组件内的 internal 事件处理方法（生产接线仍是 [StreamPipeline.newFormalSession]），
 * 用真实 [com.mcarplay.app.video.VideoSender] 观察帧接收与停止；不触达设备或实车。
 */
class StreamPipelineCarLifeTest {
    private val ports = FakeStreamPorts()
    private val pipeline = StreamPipeline(ports)

    @Test
    fun accessUnitJoinsOnIdrAndForwardsToSender() {
        val session = pipeline.newFormalSession(Application())
        val sender = sender(running = true)
        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session))
        pipeline.publishOutput("a", sender)

        pipeline.handleAccessUnit(session, idrUnit())

        assertEquals(1, pipeline.currentOutput()!!.gate.joins)
        assertEquals(1, ports.sourceFrames)
        assertTrue(ports.logs.any { it.startsWith("CARLIFE: 入流门控第 1 次接入") })
        assertTrue(ports.notes.any { it.second == "CARWITH_VIDEO_FORMAT 1280x720" })
        assertTrue(ports.logs.any { it == "CARLIFE: 实际视频尺寸 1280x720" })
    }

    @Test
    fun framesBeforeIdrAreDroppedUntilJoin() {
        val session = pipeline.newFormalSession(Application())
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.publishOutput("a", sender(running = true))

        pipeline.handleAccessUnit(session, accessUnit(annexB(slice), keyFrame = false))
        assertEquals(0, pipeline.currentOutput()!!.gate.joins)
        assertEquals(0, ports.sourceFrames)

        pipeline.handleAccessUnit(session, idrUnit())
        assertEquals(1, pipeline.currentOutput()!!.gate.joins)
        assertEquals(1L, pipeline.currentOutput()!!.gate.droppedBeforeLastJoin)
    }

    @Test
    fun missingParameterSetsAreFilledFromCacheAndReported() {
        val session = pipeline.newFormalSession(Application())
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.publishOutput("a", sender(running = true))

        // IDR 帧内只有 0x65，SPS/PPS 依赖 unit 缓存补齐
        pipeline.handleAccessUnit(session, accessUnit(annexB(idr), keyFrame = true))

        assertEquals("SPS+PPS", pipeline.currentOutput()!!.gate.filledAtLastJoin)
        assertTrue(ports.logs.any { it.contains("补参数集=SPS+PPS") })
    }

    @Test
    fun submitFailureResetsGateAndWaitsForNextIdr() {
        val session = pipeline.newFormalSession(Application())
        // 未启动的 sender：submitExternalFrame 恒为 false，模拟队列拒绝
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.publishOutput("a", sender(running = false))

        pipeline.handleAccessUnit(session, idrUnit())
        val output = pipeline.currentOutput()!!
        assertEquals(1, output.gate.joins)
        assertEquals(1, ports.sourceFrames)

        // 门控已被 reset：后续 P 帧继续等待 IDR，不再计入源帧
        pipeline.handleAccessUnit(session, accessUnit(annexB(slice), keyFrame = false))
        assertEquals(1, pipeline.currentOutput()!!.gate.joins)
        assertEquals(1, ports.sourceFrames)

        pipeline.handleAccessUnit(session, idrUnit())
        assertEquals(2, pipeline.currentOutput()!!.gate.joins)
    }

    @Test
    fun staleSourceEndedDoesNotStopCurrentSenderOrOutput() {
        val stale = pipeline.newFormalSession(Application())
        val current = pipeline.newFormalSession(Application())
        val currentSender = sender(running = true)
        // 新一轮先拿 lease：旧轮 lease 必须已释放，才可能被新轮占用
        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, stale))
        assertTrue(pipeline.release(stale))
        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, current))
        pipeline.publishOutput("new", currentSender)

        pipeline.handleEnded(stale, "peer closed")

        assertSame(currentSender, pipeline.outputSender())
        assertTrue("旧源迟到结束绝不能停掉新一轮 sender", currentSender.isRunning)
        assertSame(current, pipeline.currentFormalSession())
        assertTrue(ports.logs.any { it == "CARLIFE: 未接入推流的旧会话结束 - peer closed" })
        assertFalse(ports.logs.any { it == "CARLIFE: 会话结束 - peer closed" })
        assertTrue("旧源分支不得写时间线结束", ports.finishes.isEmpty())
    }

    @Test
    fun currentSourceEndedStopsSenderAndReleasesLease() {
        val session = pipeline.newFormalSession(Application())
        val sender = sender(running = true)
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.publishOutput("a", sender)

        pipeline.handleEnded(session, "eof")

        // 基线语义：onEnded 停发送端并释放租约，但输出槽由 VideoEndedHandling 负责清理
        assertSame(sender, pipeline.outputSender())
        assertNull(pipeline.currentFormalSession())
        assertFalse(sender.isRunning)
        assertEquals(1, ports.finishes.size)
        assertEquals("a", ports.finishes.single().attempt)
        assertTrue(ports.logs.any { it == "CARLIFE: 会话结束 - eof" })
    }

    @Test
    fun leaseIsExactAndFormalDiagnosticMutuallyExclusive() {
        val formal = pipeline.newFormalSession(Application())
        val diagnostic = pipeline.newFormalSession(Application())

        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, formal))
        assertTrue("同一租约重复认领应幂等成功", pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, formal))
        assertFalse("正式与自检共享互斥", pipeline.claim(CarLifeSessionRegistry.Owner.DIAGNOSTIC, diagnostic))
        assertFalse("错误 token 释放必须失败", pipeline.release(diagnostic))
        assertSame(formal, pipeline.currentFormalSession())

        assertTrue(pipeline.release(formal))
        assertNull(pipeline.currentFormalSession())
        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.DIAGNOSTIC, diagnostic))
        assertNull("未运行的诊断会话不对外可见", pipeline.runningDiagnosticSession())

        setSessionRunning(diagnostic, true)
        assertSame(diagnostic, pipeline.runningDiagnosticSession())
        assertFalse("诊断会话运行不代表正式会话活跃", pipeline.isFormalActive())
    }

    @Test
    fun outputPublishAndClearAreIdentityScoped() {
        val first = sender()
        val second = sender()

        pipeline.publishOutput("a", first)
        assertEquals("a", pipeline.currentOutput()!!.attempt)
        assertSame(first, pipeline.outputSender())

        pipeline.clearOutputIf(second)
        assertSame("sender 不匹配时不得清空", first, pipeline.outputSender())

        pipeline.clearOutputIf(first)
        assertNull(pipeline.outputSender())

        pipeline.publishOutput("b", second)
        pipeline.clearOutput()
        assertNull(pipeline.currentOutput())
    }

    @Test
    fun audioEventsFeedTimelineOnlyForCurrentSession() {
        val session = pipeline.newFormalSession(Application())
        val stale = pipeline.newFormalSession(Application())
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.publishOutput("a", sender())

        pipeline.handleAudioEvent(session, CarLifeChannel.MEDIA, CarLifeAudioEvent.Init(48_000, 2, 16))
        assertTrue(
            ports.notes.any {
                it.second == "CARWITH_AUDIO_FORMAT channel=MEDIA rate=48000 channels=2 bits=16"
            },
        )

        pipeline.handleAudioEvent(session, CarLifeChannel.MEDIA, CarLifeAudioEvent.Pcm(ByteArray(8), 48_000, 2))
        assertEquals(listOf(Triple("a", false, 8)), ports.media)

        val notesBefore = ports.notes.size
        pipeline.handleAudioEvent(stale, CarLifeChannel.MEDIA, CarLifeAudioEvent.Init(44_100, 1, 16))
        assertEquals("旧会话的音频事件不得入时间线", notesBefore, ports.notes.size)
    }

    @Test
    fun accessUnitFromStaleSessionIsIgnored() {
        val current = pipeline.newFormalSession(Application())
        val stale = pipeline.newFormalSession(Application())
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, current)
        pipeline.publishOutput("a", sender(running = true))

        pipeline.handleAccessUnit(stale, idrUnit())

        assertEquals(0, pipeline.currentOutput()!!.gate.joins)
        assertEquals(0, ports.sourceFrames)
    }
}
