package com.mcarplay.app.session

import android.app.Application
import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.carlife.CarLifeSession
import com.mcarplay.app.carlife.CarLifeSessionRegistry
import com.mcarplay.app.session.StreamPipelineTestSupport.cipher
import com.mcarplay.app.session.StreamPipelineTestSupport.client
import com.mcarplay.app.session.StreamPipelineTestSupport.plan
import com.mcarplay.app.session.StreamPipelineTestSupport.sender
import com.mcarplay.app.session.StreamPipelineTestSupport.setSenderRunning
import com.mcarplay.app.video.VideoSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Phase 2g-2 J 层：`StreamPipeline` 的发送端槽与主屏流重建三态/原子回滚。
 *
 * 端口全部为假实现；只在需要 `openDataPort()` 成功的分支起一个本机回环 [ServerSocket]，
 * 不触达设备或实车。真实 [VideoSender] 用来观察停止与身份。
 */
class StreamPipelineScreenTest {
    private val ports = FakeStreamPorts()
    private val pipeline = StreamPipeline(ports)
    private val client = client()
    private val hooks = RecordingScreenHooks()
    private val session = pipeline.newFormalSession(Application())

    @Test
    fun senderSlotReadsAndWritesThroughComponent() {
        assertNull(pipeline.sender)
        val first = sender()
        pipeline.sender = first
        assertSame(first, pipeline.sender)
        pipeline.sender = null
        assertNull(pipeline.sender)
    }

    @Test
    fun abortsBeforeAnySideEffectWhenScopeInactive() {
        val port = FakeRecoveryPort().apply { scopeActive = false }
        val original = prepare(port)

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertSame(original, pipeline.sender)
        assertSame(original, pipeline.currentOutput()!!.sender)
        assertEquals(emptyList<String>(), port.calls)
        assertFalse(original.isRunning)
    }

    @Test
    fun abortsWhenOutputSlotIsEmpty() {
        val port = FakeRecoveryPort()
        val original = sender()
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.sender = original
        pipeline.installRecovery(plan(hooks), client, { session }, null, "display", "a", port)

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertSame(original, pipeline.sender)
        assertNull(pipeline.currentOutput())
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun abortsWhenSenderSlotIsEmpty() {
        val port = FakeRecoveryPort()
        val original = sender()
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.publishOutput("a", original)
        pipeline.installRecovery(plan(hooks), client, { session }, null, "display", "a", port)

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertNull(pipeline.sender)
        assertSame(original, pipeline.currentOutput()!!.sender)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun abortsWhenClientIsNoLongerCurrent() {
        val port = FakeRecoveryPort().apply { currentClientMatches = false }
        val original = prepare(port)

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertSame(original, pipeline.sender)
        assertSame(original, pipeline.currentOutput()!!.sender)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun abortsWhenFormalSessionBindingChanged() {
        val port = FakeRecoveryPort()
        val original = prepare(port, carLife = { null })

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertSame(original, pipeline.sender)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun abortsWhenScreenNoLongerOwnedByPhone() {
        val port = FakeRecoveryPort().apply { phoneOwns = false }
        val original = prepare(port)

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertSame(original, pipeline.sender)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun failsAndLeavesBothSlotsEmptyWhenTeardownFails() {
        val port = FakeRecoveryPort().apply { teardownResult = false }
        prepare(port, originalRunning = true)

        assertEquals(ScreenRecoveryResult.FAILED, recover())

        assertNull(pipeline.sender)
        assertNull(pipeline.currentOutput())
        assertEquals(listOf("teardown"), port.calls)
        assertTrue("恢复必须经控制器锁端口", ports.lockCalls >= 1)
    }

    @Test
    fun abortsWhenScopeIsLostDuringTeardown() {
        val port =
            FakeRecoveryPort().apply {
                onTeardown = { scopeActive = false }
            }
        prepare(port)

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertNull(pipeline.sender)
        assertNull(pipeline.currentOutput())
        assertEquals(listOf("teardown"), port.calls)
    }

    @Test
    fun failsWhenRunScreenStreamReturnsNull() {
        val port = FakeRecoveryPort().apply { screenInfo = null }
        prepare(port)

        assertEquals(ScreenRecoveryResult.FAILED, recover())

        assertNull(pipeline.sender)
        assertNull(pipeline.currentOutput())
        assertEquals(listOf("teardown", "run"), port.calls)
    }

    @Test
    fun abortsAndStopsReplacementWhenClientChangesBeforePublish() {
        val port =
            FakeRecoveryPort().apply {
                screenInfo = unreachableStream()
                onRun = { currentClientMatches = false }
            }
        prepare(port)

        assertEquals(ScreenRecoveryResult.ABORTED, recover())

        assertNull(pipeline.sender)
        assertNull(pipeline.currentOutput())
        assertEquals(listOf("teardown", "run"), port.calls)
    }

    @Test
    fun failsWhenDataPortCannotOpen() {
        val port = FakeRecoveryPort().apply { screenInfo = unreachableStream() }
        prepare(port)

        assertEquals(ScreenRecoveryResult.FAILED, recover())

        assertNull(pipeline.sender)
        assertNull(pipeline.currentOutput())
        assertEquals(listOf("teardown", "run"), port.calls)
    }

    @Test
    fun failsAndRollsBackReplacementWhenRecordFails() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { dataServer ->
            val port =
                FakeRecoveryPort().apply {
                    screenInfo = AirPlayClient.ScreenStreamInfo(7L, dataServer.localPort, cipher())
                    recordResult = false
                }
            prepare(port)

            assertEquals(ScreenRecoveryResult.FAILED, recover())

            assertNull(pipeline.sender)
            assertNull(pipeline.currentOutput())
            assertEquals(listOf("teardown", "run", "record"), port.calls)
            assertTrue("失败回滚必须经控制器锁端口", ports.lockCalls >= 2)
        }
    }

    private fun unreachableStream(): AirPlayClient.ScreenStreamInfo = AirPlayClient.ScreenStreamInfo(7L, 9, cipher())

    /** 安装恢复闭包并预先发布一个输出；返回原发送端以便断言未被误停/误换。 */
    private fun prepare(
        port: FakeRecoveryPort,
        originalRunning: Boolean = false,
        carLife: () -> CarLifeSession? = { session },
    ): VideoSender {
        val original = sender()
        if (originalRunning) setSenderRunning(original, true)
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.sender = original
        pipeline.publishOutput("a", original)
        pipeline.installRecovery(plan(hooks), client, carLife, null, "display", "a", port)
        return original
    }

    private fun recover(): ScreenRecoveryResult = pipeline.recoveryOrNull()!!.invoke()
}
