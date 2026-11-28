package com.mcarplay.app.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归（issue #62，2026-11-20 MB_E-P-4）：主屏 SETUP 后必须先连视频 dataPort，再处理
 * 可选的 type-130 隧道。隧道 SETUP 可能长时间无响应；排在端口之前会让屏幕连接迟到，
 * 车机的 ~2 s 看门狗随即 reset 整个控制通道。
 */
class MediaPortOrderingTest {
    @Test
    fun screenDataPortIsConnectedBeforeTheOptionalIapTunnel() {
        val code = SourceTree.scan("SessionController.kt").code
        val screen = code.indexOf("val screen = client.runScreenStream")
        val dataPort = code.indexOf("sessionVideoSender.openDataPort()", screen)
        val tunnel = code.indexOf("client.runIapTunnel(sessionKey)", screen)
        assertTrue("找不到主屏 SETUP", screen >= 0)
        assertTrue("找不到主屏 dataPort 连接", dataPort > screen)
        assertTrue("找不到 iAP 隧道 SETUP", tunnel > screen)
        assertTrue(
            "主屏 dataPort 必须先于可能阻塞的 type-130 SETUP 连接",
            dataPort < tunnel,
        )
    }

    /**
     * MB_E-P-4 的 HyperCar 对齐链路：屏幕 SETUP 前连发两次 `modesChanged`，且不发
     * `hidSetInputMode`。这些分支必须留在屏幕 SETUP 之前，并受车型策略控制。
     */
    @Test
    fun mbuxAlignmentRepeatsModesAndSkipsHidBeforeScreenSetup() {
        val code = SourceTree.scan("SessionController.kt").code
        val anchor = code.indexOf("val screenPolicy = client.receiverCompatibility()")
        val screen = code.indexOf("val screen = client.runScreenStream", anchor)
        val firstModes = code.indexOf("client.assertModes(acknowledgeInitialScreenClaim = true)", anchor)
        val secondModes = code.indexOf("client.assertModes()", firstModes + 1)
        val repeatGuard = code.indexOf("if (screenPolicy.repeatScreenModesDeclaration)", anchor)
        val hidGuard = code.indexOf("if (!screenPolicy.skipScreenHidArming)", anchor)
        val hidSend = code.indexOf("client.sendHidSetInputMode", anchor)
        assertTrue("找不到屏幕策略读取", anchor >= 0)
        assertTrue("找不到主屏 SETUP", screen > anchor)
        assertTrue("第一次资源声明必须在屏幕 SETUP 前", firstModes in (anchor + 1) until screen)
        assertTrue("第二次资源声明必须在屏幕 SETUP 前", secondModes in (firstModes + 1) until screen)
        assertTrue("二次声明必须受车型策略控制", repeatGuard in (firstModes + 1) until secondModes)
        assertTrue("HID 武装必须受车型策略控制", hidGuard in (secondModes + 1) until screen)
        assertTrue("HID 发送必须留在策略分支内", hidSend > hidGuard)
    }
}
