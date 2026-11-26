package com.mcarplay.app.session

import android.view.KeyEvent
import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.airplay.ModeArbiter
import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.input.TouchReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EventCommandRouter 的确定性行为矩阵（J 层）。
 *
 * 测试直接调用生产 router，使用真实 ConsumerKeys/MediaKeyEdge/TouchReports/ModeArbiter 与
 * 记录型窄端口；不启动线程、不 sleep、不依赖 Robolectric 或私有 replay fixture。
 */
class EventCommandRouterTest {
    @Test
    fun g1GuardShortCircuitsBeforeScopeAndCommandReads() {
        val stale = Harness()
        stale.stale = true
        stale.send(PoisonMap())
        assertEquals("stale 时不得读取 scope", 0, stale.scope.activeReads)
        assertNoDispatch(stale)

        val inactive = Harness()
        inactive.scope.active = false
        inactive.send(PoisonMap())
        assertEquals("identity 通过后才读 scope", 1, inactive.scope.activeReads)
        assertNoDispatch(inactive)

        val active = Harness()
        active.send(linkedMapOf("type" to "unknown"))
        assertEquals(1, active.scope.activeReads)
        assertEquals(1, active.output.logs.size)
    }

    @Test
    fun g2GuardIsDynamicAndCancellationAfterEntryDoesNotAbortTheCommand() {
        val harness = Harness()
        assertEquals("构造不得读取动态依赖", 0, harness.audioEnabledReads)
        assertEquals(0, harness.videoSenderReads)
        assertEquals(0, harness.touchTargetReads)

        harness.send(linkedMapOf("type" to "unknown"))
        assertEquals(1, harness.output.logs.size)

        harness.stale = true
        harness.send(linkedMapOf("type" to "unknown"))
        assertEquals(1, harness.output.logs.size)
        harness.stale = false

        harness.scope.active = false
        harness.send(linkedMapOf("type" to "unknown"))
        assertEquals(1, harness.output.logs.size)
        harness.scope.active = true

        harness.nowProvider = {
            harness.scope.active = false
            1_000L
        }
        val caller = Thread.currentThread()
        harness.send(hidCommand(null, byteArrayOf(0xB5.toByte())))
        assertEquals("入口 guard 只执行一次；后续取消不回滚已开始的命令", 1, harness.output.mediaKeys.size)
        assertSame(caller, harness.output.routeThreads.single())

        harness.send(hidCommand(null, byteArrayOf(0)))
        assertEquals("下一条命令重新检查 guard", 1, harness.output.mediaKeys.size)
    }

