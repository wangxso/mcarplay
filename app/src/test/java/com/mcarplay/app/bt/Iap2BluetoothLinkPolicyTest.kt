package com.mcarplay.app.bt

import com.mcarplay.app.compat.VehiclePreset
import com.mcarplay.app.compat.VehiclePresetRuntime
import com.mcarplay.app.iap2.Iap2LinkEngine
import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.transport.InMemoryStreamPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 主动连接与车机回连共用同一通道工厂，运行时车型预设必须真正进入链路参数。 */
class Iap2BluetoothLinkPolicyTest {
    @Test
    fun inboundAndOutboundFactoryUsesGs3MarkerFirstPolicy() {
        val previous = VehiclePresetRuntime.current
        val pair = InMemoryStreamPair()
        val logs = ArrayList<String>()
        val reader = Executors.newSingleThreadExecutor()
        try {
            VehiclePresetRuntime.current = VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW
            val channel =
                createIap2BluetoothLinkChannel(
                    pair.sideA,
                    name = "gs3-test",
                    role = Iap2LinkChannel.Role.INITIATOR,
                ) { line -> synchronized(logs) { logs += line } }
            try {
                assertTrue(
                    "GS3 的 iAP2 通道没有使用 0x7F 与 marker-first",
                    waitForMillis(1_000) {
                        synchronized(logs) {
                            logs.any { it == "LINK: local initial maxOutgoing=127 waitForPeerMarker=true" }
                        }
                    },
                )
                val output = pair.sideB
                val firstRead =
                    reader.submit<ByteArray> {
                        val buffer = ByteArray(Iap2LinkEngine.MARKER.size)
                        val count = output.read(buffer)
                        buffer.copyOf(count.coerceAtLeast(0))
                    }
                assertArrayEquals(Iap2LinkEngine.MARKER, firstRead.get(1, TimeUnit.SECONDS))

                val prematureSyn =
                    reader.submit<ByteArray> {
                        val buffer = ByteArray(Iap2LinkEngine.HEADER_BYTES + 16)
                        val count = output.read(buffer)
                        buffer.copyOf(count.coerceAtLeast(0))
                    }
                val sentPrematurely =
                    try {
                        prematureSyn.get(250, TimeUnit.MILLISECONDS)
                        true
                    } catch (_: TimeoutException) {
                        false
                    }
                if (sentPrematurely) {
                    throw AssertionError("GS3 尚未收到对端 marker 前不得发送 SYN")
                }
            } finally {
                channel.close()
            }
        } finally {
            VehiclePresetRuntime.current = previous
            reader.shutdownNow()
            pair.sideA.close()
            pair.sideB.close()
        }
    }

    private fun waitForMillis(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
