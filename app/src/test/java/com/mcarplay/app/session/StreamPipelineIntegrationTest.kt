package com.mcarplay.app.session

import android.app.Application
import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.carlife.CarLifeSessionRegistry
import com.mcarplay.app.session.StreamPipelineTestSupport.cipher
import com.mcarplay.app.session.StreamPipelineTestSupport.client
import com.mcarplay.app.session.StreamPipelineTestSupport.plan
import com.mcarplay.app.session.StreamPipelineTestSupport.sender
import com.mcarplay.app.session.StreamPipelineTestSupport.setSenderRunning
import com.mcarplay.app.video.VideoSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Phase 2g-2 I 层：用真实 `VideoSender` + 本机回环数据端口串起「恢复重建 → 重新发布输出」，
 * 并验证旧 CarLife 源迟到 `onEnded` 不会停掉恢复后的新一轮发送端（B-18）。
 *
 * 不触达设备、实车或车机；数据端口只是接受 TCP 连接的本机 [ServerSocket]。
 */
class StreamPipelineIntegrationTest {
    private val ports = FakeStreamPorts()
    private val pipeline = StreamPipeline(ports)
    private val client = client()
    private val hooks = RecordingScreenHooks()
    private val session = pipeline.newFormalSession(Application())

    @Test
    fun recoveryRebuildsSenderAndReattachesOutput() {
        withDataServer { dataServer ->
            val port = readyPort(dataServer)
            val original = install(port, running = true)

            assertEquals(ScreenRecoveryResult.RESTORED, recover())

            assertFalse("旧发送端必须已停止", original.isRunning)
            val replacement = pipeline.sender
            assertNotSame(original, replacement)
            assertTrue("重建后的发送端必须已启动", replacement!!.isRunning)
            assertSame("输出槽必须指向新发送端", replacement, pipeline.currentOutput()!!.sender)
            assertEquals(
                listOf("teardown", "run", "record(force=true)", "restored"),
                port.calls,
            )
            replacement.stop()
        }
    }

    @Test
    fun consecutiveRecoveriesKeepReplacingTheLiveSender() {
        withDataServer { dataServer ->
            val port = readyPort(dataServer)
            install(port, running = true)

            assertEquals(ScreenRecoveryResult.RESTORED, recover())
            val first = pipeline.sender
            assertEquals(ScreenRecoveryResult.RESTORED, recover())
            val second = pipeline.sender

            assertNotSame(first, second)
            assertFalse("第一轮替代发送端必须已停止", first!!.isRunning)
            assertTrue("第二轮替代发送端必须已启动", second!!.isRunning)
            assertSame(second, pipeline.currentOutput()!!.sender)
            assertEquals(
                listOf(
                    "teardown",
                    "run",
                    "record(force=true)",
                    "restored",
                    "teardown",
                    "run",
                    "record(force=true)",
                    "restored",
                ),
                port.calls,
            )
            second!!.stop()
        }
    }

    @Test
    fun staleCarLifeEndedAfterRecoveryDoesNotStopNewSender() {
        withDataServer { dataServer ->
            val port = readyPort(dataServer)
            install(port, running = true)
            assertEquals(ScreenRecoveryResult.RESTORED, recover())
            val replacement = pipeline.sender!!

            val staleSession = pipeline.newFormalSession(Application())
            pipeline.handleEnded(staleSession, "旧源迟到结束")

            assertSame("旧源迟到不得换掉当前发送端", replacement, pipeline.sender)
            assertTrue("旧源迟到不得停掉当前发送端", replacement.isRunning)
            assertTrue(ports.logs.any { it == "CARLIFE: 未接入推流的旧会话结束 - 旧源迟到结束" })

            pipeline.handleEnded(session, "会话结束")
            assertFalse("当前会话结束时才停止发送端", replacement.isRunning)
            assertTrue(ports.logs.any { it == "CARLIFE: 会话结束 - 会话结束" })
        }
    }

    private fun readyPort(dataServer: ServerSocket): FakeRecoveryPort =
        FakeRecoveryPort().apply {
            screenInfo = AirPlayClient.ScreenStreamInfo(7L, dataServer.localPort, cipher())
        }

    private fun install(
        port: FakeRecoveryPort,
        running: Boolean,
    ): VideoSender {
        val original = sender()
        if (running) setSenderRunning(original, true)
        pipeline.claim(CarLifeSessionRegistry.Owner.FORMAL, session)
        pipeline.sender = original
        pipeline.publishOutput("a", original)
        pipeline.installRecovery(plan(hooks), client, { session }, null, "display", "a", port)
        return original
    }

    private fun recover(): ScreenRecoveryResult = pipeline.recoveryOrNull()!!.invoke()

    private fun withDataServer(block: (ServerSocket) -> Unit) {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            block(server)
        }
    }
}
