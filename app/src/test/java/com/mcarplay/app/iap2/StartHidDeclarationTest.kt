package com.mcarplay.app.iap2

import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.transport.InMemoryStreamPair
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * 0x6800 StartHID 的声明三态回归（issue #66，2026-11-15 保时捷 MIB2+/ALPINE 报告）。
 *
 * 现状是**只改归因文案、不改控制流**：三条路径都继续走 WiFi 交换，StartHID 从来不是
 * 建立 CarPlay 会话的前置条件。之所以要锁住，是因为旧实现把「声明会发却 2 秒内未到」
 * 也写成「该接收端不发 StartHID」——那份报告里车机明明声明 `StartHID=会发`，
 * 日志却记成不发，直接误导了归因（评论里因此提出"HID 先行"的假设）。
 *
 * 声明三态来自 [Iap2SenderSession] 的 `accessoryDeclaresSend`：
 *  - `false`  → 有清单且不含 0x6800 → 跳过等待，不打窗口；
 *  - `true`   → 有清单且含 0x6800   → 等满窗口后按「声明会发但未到」记录；
 *  - `null`   → 没有清单可判       → 保守等满窗口，按「无清单可判」记录。
 */
class StartHidDeclarationTest {
    @Test
    fun skipWindowIsTwoSeconds() {
        // 窗口长度本身是契约：文案里写的秒数必须与实际等待一致。
        assertTrue(START_HID_WAIT_NANOS == 2_000_000_000L)
    }

    @Test
    fun undeclaredStartHidSaysItWillNotBeSent() {
        val line = startHidSkipLine(declaresSend = false)
        assertTrue("应说明未声明会发：$line", line.contains("未声明会发 0x6800"))
        assertTrue("应说明跳过而不等待：$line", line.contains("跳过 2s 等待"))
        // 「未声明会发」本身就包含「声明会发」四字，必须按 StartHID 专属短语判。
        assertFalse("未声明路径不该出现声明会发分支的文案：$line", line.contains("StartHID 声明会发"))
    }

    @Test
    fun declaredButMissingStartHidDoesNotClaimTheReceiverNeverSendsIt() {
        val line = startHidTimeoutLine(declaresSend = true)
        // 关键回归：这句话在旧实现里是「该接收端不发 StartHID」，与实际声明矛盾。
        assertFalse("声明会发时不得写成「不发」：$line", line.contains("该接收端不发"))
        assertTrue("必须点明声明与实际不一致：$line", line.contains("声明会发"))
        assertTrue("必须给出实际行为（未到）：$line", line.contains("未到"))
        assertTrue("必须说明流程继续：$line", line.contains("继续"))
    }

    @Test
    fun unknownDeclarationIsReportedAsConservativeWaitNotAsNoSupport() {
        val line = startHidTimeoutLine(declaresSend = null)
        assertTrue("无清单可判应明确写出：$line", line.contains("无清单可判"))
        assertFalse("不得推断车机不支持：$line", line.contains("未声明会发"))
        assertFalse("不得推断该接收端不发：$line", line.contains("该接收端不发"))
    }

    @Test
    fun declaredTimeoutAndUndeclaredSkipAreDistinguishable() {
        // 报告归因靠日志：三态必须互不相同的字符串，否则又退化成"看不出差别"。
        val declaredTimeout = startHidTimeoutLine(declaresSend = true)
        val unknownTimeout = startHidTimeoutLine(declaresSend = null)
        val undeclaredSkip = startHidSkipLine(declaresSend = false)
        assertNotEquals(declaredTimeout, unknownTimeout)
        assertNotEquals(declaredTimeout, undeclaredSkip)
        assertNotEquals(unknownTimeout, undeclaredSkip)
    }

    // ---- 端到端：同一段 bring-up 在三态下都必须走完 WiFi 交换 ----

    /** 有清单且**含** 0x6800，但车机整轮不发：必须等满窗口并记为"声明会发但未到"。 */
    @Test
    fun declaredStartHidThatNeverArrivesTimesOutAndContinues() {
        val logs = Collections.synchronizedList(ArrayList<String>())
        val endpoint = bringUp(ShapeWithHid.DECLARED_BUT_SILENT, logs)
        assertTrue(
            "应记录「声明会发但未到」而不是「该接收端不发」：$logs",
            logs.any { it.contains("声明会发") && it.contains("未到") },
        )
        assertFalse("不得再出现误导性归因：$logs", logs.any { it.contains("该接收端不发 StartHID") })
        // 流程没有中止：同一条链路上照常拿到 0x5703 凭据。
        assertTrue("StartHID 缺失不得阻断 WiFi 交换", endpoint.ssid.isNotEmpty())
    }