    @Test
    fun h1ConsumerDescriptorsRouteMediaKeysInUsageOrderAndIgnoreHighBitFallback() {
        val arrayInputs = FakeInputs()
        val array = Harness(descriptors = mapOf("array" to AUDI_ARRAY_DESCRIPTOR), inputs = arrayInputs)
        array.send(hidCommand("array", byteArrayOf(0x05)))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT), array.output.mediaKeys.map { it.keyCode })
        assertEquals(
            "下一首 uuid=array usage=0xb5",
            array.output.mediaKeys
                .single()
                .label,
        )
        assertTrue(array.output.navigationKeys.isEmpty())
        assertEquals(0, arrayInputs.handleCalls)
        assertEquals(0, arrayInputs.touchParseCalls)

        val bitmapInputs = FakeInputs()
        val bitmap = Harness(descriptors = mapOf("bitmap" to HONDA_BITMAP_DESCRIPTOR), inputs = bitmapInputs)
        bitmap.send(hidCommand("bitmap", byteArrayOf(0x05)))
        assertEquals(
            listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_NEXT),
            bitmap.output.mediaKeys.map { it.keyCode },
        )
        assertEquals(
            listOf("播放 uuid=bitmap usage=0xb0", "下一首 uuid=bitmap usage=0xb5"),
            bitmap.output.mediaKeys.map { it.label },
        )
        assertTrue(bitmap.output.navigationKeys.isEmpty())
        assertEquals(0, bitmapInputs.handleCalls)

        val fallback = Harness()
        fallback.send(hidCommand(null, byteArrayOf(0xCD.toByte())))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), fallback.output.mediaKeys.map { it.keyCode })
    }

    @Test
    fun h2DescriptorLookupHappensForNullReportButNoLaterDependencyIsTouched() {
        val descriptors = CountingDescriptorMap(mutableMapOf("u" to AUDI_ARRAY_DESCRIPTOR))
        val inputs = FakeInputs()
        val harness = Harness(descriptors = descriptors, inputs = inputs)
        harness.send(hidCommand("u", null))
        assertEquals("null report 仍先查描述符并解析", 1, descriptors.getCalls)
        assertEquals(0, harness.clockCalls)
        assertEquals(0, inputs.handleCalls)
        assertEquals(0, inputs.touchParseCalls)
        assertTrue(harness.output.logs.isEmpty())
        assertEquals(0, harness.counters.consumerReleaseCount)
    }

    @Test
    fun h2UuidVariantsAndDescriptorPresenceKeepTheirExactFallbackRules() {
        val missingUuid = Harness()
        missingUuid.send(hidCommand(null, byteArrayOf(0xCD.toByte())))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), missingUuid.output.mediaKeys.map { it.keyCode })

        val emptyUuid = Harness(descriptors = mapOf("" to AUDI_ARRAY_DESCRIPTOR))
        emptyUuid.send(hidCommand("", byteArrayOf(0x05)))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT), emptyUuid.output.mediaKeys.map { it.keyCode })

        val numericUuid = Harness(descriptors = mapOf("42" to HONDA_BITMAP_DESCRIPTOR))
        numericUuid.send(hidCommand(42, byteArrayOf(0x04)))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT), numericUuid.output.mediaKeys.map { it.keyCode })

        val emptyDescriptorInputs = FakeInputs()
        val emptyDescriptor =
            Harness(
                descriptors = mapOf("empty" to ByteArray(0)),
                inputs = emptyDescriptorInputs,
            )
        emptyDescriptorInputs.contacts = listOf(TouchReports.Contact(0, 1, 2, true))
        emptyDescriptor.send(hidCommand("empty", byteArrayOf(0x05)))
        assertEquals("描述符存在时不得走单字节 Usage 回退", 1, emptyDescriptorInputs.handleCalls)
        assertEquals(1, emptyDescriptorInputs.touchParseCalls)
        assertTrue(emptyDescriptor.output.mediaKeys.isEmpty())

        val nonConsumerInputs = FakeInputs()
        val nonConsumer =
            Harness(
                descriptors = mapOf("touch" to AUDI_TOUCH_DESCRIPTOR),
                inputs = nonConsumerInputs,
            )
        nonConsumer.send(hidCommand("touch", byteArrayOf(0xCD.toByte())))
        assertTrue(nonConsumer.output.mediaKeys.isEmpty())
        assertEquals(1, nonConsumerInputs.handleCalls)
        assertEquals(1, nonConsumerInputs.touchParseCalls)
    }

    @Test
    fun h2ReportShapesAndConsumerReleaseStayDistinct() {
        val inputs = FakeInputs()
        val harness = Harness(inputs = inputs)

        harness.send(hidCommand("u", "wrong-type"))
        assertTrue("错误类型等效 report=null", harness.output.logs.isEmpty())
        assertEquals(0, inputs.handleCalls)

        harness.send(hidCommand("u", ByteArray(0)))
        assertEquals("空报文进入导航/触摸判定而非 Consumer", 1, inputs.handleCalls)
        assertEquals(1, inputs.touchParseCalls)
        assertEquals("EVENT: 其他 HID 报告 uuid=u bytes=0 hex=", harness.output.logs.last())

        harness.send(hidCommand("u", byteArrayOf(1, 2)))
        assertEquals(2, inputs.handleCalls)
        assertEquals(2, inputs.touchParseCalls)
        assertEquals("EVENT: 其他 HID 报告 uuid=u bytes=2 hex=01 02", harness.output.logs.last())

        inputs.contacts = listOf(TouchReports.Contact(0, 3, 4, true))
        harness.send(hidCommand("u", ByteArray(5)))
        assertEquals(3, inputs.touchParseCalls)
        assertEquals(1, harness.counters.touchLogCount)

        inputs.contacts = null
        harness.touchTarget = FakeTouchTarget()
        harness.send(hidCommand("u", ByteArray(12)))
        assertEquals(4, inputs.touchParseCalls)

        val consumer = Harness(descriptors = mapOf("array" to AUDI_ARRAY_DESCRIPTOR))
        consumer.send(hidCommand("array", byteArrayOf(0)))
        assertEquals(1, consumer.counters.consumerReleaseCount)
        assertEquals(1, consumer.clockCalls)
        assertTrue(consumer.output.mediaKeys.isEmpty())
    }

    @Test
    fun h3MediaEdgeBoundariesUuidsAndUnmappedUsagesStayExact() {
        val clock = ArrayDeque(listOf(1_000L, 2_499L, 2_500L, 2_600L, 3_000L))
        val harness = Harness()
        harness.nowProvider = {
            harness.clockCalls++
            clock.removeFirst()
        }
        harness.send(hidCommand("wheel", byteArrayOf(0xB5.toByte())))
        harness.send(hidCommand("wheel", byteArrayOf(0xB5.toByte())))
        harness.send(hidCommand("wheel", byteArrayOf(0xB5.toByte())))
        assertEquals(
            "新按与 1500ms 边界各触发一次",
            listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT),
            harness.output.mediaKeys.map { it.keyCode },
        )
        harness.send(hidCommand("wheel", byteArrayOf(0)))
        assertEquals(1, harness.counters.consumerReleaseCount)
        harness.send(hidCommand("wheel", byteArrayOf(0xB5.toByte())))
        assertEquals(3, harness.output.mediaKeys.size)
        assertEquals(5, harness.clockCalls)

        val perUuid = Harness()
        perUuid.send(hidCommand("a", byteArrayOf(0xB5.toByte())))
        perUuid.send(hidCommand("b", byteArrayOf(0xB5.toByte())))
        assertEquals(2, perUuid.output.mediaKeys.size)

        val multi = Harness(descriptors = mapOf("bitmap" to HONDA_BITMAP_DESCRIPTOR))
        multi.send(hidCommand("bitmap", byteArrayOf(0x06)))
        multi.send(hidCommand("bitmap", byteArrayOf(0x07)))
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PLAY,
            ),
            multi.output.mediaKeys.map { it.keyCode },
        )

        val unmapped = Harness(descriptors = mapOf("array" to AUDI_ARRAY_DESCRIPTOR))
        unmapped.send(hidCommand("array", byteArrayOf(0x07)))
        assertTrue(unmapped.output.mediaKeys.isEmpty())
        assertEquals("EVENT: 车机媒体键 随机播放 uuid=array usage=0xb9 无对应 Android 键，忽略", unmapped.output.logs.single())
        unmapped.send(hidCommand("array", byteArrayOf(0xFF.toByte())))
        assertEquals(1, unmapped.counters.consumerReleaseCount)
    }

    @Test
    fun h4MediaEdgesArePerRouterAndNullEmptyUuidStillShareTheChannelKey() {
        val first = Harness()
        val second = Harness()
        first.send(hidCommand("same", byteArrayOf(0xB5.toByte())))
        second.send(hidCommand("same", byteArrayOf(0xB5.toByte())))
        assertEquals(2, first.output.mediaKeys.size + second.output.mediaKeys.size)

        first.send(hidCommand("same", byteArrayOf(0xB5.toByte())))
        assertEquals(1, first.output.mediaKeys.size)

        first.stale = true
        first.send(hidCommand("same", byteArrayOf(0)))
        first.stale = false
        first.send(hidCommand("same", byteArrayOf(0xB5.toByte())))
        assertEquals("旧轮 stale 时不得更新其边沿", 1, first.output.mediaKeys.size)
        first.send(hidCommand("same", byteArrayOf(0)))
        first.send(hidCommand("same", byteArrayOf(0xB5.toByte())))
        assertEquals(2, first.output.mediaKeys.size)

        val shared = Harness()
        shared.send(hidCommand(null, byteArrayOf(0xB5.toByte())))
        shared.send(hidCommand("", byteArrayOf(0xB5.toByte())))
        assertEquals("null 与空 uuid 共用 '' 键", 1, shared.output.mediaKeys.size)
        shared.send(hidCommand("", byteArrayOf(0)))
        shared.send(hidCommand(null, byteArrayOf(0xB5.toByte())))
        assertEquals(2, shared.output.mediaKeys.size)
    }

    @Test
    fun h5ProductionNavigationInputsKeepOrderRawLoggingAndTouchFallback() {
        val previousRaw = com.mcarplay.app.input.HidNavigation.rawLogEnabled
        com.mcarplay.app.input.HidNavigation.rawLogEnabled = false
        try {
            val wheel =
                Harness(
                    descriptors = mapOf("wheel" to GENERIC_WHEEL_DESCRIPTOR),
                    inputs = EventCommandRouter.DefaultEventInputs,
                )
            wheel.send(hidCommand("wheel", byteArrayOf(0x01, 0x01)))
            assertEquals(
                listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_DOWN),
                wheel.output.navigationKeys,
            )
            assertEquals("EVENT: 旋钮/导航键 确认+下（uuid=wheel）", wheel.output.logs.last())
            assertEquals("非空 nav 不进入触摸", 0, wheel.touchTargetReads)

            val touch = FakeTouchTarget(report = null)
            wheel.touchTarget = touch
            wheel.send(hidCommand("wheel", ByteArray(5)))
            assertEquals("nav 结果为空时可继续触摸", 1, touch.sendCalls)

            val hat =
                Harness(
                    descriptors = mapOf("hat" to GENERIC_HAT_DESCRIPTOR),
                    inputs = EventCommandRouter.DefaultEventInputs,
                )
            hat.send(hidCommand("hat", byteArrayOf(0x01)))
            assertEquals(
                listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT),
                hat.output.navigationKeys,
            )
            hat.send(hidCommand("hat", byteArrayOf(0x01)))
            assertEquals(2, hat.output.navigationKeys.size)

            com.mcarplay.app.input.HidNavigation.rawLogEnabled = true
            wheel.send(hidCommand("wheel", byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00)))
            val raw = wheel.output.logs.single { it.startsWith("HID-RAW:") }
            assertEquals(
                "HID-RAW: uuid=wheel bytes=01 00 00 00 00 desc=${GENERIC_WHEEL_DESCRIPTOR.size}B",
                raw,
            )
        } finally {
            com.mcarplay.app.input.HidNavigation.rawLogEnabled = previousRaw
        }
    }

    @Test
    fun h6RealTouchReportsSelectSlotZeroAndMapToCapturedTargetDimensions() {
        val target = FakeTouchTarget(width = 200, height = 400)
        val harness = Harness(inputs = EventCommandRouter.DefaultEventInputs)
        harness.touchTarget = target

        val single = byteArrayOf(0x01, 0x0A, 0x00, 0x14, 0x00)
        harness.send(hidCommand(null, single))
        assertEquals(listOf(Triple(20, 80, true)), target.sends)
        assertEquals(1, harness.output.touchSentCalls)
        assertEquals("EVENT: 触摸 #0(10,20)↓", harness.output.logs.last())

        val multi =
            byteArrayOf(
                0x00,
                0x01,
                0x0A,
                0x00,
                0x14,
                0x00,
                0x01,
                0x00,
                0x5A,
                0x00,
                0x5A,
                0x00,
            )
        harness.send(hidCommand(null, multi))
        assertEquals(Triple(20, 80, true), target.sends.last())
        assertEquals(2, target.sendCalls)
    }

    @Test
    fun h6InjectedContactsPreferSlotZeroThenFirstAndPreserveRelease() {
        val inputs = FakeInputs()
        val target = FakeTouchTarget(width = 100, height = 100)
        val harness = Harness(inputs = inputs)
        harness.touchTarget = target
        inputs.contacts =
            listOf(
                TouchReports.Contact(1, 90, 90, true),
                TouchReports.Contact(0, 10, 20, false),
            )
        harness.send(hidCommand(null, ByteArray(5)))
        assertEquals(Triple(10, 20, false), target.sends.single())
        assertEquals("EVENT: 触摸 #1(90,90)↓, #0(10,20)↑", harness.output.logs.last())

        inputs.contacts = listOf(TouchReports.Contact(1, 30, 40, true))
        harness.send(hidCommand(null, ByteArray(5)))
        assertEquals(Triple(30, 40, true), target.sends.last())

        val empty = Harness(inputs = inputs)
        empty.touchTarget = target
        inputs.contacts = emptyList()
        empty.send(hidCommand(null, ByteArray(5)))
        assertEquals(0, empty.touchTargetReads)
        assertEquals(0, empty.counters.touchLogCount)
    }

    @Test
    fun h7TouchThrottleHappensBeforeIncrementAndClampAndEvidenceStayExact() {
        val expectedLogged = mapOf(0 to true, 30 to true, 31 to false, 49 to false, 50 to true)
        expectedLogged.forEach { (count, logged) ->
            val inputs = FakeInputs()
            inputs.contacts = listOf(TouchReports.Contact(0, 1, 2, true))
            val harness = Harness(inputs = inputs)
            harness.counters.touchLogCount = count
            harness.send(hidCommand(null, ByteArray(5)))
            assertEquals("count=$count", logged, harness.output.logs.any { it.startsWith("EVENT: 触摸 ") })
            assertEquals(count + 1, harness.counters.touchLogCount)
        }

        val inputs = FakeInputs()
        inputs.contacts = listOf(TouchReports.Contact(0, 200, 200, true))
        val noTarget = Harness(inputs = inputs)
        noTarget.send(hidCommand(null, ByteArray(5)))
        assertEquals("无 CarLife target 仍计数", 1, noTarget.counters.touchLogCount)

        val target = FakeTouchTarget(width = 10, height = 20)
        val clamped = Harness(inputs = inputs)
        clamped.touchTarget = target
        clamped.send(hidCommand(null, ByteArray(5)))
        assertEquals(Triple(9, 19, true), target.sends.single())
        assertEquals(1, clamped.output.touchSentCalls)

        val zeroSizeTarget = FakeTouchTarget(width = 0, height = 20)
        val zero = Harness(inputs = inputs)
        zero.touchTarget = zeroSizeTarget
        zero.send(hidCommand(null, ByteArray(5)))
        assertEquals(0, zeroSizeTarget.sendCalls)
        assertEquals(0, zero.output.touchSentCalls)

        val rejectedTarget = FakeTouchTarget(width = 10, height = 10, result = false)
        val rejected = Harness(inputs = inputs)
        rejected.touchTarget = rejectedTarget
        rejected.send(hidCommand(null, ByteArray(5)))
        assertEquals(1, rejectedTarget.sendCalls)
        assertEquals(0, rejected.output.touchSentCalls)

        val failure = IllegalStateException("touch failed")
        val throwingTarget = FakeTouchTarget(width = 10, height = 10, failure = failure)
        val throwing = Harness(inputs = inputs)
        throwing.touchTarget = throwingTarget
        try {
            throwing.send(hidCommand(null, ByteArray(5)))
            fail("touch 异常必须原对象传播")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals(0, throwing.output.touchSentCalls)
    }

    @Test
    fun h8OtherHidLogsAreUnlimitedAndParserFailuresPropagateExactly() {
        val inputs = FakeInputs()
        val harness = Harness(inputs = inputs)
        val longReport = ByteArray(301) { 0xAB.toByte() }
        harness.send(hidCommand("long", longReport))
        val expectedHex = longReport.joinToString(" ") { "%02x".format(it) }
        assertEquals(
            "EVENT: 其他 HID 报告 uuid=long bytes=301 hex=$expectedHex",
            harness.output.logs.single(),
        )
        assertTrue(expectedHex.length > 600)

        val nullReport = Harness(inputs = FakeInputs())
        nullReport.send(hidCommand("none", null))
        assertTrue(nullReport.output.logs.isEmpty())

        val failure = IllegalStateException("parser failed")
        val throwingInputs = FakeInputs().apply { parseFailure = failure }
        val throwing = Harness(inputs = throwingInputs)
        try {
            throwing.send(hidCommand("touch", ByteArray(5)))
            fail("解析异常必须原对象传播")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals(0, throwing.counters.touchLogCount)
        assertTrue(throwing.output.logs.isEmpty())
        assertEquals(0, throwing.output.touchSentCalls)
    }

    @Test
    fun a1IapRequiresNestedByteArrayAndFeedsTheSameInstanceAfterCounting() {
        val invalidParams =
            listOf(
                null,
                "not-a-map",
                listOf("data" to byteArrayOf(1)),
                mapOf("data" to null),
                mapOf("data" to "not-bytes"),
                mapOf("data" to listOf<Byte>(1, 2)),
            )
        invalidParams.forEach { params ->
            val harness = Harness()
            harness.send(linkedMapOf("type" to "iAPSendMessage", "params" to params))
            val loggedParams = params as? Map<*, *>
            assertEquals("EVENT: iAPSendMessage 无 data（params=$loggedParams）", harness.output.logs.single())
            assertEquals(0, harness.counters.iapEventMessageCount)
            assertTrue(harness.client.iapData.isEmpty())
        }

        val topLevel = Harness()
        topLevel.send(linkedMapOf("type" to "iAPSendMessage", "data" to byteArrayOf(1)))
        assertEquals("EVENT: iAPSendMessage 无 data（params=null）", topLevel.output.logs.single())

        val harness = Harness()
        val empty = ByteArray(0)
        harness.send(linkedMapOf("type" to "iAPSendMessage", "params" to mapOf("data" to empty)))
        assertSame(empty, harness.client.iapData.single())
        assertEquals(1, harness.counters.iapEventMessageCount)
        assertTrue(harness.trace.events.indexOf("counter.iap=1") < harness.trace.events.indexOf("iap-bytes"))

        val nonEmpty = byteArrayOf(1, 2, 3)
        harness.send(linkedMapOf("type" to "iAPSendMessage", "params" to mapOf("data" to nonEmpty)))
        assertSame(nonEmpty, harness.client.iapData.last())
        assertEquals(2, harness.counters.iapEventMessageCount)

        val failure = IllegalStateException("iap failed")
        harness.client.iapFailure = failure
        try {
            harness.send(linkedMapOf("type" to "iAPSendMessage", "params" to mapOf("data" to ByteArray(0))))
            fail("client 异常必须原对象传播")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertEquals("计数发生在 client 调用前", 3, harness.counters.iapEventMessageCount)
    }

    @Test
    fun f1KeyFrameRequestOrderDefaultAndPriorityDiagnosticsAreExact() {
        val noSender = Harness()
        noSender.send(linkedMapOf("type" to "forceKeyFrame"))
        assertEquals(1, noSender.counters.forceKeyFrameCount)
        assertEquals(
            "EVENT: 车机请求关键帧（forceKeyFrame，第 1 次）→ 源未就绪，等周期 I 帧",
            noSender.output.logs.single(),
        )
        assertEquals(1, noSender.videoSenderReads)

        val cases =
            listOf(
                FakeSender(requestResult = false, gap = 0L, external = true, transcoding = true) to
                    "EVENT: 车机请求关键帧（forceKeyFrame，第 1 次，距上个已发 IDR 0ms）→ 源未就绪，等周期 I 帧",
                FakeSender(requestResult = true, gap = 12L, external = true, transcoding = true) to
                    "EVENT: 车机请求关键帧（forceKeyFrame，第 1 次，距上个已发 IDR 12ms）→ 已向 CarWith 请求（透传源不保证立即出 I 帧）",
                FakeSender(requestResult = true, gap = 12L, external = false, transcoding = true) to
                    "EVENT: 车机请求关键帧（forceKeyFrame，第 1 次，距上个已发 IDR 12ms）→ 已强制编码 I 帧（转码输出）",
                FakeSender(requestResult = true, gap = null, external = false, transcoding = false) to
                    "EVENT: 车机请求关键帧（forceKeyFrame，第 1 次）→ 已强制编码 I 帧",
            )
        cases.forEach { (sender, expected) ->
            val harness = Harness()
            harness.sender = sender
            harness.send(linkedMapOf("type" to "forceKeyFrame"))
            assertEquals(expected, harness.output.logs.single())
            assertEquals(1, sender.requestCalls)
            assertEquals(1, harness.videoSenderReads)
            assertTrue(harness.trace.events.indexOf("sender.request") < harness.trace.events.indexOf("counter.force=1"))
            if (!sender.requestResult) {
                assertEquals("request=false 优先，不读模式属性", 0, sender.externalReads + sender.transcodingReads)
            } else if (sender.external) {
                assertEquals("external 优先，不读 transcode", 0, sender.transcodingReads)
            }
        }
    }

    @Test
    fun f2KeyFrameThrottleNullSenderAndExceptionKeepTheExactCounts() {
        listOf(0, 1, 2, 3, 18, 19, 20, 39).forEach { old ->
            val harness = Harness()
            val sender = FakeSender(requestResult = true, gap = 7L)
            harness.sender = sender
            harness.counters.forceKeyFrameCount = old
            harness.send(linkedMapOf("type" to "forceKeyFrame"))
            val new = old + 1
            val logged = new <= 3 || new % 20 == 0
            assertEquals("old=$old", new, harness.counters.forceKeyFrameCount)
            assertEquals("old=$old", logged, harness.output.logs.isNotEmpty())
            assertEquals(1, sender.requestCalls)
            assertEquals("静默帧不得提前读 gap", if (logged) 1 else 0, sender.gapReads)
            if (!logged) {
                assertEquals(0, sender.externalReads + sender.transcodingReads)
            }
        }

        val failure = IllegalArgumentException("request failed")
        val throwing = Harness()
        val sender = FakeSender(failure = failure)
        throwing.sender = sender
        throwing.counters.forceKeyFrameCount = 5
        try {
            throwing.send(linkedMapOf("type" to "forceKeyFrame"))
            fail("request 异常必须原对象传播")
        } catch (caught: IllegalArgumentException) {
            assertSame(failure, caught)
        }
        assertEquals(5, throwing.counters.forceKeyFrameCount)
        assertTrue(throwing.output.logs.isEmpty())
    }

    @Test
    fun m1ChangeModesAlwaysEnqueuesThenConsumesRealPendingClaim() {
        listOf(null, "not-a-map").forEach { params ->
            val modes = ModeArbiter()
            val harness = Harness(modes = modes)
            harness.send(linkedMapOf("type" to "changeModes", "params" to params))
            val expectedNote = "无资源请求，保持 ${ModeArbiter().describe()}"
            assertEquals(
                "车机请求 changeModes（→ $expectedNote）",
                harness.output.enqueues
                    .single()
                    .reason,
            )
            assertFalse(
                harness.output.enqueues
                    .single()
                    .requestKeyFrame,
            )
            assertFalse(
                harness.output.enqueues
                    .single()
                    .retakeScreen,
            )
            assertFalse(
                harness.output.enqueues
                    .single()
                    .rebuildStream,
            )
            assertTrue(harness.output.backgrounds.isEmpty())
        }

        val pendingModes = ModeArbiter()
        pendingModes.onChangeModes(resources(audioTake(constraint = 1_000)))
        pendingModes.onAudioStreamStarting()
        assertTrue(pendingModes.audioClaimAwaitingReturn)
        val pending = Harness(modes = pendingModes)
        val returning = resources(audioTransfer(type = ModeArbiter.TRANSFER_UNTAKE))
        pending.send(linkedMapOf("type" to "changeModes", "params" to returning))
        assertEquals(1, pending.output.enqueues.size)
        assertEquals(
            "audio-claim-retry",
            pending.output.backgrounds
                .single()
                .name,
        )
        assertEquals("AUDIO: 车机已归还主音频——后台重试建立音频流", pending.output.logs.single())
        assertFalse(pendingModes.audioClaimAwaitingReturn)
        assertTrue(
            pending.trace.events.indexOf("enqueue") < pending.trace.events.indexOf("log") &&
                pending.trace.events.indexOf("log") < pending.trace.events.indexOf("background"),
        )

        val stillHeld = ModeArbiter()
        stillHeld.onChangeModes(resources(audioTake(constraint = 1_000)))
        stillHeld.onAudioStreamStarting()
        val held = Harness(modes = stillHeld)
        held.send(linkedMapOf("type" to "changeModes", "params" to resources(audioTake(constraint = 1_000))))
        assertEquals(1, held.output.enqueues.size)
        assertTrue(held.output.backgrounds.isEmpty())
        assertTrue(held.output.logs.isEmpty())
        assertTrue(stillHeld.audioClaimAwaitingReturn)
    }

    @Test
    fun u1AllAudioDecisionCombinationsUseRunningForTakeAndSecondReadForSuffix() {
        listOf(false, true).forEach { enabled ->
            listOf(false, true).forEach { running ->
                listOf(false, true).forEach { awaiting ->
                    val modes = audioModes(awaiting)
                    val harness = Harness(modes = modes)
                    harness.audioEnabledProvider = { enabled }
                    harness.audioStreamRunningValue = running
                    harness.send(linkedMapOf("type" to "requestUI"))

                    assertEquals("enabled=$enabled running=$running awaiting=$awaiting", 2, harness.audioEnabledReads)
                    assertEquals(1, harness.audioStreamRunningReads)
                    assertEquals(1, harness.counters.requestUiCount)
                    val hasSuffix = enabled && !running
                    assertEquals(
                        "suffix 必须按第二次 enabled 读取",
                        hasSuffix,
                        harness.output.enqueues
                            .single()
                            .reason
                            .contains("；音频流未建立，暂不声明主音频"),
                    )
                    if (!awaiting) {
                        assertEquals(
                            "enabled=false+running=true 仍声明主音频",
                            running,
                            modes.phoneOwnsAudio,
                        )
                    }
                    val retried = enabled && !running && awaiting
                    assertEquals(retried, harness.output.backgrounds.isNotEmpty())
                    if (awaiting) {
                        assertEquals("pending claim 仅成功消费一次", !retried, modes.audioClaimAwaitingReturn)
                    }
                }
            }
        }
    }

    @Test
    fun u2ModeResultsAndResolutionOrderUseRealArbiterWithoutLettingAllowedGateRetry() {
        val takeModes = ModeArbiter()
        val takeMirror = ModeArbiter()
        takeModes.onChangeModes(resources(screenTake(500)))
        takeMirror.onChangeModes(resources(screenTake(500)))

        val borrowModes = ModeArbiter()
        val borrowMirror = ModeArbiter()
        borrowModes.onChangeModes(resources(screenBorrow(unborrowConstraint = 1_000)))
        borrowMirror.onChangeModes(resources(screenBorrow(unborrowConstraint = 1_000)))

        val returnedModes = ModeArbiter().also { it.onUserReturnedToCar() }
        val returnedMirror = ModeArbiter().also { it.onUserReturnedToCar() }
        val scenarios =
            listOf(
                ModeArbiter() to ModeArbiter(),
                takeModes to takeMirror,
                borrowModes to borrowMirror,
                returnedModes to returnedMirror,
            )
        scenarios.forEach { (modes, mirror) ->
            val expected = mirror.onUserRequestedUi(takeAudio = false)
            val harness = Harness(modes = modes)
            harness.rebuildResult = true
            harness.send(linkedMapOf("type" to "requestUI"))
            val enqueue = harness.output.enqueues.single()
            assertEquals(expected.allowed, enqueue.requestKeyFrame)
            assertEquals(expected.retake, enqueue.retakeScreen)
            assertEquals(true, enqueue.rebuildStream)
            assertTrue(enqueue.reason.contains(expected.note))
            assertEquals(1, harness.rebuildReads)
            assertTrue(
                harness.trace.events.indexOf("counter.requestUi=1") <
                    harness.trace.events.indexOf("audioEnabled") &&
                    harness.trace.events.indexOf("audioRunning") <
                    harness.trace.events.indexOf("resolveRebuild") &&
                    harness.trace.events.indexOf("resolveRebuild") <
                    harness.trace.events.indexOf("enqueue"),
            )
        }

        val modes = ModeArbiter()
        modes.onChangeModes(resources(screenBorrow(unborrowConstraint = 1_000)))
        modes.onChangeModes(resources(audioTake(constraint = 1_000)))
        modes.onAudioStreamStarting()
        modes.onChangeModes(resources(audioTransfer(type = ModeArbiter.TRANSFER_UNTAKE)))
        val harness = Harness(modes = modes)
        harness.audioEnabledProvider = { true }
        harness.audioStreamRunningValue = false
        harness.send(linkedMapOf("type" to "requestUI"))
        assertFalse(
            "allowed=false 时仍保留原音频重试判定",
            harness.output.enqueues
                .single()
                .requestKeyFrame,
        )
        assertEquals(
            "audio-request-ui-retry",
            harness.output.backgrounds
                .single()
                .name,
        )
        assertEquals("AUDIO: requestUI 重进——主音频此前未接管，后台重试建立音频流", harness.output.logs.single())
    }

    @Test
    fun u3PendingClaimIsConsumedOnceAcrossRequestUiAndChangeModes() {
        val modes = audioModes(awaiting = true)
        val harness = Harness(modes = modes)
        harness.audioEnabledProvider = { true }
        harness.send(linkedMapOf("type" to "requestUI"))
        assertEquals(
            "audio-request-ui-retry",
            harness.output.backgrounds
                .single()
                .name,
        )
        assertFalse(modes.audioClaimAwaitingReturn)

        harness.send(linkedMapOf("type" to "changeModes", "params" to resources(audioTransfer(type = ModeArbiter.TRANSFER_UNTAKE))))
        harness.send(linkedMapOf("type" to "requestUI"))
        assertEquals("重复事件不得凭空再投递", 1, harness.output.backgrounds.size)

        val noRetryModes = audioModes(awaiting = true)
        val noRetry = Harness(modes = noRetryModes)
        noRetry.audioEnabledProvider = { false }
        noRetry.send(linkedMapOf("type" to "requestUI"))
        assertTrue(noRetry.output.backgrounds.isEmpty())
        assertTrue(noRetryModes.audioClaimAwaitingReturn)

        noRetry.send(linkedMapOf("type" to "changeModes", "params" to resources(audioTransfer(type = ModeArbiter.TRANSFER_UNTAKE))))
        assertEquals(
            "audio-claim-retry",
            noRetry.output.backgrounds
                .single()
                .name,
        )
        assertFalse(noRetryModes.audioClaimAwaitingReturn)
    }

    @Test
    fun c1BackgroundWorkIsDeferredAndFailuresPropagateAtTheOriginalBoundary() {
        val modes = audioModes(awaiting = true)
        val harness = Harness(modes = modes)
        val returning = resources(audioTransfer(type = ModeArbiter.TRANSFER_UNTAKE))
        harness.send(linkedMapOf("type" to "changeModes", "params" to returning))
        assertEquals(1, harness.output.backgrounds.size)
        assertEquals("router 不得同步重试音频", 0, harness.output.retryCalls)
        harness.output.drainBackgrounds()
        assertEquals(1, harness.output.retryCalls)

        val enqueueFailure = IllegalStateException("enqueue failed")
        val broken = Harness(modes = audioModes(awaiting = true))
        broken.output.enqueueFailure = enqueueFailure
        try {
            broken.send(linkedMapOf("type" to "changeModes", "params" to returning))
            fail("enqueue 异常必须原对象传播")
        } catch (caught: IllegalStateException) {
            assertSame(enqueueFailure, caught)
        }
        assertTrue("enqueue 失败时不得消费 pending claim", broken.modeArbiter.audioClaimAwaitingReturn)
        assertTrue(broken.output.backgrounds.isEmpty())

        val taskFailure = IllegalStateException("retry failed")
        val failingTask = Harness(modes = audioModes(awaiting = true))
        failingTask.send(linkedMapOf("type" to "changeModes", "params" to returning))
        failingTask.output.retryFailure = taskFailure
        try {
            failingTask.output.drainBackgrounds()
            fail("后台任务异常必须保持原对象")
        } catch (caught: IllegalStateException) {
            assertSame(taskFailure, caught)
        }
    }

    @Test
    fun d1DescriptorsSenderTouchTargetsAndAudioObservationsAreReadAtTheirOriginalPoints() {
        val descriptors = CountingDescriptorMap(mutableMapOf("u" to AUDI_ARRAY_DESCRIPTOR))
        val inputs = FakeInputs()
        val harness = Harness(descriptors = descriptors, inputs = inputs)
        harness.send(hidCommand("u", byteArrayOf(0x05)))
        descriptors.descriptorValues["u"] = HONDA_BITMAP_DESCRIPTOR
        harness.send(hidCommand("u", byteArrayOf(0)))
        harness.send(hidCommand("u", byteArrayOf(0x04)))
        assertEquals("描述符每帧重查", 3, descriptors.getCalls)
        assertEquals(
            listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT),
            harness.output.mediaKeys.map { it.keyCode },
        )

        val senderA = FakeSender(requestResult = true, gap = 12L, external = true)
        val senderB = FakeSender(requestResult = true, gap = 99L, external = false)
        val senderHarness = Harness()
        senderHarness.sender = senderA
        senderA.onRequest = { senderHarness.sender = senderB }
        senderHarness.send(linkedMapOf("type" to "forceKeyFrame"))
        assertTrue(
            senderHarness.output.logs
                .single()
                .contains("距上个已发 IDR 12ms"),
        )
        assertTrue(
            senderHarness.output.logs
                .single()
                .contains("透传源"),
        )
        assertEquals("同命令后续属性仍来自 A", 1, senderA.gapReads)
        assertEquals(1, senderA.externalReads)
        assertEquals(0, senderB.gapReads)
        assertEquals(0, senderB.externalReads)
        senderHarness.send(linkedMapOf("type" to "forceKeyFrame"))
        assertEquals("下一条命令重新捕获 sender", 1, senderB.requestCalls)

        val targetA = FakeTouchTarget(width = 200, height = 300)
        val targetB = FakeTouchTarget(width = 20, height = 30)
        val touchHarness = Harness(inputs = inputs)
        inputs.contacts = listOf(TouchReports.Contact(0, 10, 20, true))
        touchHarness.touchTarget = targetA
        targetA.onWidth = { touchHarness.touchTarget = targetB }
        touchHarness.send(hidCommand(null, ByteArray(5)))
        assertEquals(1, targetA.sendCalls)
        assertEquals(0, targetB.sendCalls)
        assertEquals(Triple(20, 60, true), targetA.sends.single())
        touchHarness.send(hidCommand(null, ByteArray(5)))
        assertEquals(1, targetB.sendCalls)
    }

    @Test
    fun d2AudioReadsSenderDiagnosticsAndRebuildResolverAreNeverSnapshotted() {
        val audioValues = ArrayDeque(listOf(true, false))
        val suffixHarness = Harness()
        suffixHarness.audioEnabledProvider = { audioValues.removeFirst() }
        suffixHarness.send(linkedMapOf("type" to "requestUI"))
        assertTrue(
            "第二次 enabled=false 时不得补 suffix",
            !suffixHarness.output.enqueues
                .single()
                .reason
                .contains("；音频流未建立，暂不声明主音频"),
        )
        assertEquals(2, suffixHarness.audioEnabledReads)

        val reverse = ArrayDeque(listOf(false, true))
        val reverseHarness = Harness()
        reverseHarness.audioEnabledProvider = { reverse.removeFirst() }
        reverseHarness.send(linkedMapOf("type" to "requestUI"))
        assertTrue(
            "第二次 enabled=true 时补 suffix",
            reverseHarness.output.enqueues
                .single()
                .reason
                .contains("；音频流未建立，暂不声明主音频"),
        )

        val sender = FakeSender(requestResult = true, gap = 5L, external = false, transcoding = false)
        sender.onRequest = {
            sender.gap = 42L
            sender.external = true
        }
        val keyFrame = Harness()
        keyFrame.sender = sender
        keyFrame.send(linkedMapOf("type" to "forceKeyFrame"))
        assertTrue(
            keyFrame.output.logs
                .single()
                .contains("距上个已发 IDR 42ms"),
        )
        assertTrue(
            keyFrame.output.logs
                .single()
                .contains("透传源"),
        )

        val rebuild = Harness()
        rebuild.rebuildResult = true
        rebuild.send(linkedMapOf("type" to "requestUI"))
        assertTrue(
            rebuild.output.enqueues
                .first()
                .rebuildStream,
        )
        rebuild.rebuildResult = false
        rebuild.send(linkedMapOf("type" to "requestUI"))
        assertFalse(
            rebuild.output.enqueues
                .last()
                .rebuildStream,
        )
        assertEquals("每次 requestUI 只求值一次", 2, rebuild.rebuildReads)
        assertEquals(0, rebuild.touchTargetReads)
    }

    @Test
    fun d3EntryGuardAndPassedCommandBehaviorDoNotAddMidCommandChecks() {
        val harness = Harness()
        harness.clientIdentity = 1
        harness.nowProvider = {
            harness.scope.active = false
            100L
        }
        harness.send(hidCommand(null, byteArrayOf(0xB5.toByte())))
        assertEquals("通过入口 guard 后保留既有行为", 1, harness.output.mediaKeys.size)

        harness.scope.active = true
        harness.clientIdentity = 2
        harness.send(hidCommand(null, byteArrayOf(0xB5.toByte())))
        assertEquals("下一条事件重新检查 stale", 1, harness.output.mediaKeys.size)
    }

    @Test
    fun x1UnknownTypesAndAliasesOnlyProduceTheOriginalDiagnostic() {
        val types =
            listOf<Any?>(
                null,
                7,
                "HIDSENDREPORT",
                "hidsendreport",
                "iAPSendEvent",
                "event",
                "ordinary-private-command",
            )
        types.forEach { type ->
            val harness = Harness()
            harness.send(linkedMapOf("type" to type, "params" to linkedMapOf("a" to 1, "b" to 2)))
            assertEquals("type=$type", 1, harness.output.logs.size)
            assertTrue(harness.output.mediaKeys.isEmpty())
            assertTrue(harness.output.navigationKeys.isEmpty())
            assertTrue(harness.output.enqueues.isEmpty())
            assertEquals(0, harness.counters.requestUiCount)
            assertEquals(0, harness.counters.touchLogCount)
            assertEquals(0, harness.counters.iapEventMessageCount)
        }

        val harness = Harness()
        val command = LinkedHashMap<Any?, Any?>()
        command["type"] = "private"
        command[null] = "nil-key"
        command[7] = "number-key"
        command["type"] = "private"
        harness.send(command)
        assertEquals("EVENT: 命令 type=private null=nil-key 7=number-key", harness.output.logs.single())
    }

    @Test
    fun x2UnknownLogAppliesPerValue240AndTail600LimitsExactly() {
        listOf(239, 240, 241).forEach { length ->
            val harness = Harness()
            harness.send(linkedMapOf("type" to "unknown", "v" to "a".repeat(length)))
            val expected = "a".repeat(minOf(length, 240))
            assertEquals("EVENT: 命令 type=unknown v=$expected", harness.output.logs.single())
        }

        val nullValue = Harness()
        nullValue.send(linkedMapOf("type" to "unknown", "v" to null))
        assertEquals("EVENT: 命令 type=unknown v=null", nullValue.output.logs.single())

        val bytes = ByteArray(301) { 0x5A }
        val bytesHarness = Harness()
        bytesHarness.send(linkedMapOf("type" to "unknown", "blob" to bytes))
        val fullHex = bytes.joinToString("") { "%02x".format(it) }
        assertEquals("EVENT: 命令 type=unknown ${"blob=$fullHex".take(600)}", bytesHarness.output.logs.single())

        listOf(111, 112, 113).forEach { thirdLength ->
            val harness = Harness()
            val command =
                linkedMapOf<String, Any?>(
                    "type" to "unknown",
                    "a" to "a".repeat(240),
                    "b" to "b".repeat(240),
                    "c" to "c".repeat(thirdLength),
                )
            harness.send(command)
            val tail =
                "a=${"a".repeat(240)} b=${"b".repeat(240)} c=${"c".repeat(thirdLength)}"
            val expectedTail = tail.take(600)
            assertEquals("EVENT: 命令 type=unknown $expectedTail", harness.output.logs.single())
            assertEquals(minOf(600, tail.length), expectedTail.length)
            assertFalse(
                harness.output.logs
                    .single()
                    .contains("…"),
            )
        }

        val longType = "t".repeat(700)
        val longTypeHarness = Harness()
        longTypeHarness.send(linkedMapOf("type" to longType, "v" to "x"))
        assertEquals("EVENT: 命令 type=$longType v=x", longTypeHarness.output.logs.single())
    }

    @Test
    fun x3UnknownLogStringAndSinkFailuresPropagateTheSameException() {
        val valueFailure = IllegalArgumentException("toString failed")
        val command = linkedMapOf<String, Any?>("type" to "unknown", "bad" to ThrowingValue(valueFailure))
        val valueHarness = Harness()
        try {
            valueHarness.send(command)
            fail("value.toString 异常必须原对象传播")
        } catch (caught: IllegalArgumentException) {
            assertSame(valueFailure, caught)
        }

        val logFailure = IllegalStateException("sink failed")
        val logHarness = Harness()
        logHarness.output.logFailure = logFailure
        try {
            logHarness.send(linkedMapOf("type" to "unknown", "v" to 1))
            fail("日志口异常必须原对象传播")
        } catch (caught: IllegalStateException) {
            assertSame(logFailure, caught)
        }
    }

    private fun hidCommand(
        uuid: Any?,
        report: Any?,
    ): Map<*, *> =
        linkedMapOf(
            "type" to "hidSendReport",
            "uuid" to uuid,
            "hidReport" to report,
        )

    private fun audioModes(awaiting: Boolean): ModeArbiter {
        val modes = ModeArbiter()
        modes.onChangeModes(resources(audioTake(constraint = if (awaiting) 1_000 else 500)))
        if (awaiting) {
            modes.onAudioStreamStarting()
            modes.onChangeModes(resources(audioTransfer(type = ModeArbiter.TRANSFER_UNTAKE)))
            check(modes.audioClaimAwaitingReturn)
        }
        return modes
    }

    private fun resources(vararg resource: Map<String, Long>): Map<String, Any> = mapOf("resources" to resource.toList())

    private fun screenTake(constraint: Long): Map<String, Long> =
        resource(ModeArbiter.SCREEN, ModeArbiter.TRANSFER_TAKE.toLong(), constraint, constraint)

    private fun screenBorrow(unborrowConstraint: Long): Map<String, Long> =
        resource(
            ModeArbiter.SCREEN,
            ModeArbiter.TRANSFER_BORROW.toLong(),
            500L,
            unborrowConstraint,
        )

    private fun audioTake(constraint: Long): Map<String, Long> =
        resource(
            ModeArbiter.MAIN_AUDIO,
            ModeArbiter.TRANSFER_TAKE.toLong(),
            constraint,
            constraint,
        )

    private fun audioTransfer(type: Int): Map<String, Long> =
        resource(
            ModeArbiter.MAIN_AUDIO,
            type.toLong(),
            500L,
            100L,
        )

    private fun resource(
        id: Long,
        type: Long,
        takeConstraint: Long,
        borrowConstraint: Long,
    ): Map<String, Long> =
        linkedMapOf(
            "resourceID" to id,
            "transferType" to type,
            "transferPriority" to 500L,
            "takeConstraint" to takeConstraint,
            "borrowConstraint" to borrowConstraint,
            "unborrowConstraint" to borrowConstraint,
        )

    private fun assertNoDispatch(harness: Harness) {
        assertTrue(harness.output.logs.isEmpty())
        assertTrue(harness.output.mediaKeys.isEmpty())
        assertTrue(harness.output.navigationKeys.isEmpty())
        assertTrue(harness.output.enqueues.isEmpty())
        assertTrue(harness.output.backgrounds.isEmpty())
        assertTrue(harness.client.iapData.isEmpty())
        assertEquals(0, harness.clockCalls)
        assertEquals(0, harness.audioEnabledReads)
        assertEquals(0, harness.audioStreamRunningReads)
        assertEquals(0, harness.videoSenderReads)
        assertEquals(0, harness.touchTargetReads)
        assertEquals(0, harness.rebuildReads)
    }

    private class Harness(
        descriptors: Map<String, ByteArray> = emptyMap(),
        modes: ModeArbiter = ModeArbiter(),
        inputs: EventCommandRouter.EventInputs = FakeInputs(),
    ) {
        val modeArbiter = modes
        val trace = Trace()
        val scope = FakeScope()
        val counters = RecordingCounters(trace)
        val client = RecordingClient(modes, trace)
        val output = RecordingOutput(trace)
        var stale = false
        var isStaleProvider: () -> Boolean = { stale }
        var clientIdentity = 1
        var sender: FakeSender? = null
        var touchTarget: FakeTouchTarget? = null
        var audioEnabledProvider: () -> Boolean = { false }
        var audioStreamRunningValue = false
        var audioEnabledReads = 0
        var audioStreamRunningReads = 0
        var videoSenderReads = 0
        var touchTargetReads = 0
        var rebuildReads = 0
        var rebuildResult = false
        var clockCalls = 0
        var nowProvider: () -> Long = { 1_000L + clockCalls++ }

        private val router =
            EventCommandRouter(
                scope = scope,
                setup = setup(descriptors),
                isStaleClient = { isStaleProvider() || clientIdentity != 1 },
                client = client,
                counters = counters,
                nowUptimeMillis = {
                    trace.add("clock")
                    nowProvider()
                },
                output = output,
                currentVideoSender = {
                    videoSenderReads++
                    trace.add("videoSender")
                    sender
                },
                currentTouchTarget = {
                    touchTargetReads++
                    trace.add("touchTarget")
                    touchTarget
                },
                audioEnabled = {
                    audioEnabledReads++
                    trace.add("audioEnabled")
                    audioEnabledProvider()
                },
                audioStreamRunning = {
                    audioStreamRunningReads++
                    trace.add("audioRunning")
                    audioStreamRunningValue
                },
                resolveScreenReturnRebuild = { requested ->
                    rebuildReads++
                    trace.add("resolveRebuild")
                    rebuildResult
                },
                inputs = inputs,
            )

        fun send(command: Map<*, *>) = router.onCommand(command)
    }

    private class FakeScope : AttemptScope {
        var active = true
        var activeReads = 0

        override val id = "test"
        override val observation: ReceiverObservation? = null
        override val isCurrent: Boolean = true
        override val cancelReason: DiagnosticCode? = null

        override val isActive: Boolean
            get() {
                activeReads++
                return active
            }

        override fun cancel(reason: DiagnosticCode) {
            active = false
        }

        override fun checkActive() {
            if (!isActive) throw AttemptCancelled(cancelReason)
        }

        override fun <T : AutoCloseable> own(resource: T): T = resource

        override fun await(
            latch: CountDownLatch,
            timeoutMs: Long,
        ): Boolean = isActive && latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        override fun sleep(ms: Long): Boolean = isActive
    }

    private class RecordingClient(
        override val modes: ModeArbiter,
        private val trace: Trace,
    ) : EventCommandRouter.EventClient {
        val iapData = mutableListOf<ByteArray>()
        var iapFailure: Throwable? = null

        override fun onIapEventBytes(data: ByteArray) {
            trace.add("iap-bytes")
            iapFailure?.let { throw it }
            iapData += data
        }
    }

    private class RecordingCounters(
        private val trace: Trace,
    ) : EventCommandRouter.EventCounters {
        override var touchLogCount: Int = 0
            set(value) {
                trace.add("counter.touch=$value")
                field = value
            }

        override var consumerReleaseCount: Int = 0
            set(value) {
                trace.add("counter.release=$value")
                field = value
            }

        override var iapEventMessageCount: Int = 0
            set(value) {
                trace.add("counter.iap=$value")
                field = value
            }

        override var forceKeyFrameCount: Int = 0
            set(value) {
                trace.add("counter.force=$value")
                field = value
            }

        override var requestUiCount: Int = 0
            set(value) {
                trace.add("counter.requestUi=$value")
                field = value
            }
    }

    private data class MediaCall(
        val keyCode: Int,
        val label: String,
    )

    private data class EnqueueCall(
        val reason: String,
        val requestKeyFrame: Boolean,
        val retakeScreen: Boolean,
        val rebuildStream: Boolean,
    )

    private class PendingBackground(
        val name: String,
        val task: () -> Unit,
    ) {
        var executed = false
    }

    private class RecordingOutput(
        private val trace: Trace,
    ) : EventCommandRouter.EventOutput {
        val logs = mutableListOf<String>()
        val mediaKeys = mutableListOf<MediaCall>()
        val navigationKeys = mutableListOf<Int>()
        val enqueues = mutableListOf<EnqueueCall>()
        val backgrounds = mutableListOf<PendingBackground>()
        val routeThreads = mutableListOf<Thread>()
        var touchSentCalls = 0
        var retryCalls = 0
        var logFailure: Throwable? = null
        var enqueueFailure: Throwable? = null
        var retryFailure: Throwable? = null

        override fun appendLog(line: String) {
            logFailure?.let { throw it }
            trace.add("log")
            logs += line
        }

        override fun routeCarMediaKey(
            keyCode: Int,
            label: String,
        ) {
            trace.add("media")
            mediaKeys += MediaCall(keyCode, label)
            routeThreads += Thread.currentThread()
        }

        override fun injectNavigationKey(keyCode: Int) {
            trace.add("navigation")
            navigationKeys += keyCode
        }

        override fun enqueueModesAssert(
            reason: String,
            requestKeyFrame: Boolean,
            retakeScreen: Boolean,
            rebuildStream: Boolean,
        ) {
            enqueueFailure?.let { throw it }
            trace.add("enqueue")
            enqueues += EnqueueCall(reason, requestKeyFrame, retakeScreen, rebuildStream)
        }

        override fun runBackground(
            name: String,
            task: () -> Unit,
        ) {
            trace.add("background")
            backgrounds += PendingBackground(name, task)
        }

        override fun retryAudioAfterClaimReturned() {
            trace.add("retry")
            retryFailure?.let { throw it }
            retryCalls++
        }

        override fun onTouchSent() {
            trace.add("touch-sent")
            touchSentCalls++
        }

        fun drainBackgrounds() {
            backgrounds.forEach { pending ->
                if (!pending.executed) {
                    pending.executed = true
                    pending.task()
                }
            }
        }
    }

    private class FakeSender(
        var requestResult: Boolean = false,
        var gap: Long? = null,
        var external: Boolean = false,
        var transcoding: Boolean = false,
        var failure: Throwable? = null,
    ) : EventCommandRouter.KeyFrameSender {
        var requestCalls = 0
        var gapReads = 0
        var externalReads = 0
        var transcodingReads = 0
        var onRequest: (() -> Unit)? = null

        override fun requestKeyFrame(): Boolean {
            requestCalls++
            onRequest?.invoke()
            failure?.let { throw it }
            return requestResult
        }

        override val lastReceiverRequestIdrGapMs: Long?
            get() {
                gapReads++
                return gap
            }

        override val isExternalPassthrough: Boolean
            get() {
                externalReads++
                return external
            }

        override val isTranscoding: Boolean
            get() {
                transcodingReads++
                return transcoding
            }
    }

    private class FakeTouchTarget(
        var width: Int = 100,
        var height: Int = 100,
        var result: Boolean = true,
        var failure: Throwable? = null,
        var report: Boolean? = null,
    ) : EventCommandRouter.TouchTarget {
        val sends = mutableListOf<Triple<Int, Int, Boolean>>()
        var sendCalls = 0
        var widthReads = 0
        var heightReads = 0
        var onWidth: (() -> Unit)? = null

        override val videoWidth: Int
            get() {
                widthReads++
                onWidth?.invoke()
                return width
            }

        override val videoHeight: Int
            get() {
                heightReads++
                return height
            }

        override fun sendTouch(
            x: Int,
            y: Int,
            down: Boolean,
        ): Boolean {
            sendCalls++
            failure?.let { throw it }
            sends += Triple(x, y, down)
            return result
        }
    }

    private class FakeInputs : EventCommandRouter.EventInputs {
        var raw = false
        var navigation = emptyList<Int>()
        var contacts: List<TouchReports.Contact>? = null
        var parseFailure: Throwable? = null
        var handleCalls = 0
        var touchFormatCalls = 0
        var touchParseCalls = 0

        override fun rawLogEnabled(): Boolean = raw

        override fun handleNavigation(
            uuid: String,
            descriptor: ByteArray?,
            report: ByteArray,
        ): List<Int> {
            handleCalls++
            return navigation
        }

        override fun navigationName(keyCode: Int): String =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER -> "确认"
                KeyEvent.KEYCODE_DPAD_DOWN -> "下"
                else -> "$keyCode"
            }

        override fun touchFormat(descriptor: ByteArray): TouchReports.Format? {
            touchFormatCalls++
            return null
        }

        override fun parseTouch(
            report: ByteArray,
            format: TouchReports.Format?,
        ): List<TouchReports.Contact>? {
            touchParseCalls++
            parseFailure?.let { throw it }
            return contacts
        }
    }

    private class CountingDescriptorMap(
        val descriptorValues: MutableMap<String, ByteArray>,
    ) : AbstractMap<String, ByteArray>() {
        var getCalls = 0

        override val entries: Set<Map.Entry<String, ByteArray>>
            get() = descriptorValues.entries

        override fun get(key: String): ByteArray? {
            getCalls++
            return descriptorValues[key]
        }
    }

    private class PoisonMap : AbstractMap<Any?, Any?>() {
        override val entries: Set<Map.Entry<Any?, Any?>>
            get() = throw AssertionError("guard 短路后不得读取 command.entries")

        override fun get(key: Any?): Any? = throw AssertionError("guard 短路后不得读取 command")
    }

    private class ThrowingValue(
        private val failure: Throwable,
    ) {
        override fun toString(): String = throw failure
    }

    private class Trace {
        val events = mutableListOf<String>()

        fun add(event: String) {
            events += event
        }
    }

    companion object {
        private fun setup(
            descriptors: Map<String, ByteArray>,
            width: Int = 100,
            height: Int = 100,
        ): AirPlayClient.SetupInfo =
            AirPlayClient.SetupInfo(
                eventPort = 1,
                timingPort = 2,
                keepAlivePort = null,
                enabledFeatures = emptyList(),
                displayWidth = width,
                displayHeight = height,
                displayUuid = "display",
                maxFps = 60,
                hidDeviceUuids = descriptors.keys.toList(),
                hidDescriptors = descriptors,
            )

        private fun hex(value: String): ByteArray =
            value
                .trim()
                .split(Regex("\\s+"))
                .map { it.toInt(16).toByte() }
                .toByteArray()

        private val AUDI_ARRAY_DESCRIPTOR =
            hex(
                "05 0C 09 01 A1 01 15 00 25 08 75 08 95 01 " +
                    "09 00 09 B0 09 B1 09 B3 09 B4 09 B5 09 B6 09 B9 09 BC " +
                    "81 00 C0",
            )
        private val HONDA_BITMAP_DESCRIPTOR =
            hex(
                "05 0c 09 01 a1 01 15 00 25 01 75 01 95 05 " +
                    "09 b0 09 b1 09 b5 09 b6 09 b9 81 02 75 03 95 01 81 03 c0",
            )
        private val AUDI_TOUCH_DESCRIPTOR =
            hex(
                "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 " +
                    "15 00 25 01 75 01 95 01 81 02 75 07 95 01 81 01 " +
                    "05 01 09 30 15 00 26 84 05 75 10 95 01 81 02 " +
                    "09 31 15 00 26 8e 02 75 10 95 01 81 02 c0 c0",
            )
        private val GENERIC_WHEEL_DESCRIPTOR =
            hex("05 01 09 38 a1 01 09 30 15 00 25 01 75 01 95 01 81 02 c0")
        private val GENERIC_HAT_DESCRIPTOR =
            hex("05 01 09 39 a1 01 09 30 15 00 25 07 75 04 95 01 81 02 c0")
    }
}
