package com.mcarplay.app.replay

import com.mcarplay.app.airplay.VirtualIap2Link
import com.mcarplay.app.iap2.Iap2Csm
import com.mcarplay.app.iap2.Iap2LinkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **隧道重放回归**：录制的车机隧道 CSM（IAPLINK rx）逐条喂给真实的
 * [VirtualIap2Link]（MIB3 形态），我们回出的 CSM 消息号序列必须与录制的
 * 隧道 tx 序列一致（0x4E0C/0x4E0B 等含随机内容，比对消息号与首现顺序）。
 *
 * 车机侧由引擎扮演（复用 VirtualIap2LinkTest 的换帧法），录制的 CSM 体
 * 经引擎注入——聚焦控制会话状态机，不重演链路层。
 */
class TunnelReplayTest {
    @Test
    fun recordedTunnelCsmReplaysIdentically() {
        val fixtures = CarFixture.loadDirectory()
        val usable = fixtures.filter { it.tunnelRx.isNotEmpty() }
        if (usable.isEmpty()) {
            println("REPLAY: 无隧道 fixture——跳过")
            return
        }
        val failures = ArrayList<String>()
        usable.forEach { fixture -> replay(fixture, failures) }
        assertTrue("隧道重放回归失败：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private class Harness {
        val controlAll = ArrayList<ByteArray>()
        val eventAll = ArrayList<ByteArray>()
        var clock = 0L
        var engineClock = 1L
        val car = Iap2LinkEngine()
        val pending = ArrayList<ByteArray>()
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventAll.add(it)
                    true
                },
                sendControlIap = {
                    controlAll.add(it)
                    pending.add(it)
                },
                tunnelWriter = { null },
                alive = { false },
                nowMillis = { clock },
            )

        fun exchange() {
            var guard = 0
            while (guard++ < 8) {
                val out = car.takeOutput()
                if (out.isNotEmpty()) link.onCarBytes(out)
                drainPendingToCar()
                engineClock += 600
                clock += 600
                car.advanceTime(engineClock)
                drainPendingToCar()
                if (car.state == Iap2LinkEngine.State.NORMAL && pending.isEmpty()) return
            }
        }

        private fun drainPendingToCar() {
            while (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                batch.forEach { car.feed(it, engineClock) }
            }
        }
    }

    private fun replay(
        fixture: CarFixture,
        failures: MutableList<String>,
    ) {
        val h = Harness()
        h.link.begin()
        h.link.armMib3()
        h.car.start(h.engineClock)
        h.exchange()
        assertEquals("${fixture.car}: 链路未达 NORMAL", Iap2LinkEngine.State.NORMAL, h.car.state)

        fixture.tunnelRx.forEach { (messageId, body) ->
            assertTrue(
                "${fixture.car}: 注入 0x${messageId.toString(16)} 失败（引擎拒绝入队）",
                h.car.sendControl(Iap2Csm.encode(messageId, body), h.engineClock),
            )
            h.exchange()
        }

        val replayCore = distinctCsmIds(extractCsmPayloads(h.controlAll))
        val recordedCore = fixture.tunnelTxIds.distinct()
        // 旧版录制的应答可能少于现行代码（有意的新增，如 MIB3 突发）——
        // 断言「录制 = 回放的有序子序列」：少答/乱序/换序都会红，有意追加放行
        val subsequenceOk = isOrderedSubsequence(recordedCore, replayCore)
        if (!subsequenceOk) {
            failures.add(
                "${fixture.car}: 隧道应答序列漂移（录制应为回放的有序子序列）\n" +
                    "  录制=${recordedCore.joinToString { "0x%04x".format(it) }}\n" +
                    "  回放=${replayCore.joinToString { "0x%04x".format(it) }}",
            )
        } else {
            println("REPLAY ✓ ${fixture.car} 隧道序列一致（录制 ${recordedCore.size} ⊆ 回放 ${replayCore.size}）")
        }
    }

    private fun isOrderedSubsequence(
        needle: List<Int>,
        haystack: List<Int>,
    ): Boolean {
        var i = 0
        haystack.forEach { id ->
            if (i < needle.size && needle[i] == id) i += 1
        }
        return i == needle.size
    }

    private fun distinctCsmIds(payloads: List<ByteArray>): List<Int> {
        val seen = LinkedHashSet<Int>()
        payloads.forEach { payload ->
            Iap2Csm.Parser().offer(payload).forEach { frame -> seen.add(frame.messageId) }
        }
        return seen.toList()
    }
}