    /** 有清单且**不含** 0x6800：直接跳过，不应出现「等满窗口」的记录。 */
    @Test
    fun undeclaredStartHidSkipsTheWindowEntirely() {
        val logs = Collections.synchronizedList(ArrayList<String>())
        val startedAt = System.nanoTime()
        val endpoint = bringUp(ShapeWithHid.UNDECLARED, logs)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("应记录跳过等待：$logs", logs.any { it.contains("未声明会发 0x6800") && it.contains("跳过") })
        assertFalse(
            "未声明时不该等 StartHID 窗口：$logs",
            logs.any { it.contains("0x6800 StartHID") && it.contains("未到") },
        )
        assertTrue("未声明路径不该白等 2s（实际 ${elapsedMillis}ms）", elapsedMillis < 2_000L)
        assertTrue(endpoint.ssid.isNotEmpty())
    }

    /** 完全没有清单可判：保守等一个窗口，按「无清单可判」记录，且不推断不支持。 */
    @Test
    fun missingDeclarationWaitsConservativelyAndSaysSo() {
        val logs = Collections.synchronizedList(ArrayList<String>())
        val endpoint = bringUp(ShapeWithHid.NO_LISTS, logs)
        assertTrue("应记录保守等待：$logs", logs.any { it.contains("无清单可判") })
        assertFalse("不得推断车机不支持：$logs", logs.any { it.contains("该接收端不发 StartHID") })
        assertTrue(endpoint.ssid.isNotEmpty())
    }

    private enum class ShapeWithHid { DECLARED_BUT_SILENT, UNDECLARED, NO_LISTS }

    private fun bringUp(
        shape: ShapeWithHid,
        logs: MutableList<String>,
    ): Iap2SenderSession.CarPlayEndpoint {
        val pair = InMemoryStreamPair()
        val phone = Iap2LinkChannel(pair.sideA, name = "phone-start-hid-${shape.name}")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory-start-hid-${shape.name}")
        val accessory = HidVariantAccessory(accessoryChannel, shape)
        val worker =
            Thread({ accessory.run() }, "mock-start-hid-${shape.name}").apply {
                isDaemon = true
                start()
            }
        try {
            return Iap2SenderSession(
                phone,
                log = { line -> logs.add(line) },
                bluetoothMac = "02:00:00:00:00:02",
                deviceName = "test-device",
            ).run(timeoutMillis = 15_000L)
        } finally {
            phone.close()
            accessoryChannel.close()
            worker.join(2_000)
        }
    }

    /**
     * 最小配件端：只覆盖识别、MFi 跳过、StartHID 三态，然后走标准 0x5702→0x5703，
     * 不提供 0x4301（本轮 0x5703 已给全凭据，Phase 1 不依赖它）。
     */
    private class HidVariantAccessory(
        private val channel: Iap2LinkChannel,
        private val shape: ShapeWithHid,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, "StartHidMock"),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                *declarations().toTypedArray(),
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64)))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70)))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // 设备状态问候五连
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)

            // DECLARED_BUT_SILENT 故意什么都不发：发送端应等满 2s 后继续。
            if (shape != ShapeWithHid.DECLARED_BUT_SILENT) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
            }

            expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
            send(
                Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                Iap2Csm.string(1, "MockAP"),
                Iap2Csm.string(2, "secret-passphrase"),
                Iap2Csm.u8(3, 2),
                Iap2Csm.u8(4, 36),
            )

            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
        }

        /**
         * 三态的声明清单：都保留 0x5703/0x5702（走旧协议，保证 0x5703 一定给出完整凭据），
         * 只在「是否声明 0x6800」与「是否有清单」上不同。
         */
        private fun declarations(): List<ByteArray> =
            when (shape) {
                // 发 3 项含 0x6800
                ShapeWithHid.DECLARED_BUT_SILENT ->
                    listOf(
                        Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03, 0x6800)),
                        Iap2Csm.u16List(7, intArrayOf(0x5702, 0x4e0d, 0x4e0e, 0xea00, 0xea01, 0x6801)),
                    )
                // 发 2 项不含 0x6800
                ShapeWithHid.UNDECLARED ->
                    listOf(
                        Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03)),
                        Iap2Csm.u16List(7, intArrayOf(0x5702, 0x4e0d, 0x4e0e, 0xea00, 0xea01)),
                    )
                // 完全不给参数 6/7
                ShapeWithHid.NO_LISTS -> emptyList()
            }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        private fun expect(messageId: Int) {
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
