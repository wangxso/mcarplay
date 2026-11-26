package com.mcarplay.app.session

import android.app.Application
import com.mcarplay.app.carlife.CarLifeSessionRegistry
import com.mcarplay.app.session.StreamPipelineTestSupport.idrUnit
import com.mcarplay.app.session.StreamPipelineTestSupport.sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2g-1 I 层：把 CarLife 源的完整生命周期（认领 → 发布输出 → 入流 → 结束 → 新一轮）
 * 串起来，重点验证旧轮迟到 `onEnded` 与新一轮输出的隔离（B-18）。
 */
class StreamPipelineCarLifeIntegrationTest {
    private val ports = FakeStreamPorts()
    private val pipeline = StreamPipeline(ports)

    @Test
    fun firstRoundThenNewRoundKeepsNewSenderAliveOnStaleEnd() {
        // 第一轮：认领 → 发布输出 → IDR 入流
        val first = pipeline.newFormalSession(Application())
        val firstSender = sender(running = true)
        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, first))
        pipeline.publishOutput("r1", firstSender)
        pipeline.handleAccessUnit(first, idrUnit())
        assertEquals(1, pipeline.currentOutput()!!.gate.joins)

        // 第一轮正常结束
        pipeline.handleEnded(first, "first eof")
        assertNull(pipeline.currentFormalSession())
        assertFalse(firstSender.isRunning)

        // 第二轮：新租约、新 sender、新输出
        val second = pipeline.newFormalSession(Application())
        val secondSender = sender(running = true)
        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, second))
        pipeline.publishOutput("r2", secondSender)
        pipeline.handleAccessUnit(second, idrUnit())

        // 第一轮的迟到 onEnded 到达：只能走旧源分支
        pipeline.handleEnded(first, "late first eof")

        assertSame(secondSender, pipeline.outputSender())
        assertTrue(secondSender.isRunning)
        assertSame(second, pipeline.currentFormalSession())
        assertEquals("r2", pipeline.currentOutput()!!.attempt)
        assertTrue(ports.logs.any { it == "CARLIFE: 未接入推流的旧会话结束 - late first eof" })
    }

    @Test
    fun stopFormalClearsOutputAndReleasesLease() {
        val session = pipeline.newFormalSession(Application())
        val sender = sender(running = true)
        assertTrue(pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session))
        pipeline.publishOutput("a", sender)

        pipeline.stopFormal()

        assertNull(pipeline.currentFormalSession())
        assertNull(pipeline.outputSender())
        assertTrue("正式租约已释放，自检可以重新认领", pipeline.claim(CarLifeSessionRegistry.Owner.DIAGNOSTIC, session))
    }
}
