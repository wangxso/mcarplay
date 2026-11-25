package com.mcarplay.app.service

import com.mcarplay.app.RoundActions
import com.mcarplay.app.RoundRetirement
import com.mcarplay.app.RoundSlots
import com.mcarplay.app.VideoEndActions
import com.mcarplay.app.VideoEndCapture
import com.mcarplay.app.VideoEndSlots
import com.mcarplay.app.VideoEndedHandling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPowerControllerTest {
    private class FakePorts(
        private val runInline: Boolean = true,
    ) : ScreenPowerPorts {
        var contextAvailable = true
        var streaming = true
        var interactive = true
        var dimmerAttached = true
        var setPowerResult = true
        var shizukuReady = true
        var inputServiceBound = false
        var bindOnEnsureAttempt: Int? = null

        var nowMs = 1_000L
        var ensureCalls = 0
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val queuedTasks = mutableListOf<Pair<String, () -> Unit>>()

        override fun hasContext(): Boolean = contextAvailable

        override fun isStreaming(): Boolean = streaming

        override fun isInteractive(): Boolean = interactive

        override fun isDimmerAttached(): Boolean = dimmerAttached

        override fun enterDimNow() {
            events += "enterDimNow"
        }

        override fun exitPanelOff() {
            events += "exitPanelOff"
        }

        override fun setPhysicalScreenPower(on: Boolean): Boolean {
            events += "setPhysicalScreenPower($on)"
            return setPowerResult
        }

        override fun shellExec(
            command: String,
            timeoutMs: Long,
        ) {
            events += "shellExec($command,$timeoutMs)"
        }

        override fun isShizukuReady(): Boolean = shizukuReady

        override fun isInputServiceBound(): Boolean = inputServiceBound

        override fun ensureInputService() {
            ensureCalls++
            events += "ensureInputService#$ensureCalls"
            if (bindOnEnsureAttempt == ensureCalls) {
                inputServiceBound = true
            }
        }

        override fun runBackground(
            name: String,
            block: () -> Unit,
        ) {
            events += "bg:$name"
            if (runInline) {
                block()
            } else {
                queuedTasks += name to block
            }
        }

        override fun uptimeMillis(): Long = nowMs

        override fun sleep(ms: Long) {
            events += "sleep($ms)"
            nowMs += ms
        }

        override fun log(message: String) {
            logs += message
        }
    }

    @Test
    fun enterOffShortCircuitsWhenNoContextOrNotStreaming() {
        val ports = FakePorts()
        val controller = ScreenPowerController(ports)

        ports.contextAvailable = false
        controller.enterOff()
        assertTrue(ports.events.isEmpty())

        ports.contextAvailable = true
        ports.streaming = false
        controller.enterOff()
        assertTrue(ports.events.isEmpty())
        assertFalse(controller.isScreenOff)
    }

    @Test
    fun enterOffSkipsInsideLockWhenStreamEndedBeforeBackgroundTaskRan() {
        val ports = FakePorts(runInline = false)
        val controller = ScreenPowerController(ports)

        controller.enterOff()
        assertEquals(1, ports.queuedTasks.size)

        ports.streaming = false
        ports.queuedTasks
            .removeAt(0)
            .second
            .invoke()

        assertFalse(controller.isScreenOff)
        assertEquals(listOf("bg:screen-power-off"), ports.events)
        assertEquals(listOf("SCREEN: 会话已结束——跳过物理息屏"), ports.logs)
    }

    @Test
    fun enterOffWakesSleepingDeviceBeforeEnteringDimmer() {
        val ports = FakePorts()
        ports.interactive = false
        ports.dimmerAttached = true
        val controller = ScreenPowerController(ports)

        controller.enterOff()

        assertTrue(controller.isScreenOff)
        assertEquals(
            listOf(
                "bg:screen-power-off",
                "shellExec(input keyevent KEYCODE_WAKEUP,4000)",
                "sleep(400)",
                "shellExec(wm dismiss-keyguard,3000)",
                "enterDimNow",
            ),
            ports.events,
        )
        assertEquals(
            listOf(
                "SCREEN: 手机处于休眠——先唤醒再物理息屏（避免框架把面板重新点亮）",
                "SCREEN: 真息屏已进入（暗幕承载触摸检测——点暗幕唤醒，无需按电源键）",
            ),
            ports.logs,
        )
    }

    @Test
    fun enterOffIsIdempotentOnceScreenIsOff() {
        val ports = FakePorts()
        val controller = ScreenPowerController(ports)

        controller.enterOff()
        controller.enterOff()

        assertEquals(listOf("bg:screen-power-off", "enterDimNow", "bg:screen-power-off"), ports.events)
        assertEquals(1, ports.logs.size)
    }

    @Test
    fun enterOffFallsBackToRawPhysicalPowerWhenDimmerNotAttached() {
        val ports = FakePorts()
        ports.dimmerAttached = false
        ports.setPowerResult = true
        val controller = ScreenPowerController(ports)

        controller.enterOff()

        assertTrue(controller.isScreenOff)
        assertEquals(listOf("bg:screen-power-off", "setPhysicalScreenPower(false)"), ports.events)
        assertEquals(
            listOf("SCREEN: 物理屏已断电（暗幕不可用——无触摸唤醒手段，按电源键两次可恢复）"),
            ports.logs,
        )
    }

    @Test
    fun enterOffWithoutDimmerLeavesScreenOffFalseOnPowerFailure() {
        val ports = FakePorts()
        ports.dimmerAttached = false
        ports.setPowerResult = false
        val controller = ScreenPowerController(ports)

        controller.enterOff()

        assertFalse(controller.isScreenOff)
        assertEquals(listOf("bg:screen-power-off", "setPhysicalScreenPower(false)"), ports.events)
        assertEquals(
            listOf("SCREEN: 物理屏断电失败（ROM 未给 shell 权限 / 取不到 display token）"),
            ports.logs,
        )
    }

    @Test
    fun restoreIsNoOpWhenScreenWasNotTurnedOff() {
        val ports = FakePorts()
        val controller = ScreenPowerController(ports)

        controller.restore(sleepIfCarSide = false)
        controller.restore(sleepIfCarSide = true)

        assertEquals(listOf("bg:screen-power-restore", "bg:screen-power-restore"), ports.events)
        assertTrue(ports.logs.isEmpty())
    }

    @Test
    fun restoreWithSleepFalseExitsDimmerAndLightsUpPanel() {
        val ports = FakePorts()
        val controller = ScreenPowerController(ports)
        controller.enterOff()
        ports.events.clear()
        ports.logs.clear()

        controller.restore(sleepIfCarSide = false)
        controller.restore(sleepIfCarSide = false)

        assertFalse(controller.isScreenOff)
        assertEquals(
            listOf(
                "bg:screen-power-restore",
                "exitPanelOff",
                "setPhysicalScreenPower(true)",
                "bg:screen-power-restore",
            ),
            ports.events,
        )
        assertEquals(listOf("SCREEN: 物理屏已恢复点亮 ok=true"), ports.logs)
    }

    @Test
    fun restoreWithSleepTruePutsDeviceToSleepKeepingPanelDark() {
        val ports = FakePorts()
        val controller = ScreenPowerController(ports)
        controller.enterOff()
        ports.events.clear()
        ports.logs.clear()

        controller.restore(sleepIfCarSide = true)

        assertFalse(controller.isScreenOff)
        assertEquals(
            listOf(
                "bg:screen-power-restore",
                "shellExec(input keyevent KEYCODE_SLEEP,4000)",
            ),
            ports.events,
        )
        assertEquals(listOf("SCREEN: 车机侧断开——已让手机休眠（面板保持黑）"), ports.logs)
    }

    @Test
    fun applyPreferenceTogglesBetweenEnterOffAndLightUpRestore() {
        val ports = FakePorts()
        val controller = ScreenPowerController(ports)

        controller.applyPreference(true)
        assertTrue(controller.isScreenOff)

        ports.events.clear()
        controller.applyPreference(false)
        assertFalse(controller.isScreenOff)
        assertEquals(
            listOf("bg:screen-power-restore", "exitPanelOff", "setPhysicalScreenPower(true)"),
            ports.events,
        )
    }

    @Test
    fun restoreAtStartupSkipsWhenShizukuNotReady() {
        val ports = FakePorts()
        ports.shizukuReady = false
        val controller = ScreenPowerController(ports)

        controller.restoreAtStartup()

        assertEquals(listOf("bg:screen-power-startup-restore"), ports.events)
        assertTrue(ports.logs.isEmpty())
    }

    @Test
    fun restoreAtStartupPollsUntilBoundAndLogsOnlyOnPowerFailure() {
        val ports = FakePorts()
        ports.bindOnEnsureAttempt = 2
        ports.setPowerResult = false
        val controller = ScreenPowerController(ports)

        controller.restoreAtStartup()

        assertEquals(
            listOf(
                "bg:screen-power-startup-restore",
                "ensureInputService#1",
                "sleep(150)",
                "ensureInputService#2",
                "sleep(150)",
                "setPhysicalScreenPower(true)",
            ),
            ports.events,
        )
        assertEquals(listOf("SCREEN: 启动恢复物理屏失败（ROM 未授权 shell 电源控制）"), ports.logs)
    }

    @Test
    fun restoreAtStartupStopsAtDeadlineWithoutCallingPowerWhenUnbound() {
        val ports = FakePorts()
        ports.inputServiceBound = false
        val controller = ScreenPowerController(ports)

        controller.restoreAtStartup()

        assertEquals(20, ports.ensureCalls)
        assertFalse(ports.events.any { it.startsWith("setPhysicalScreenPower") })
        assertTrue(ports.logs.isEmpty())
    }

    @Test
    fun lateEnterOffQueuedBeforeSessionEndCannotDarkenScreenAfterRestore() {
        val ports = FakePorts(runInline = false)
        val controller = ScreenPowerController(ports)

        controller.enterOff()
        // 模拟 restore 先跑完且会话已结束，随后迟到的 enterOff 任务才被线程调度执行
        ports.streaming = false
        controller.restore(sleepIfCarSide = false)
        assertEquals(2, ports.queuedTasks.size)

        // 先执行 restore，再执行迟到的 enterOff
        ports.queuedTasks[1].second.invoke()
        ports.queuedTasks[0].second.invoke()

        assertFalse(controller.isScreenOff)
        assertFalse(ports.events.contains("enterDimNow"))
        assertFalse(ports.events.any { it.startsWith("setPhysicalScreenPower") })
        assertEquals(listOf("SCREEN: 会话已结束——跳过物理息屏"), ports.logs)
    }

    @Test
    fun b13VideoEndedDeactivatesDimmerAndSleepsDeviceWithoutLightingPanel() {
        val ports = FakePorts()
        val power = ScreenPowerController(ports)
        power.enterOff()
        ports.events.clear()
        ports.logs.clear()

        val sender = Any()
        val trace = mutableListOf<String>()
        val handling =
            VideoEndedHandling(
                slots =
                    object : VideoEndSlots<Any, Any, Any, Any> {
                        override val currentSender: Any = sender
                        override val outputSender: Any = sender
                        override val nativeBridge: Any? = null
                        override val btClient: Any? = null
                        override val context: Any? = null
                        override val forceKeyFrames: Int = 0
                        override val requestUi: Int = 0
                        override val touches: Int = 0
                        override val consumerReleases: Int = 0

                        override fun clearCarLifeOutput() = Unit

                        override fun clearNativeBridge() = Unit

                        override fun setSessionInactive() {
                            trace += "setSessionInactive"
                        }

                        override fun setDimmerInactive() {
                            trace += "setDimmerInactive"
                        }

                        override fun resetEnvironmentSampleAt() = Unit
                    },
                actions =
                    object : VideoEndActions<Any, Any, Any, Any, Any> {
                        override fun log(message: String) = Unit

                        override fun videoEndedTimeline(attempt: String) = Unit

                        override fun mediaEnded(observation: Any?) = Unit

                        override fun stopCarLife(carLife: Any) = Unit

                        override fun releaseCarLife(carLife: Any) = Unit

                        override fun releaseWifiLock() = Unit

                        override fun stopScreenMonitor(context: Any) = Unit

                        override fun stopNativeBridge(bridge: Any) = Unit

                        override fun armBluetoothAfterSessionEnd(btClient: Any) = Unit

                        override fun restorePhoneScreen(sleepIfCarSide: Boolean) {
                            trace += "restorePhoneScreen($sleepIfCarSide)"
                            power.restore(sleepIfCarSide)
                        }

                        override fun cleanupShellFallbackNetwork() = Unit

                        override fun attributeAudioTeardown() = Unit

                        override fun recordAutoReconnectOutcome(
                            attempt: String?,
                            peer: String?,
                        ): Boolean = false

                        override fun disconnect(context: Any) = Unit
                    },
            )

        handling.handle(
            object : VideoEndCapture<Any, Any> {
                override val senderIdentity: () -> Any = { sender }
                override val carLifeBinding: () -> Any? = { null }
                override val mediaObservation: () -> Any? = { null }
                override val attempt: String = "attempt-b13"
                override val peerAddress: String? = null
            },
        )

        // 附录 B-13：车机拆流必须同时停掉暗幕/常亮，且以 sleepIfCarSide=true 休眠（不点亮面板）
        assertEquals(listOf("setSessionInactive", "setDimmerInactive", "restorePhoneScreen(true)"), trace)
        assertFalse(power.isScreenOff)
        assertEquals(
            listOf("bg:screen-power-restore", "shellExec(input keyevent KEYCODE_SLEEP,4000)"),
            ports.events,
        )
        assertFalse(ports.events.contains("exitPanelOff"))
        assertFalse(ports.events.contains("setPhysicalScreenPower(true)"))
    }

    @Test
    fun retireSessionAndDisconnectPathsDeactivateDimmerAndLightUpPanel() {
        listOf(
            "retireSession" to (true to false),
            "disconnect" to (false to true),
        ).forEach { (label, flags) ->
            val ports = FakePorts()
            val power = ScreenPowerController(ports)
            power.enterOff()
            ports.events.clear()
            ports.logs.clear()

            val trace = mutableListOf<String>()
            val retirement =
                RoundRetirement(
                    slots =
                        object : RoundSlots<Any, Any, Any, Any, Any> {
                            override var client: Any? = null
                            override var sender: Any? = null
                            override var audio: Any? = null
                            override var bridge: Any? = null
                            override val context: Any? = null

                            override fun clearRecovery() = Unit

                            override fun clearSharedSecret() = Unit

                            override fun clearAudioHost() = Unit

                            override fun markCancelled(client: Any) = Unit

                            override fun setSessionInactive() = Unit
                        },
                    actions =
                        object : RoundActions<Any, Any, Any, Any, Any> {
                            override fun beginClient(client: Any) = Unit

                            override fun stopAutoPlayWatch() = Unit

                            override fun stopSender(sender: Any) = Unit

                            override fun quiesceSender(sender: Any) = Unit

                            override fun releaseWifiLock() = Unit

                            override fun stopScreenMonitor(context: Any) = Unit

                            override fun setDimmerInactive() {
                                trace += "setDimmerInactive"
                            }

                            override fun restorePhoneScreen(sleepIfCarSide: Boolean) {
                                trace += "restorePhoneScreen($sleepIfCarSide)"
                                power.restore(sleepIfCarSide)
                            }

                            override fun stopAudio(audio: Any) = Unit

                            override fun stopBridge(bridge: Any) = Unit

                            override fun stopFormalCarLife() = Unit

                            override fun closeClient(client: Any) = Unit
                        },
                )

            retirement.retire(closeClient = flags.first, preserveScreenSocket = flags.second)

            assertEquals("$label 路径应先收暗幕再点亮物理屏", listOf("setDimmerInactive", "restorePhoneScreen(false)"), trace)
            assertFalse("$label 路径应清除息屏状态", power.isScreenOff)
            assertEquals(
                "$label 路径应撤暗幕并恢复面板供电",
                listOf("bg:screen-power-restore", "exitPanelOff", "setPhysicalScreenPower(true)"),
                ports.events,
            )
            assertFalse("$label 路径不得发 KEYCODE_SLEEP", ports.events.any { it.contains("KEYCODE_SLEEP") })
        }
    }
}
