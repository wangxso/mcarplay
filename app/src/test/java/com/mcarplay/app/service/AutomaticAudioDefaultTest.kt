package com.mcarplay.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #55：会话仍在运行时，车机蓝牙 ACL 回连触发的自动连接不得改写本次会话的音频选择。
 *
 * 车主已选「仅视频」，自动连接曾把 `audioEnabledForSession` 重新置为 true，车机随后发起的
 * AirPlay 会话又带音频并被拆链。这里锁定「运行中会话沿用原选择、也不申请投屏授权」。
 */
class AutomaticAudioDefaultTest {
    @Test
    fun runningSessionKeepsItsOwnAudioSelectionAndSkipsProjectionRequest() {
        var defaultEvaluated = false
        val value =
            automaticAudioDefault(sessionRunning = true) {
                defaultEvaluated = true
                true
            }
        assertNull("运行中的会话不得被自动连接改写音频选择", value)
        assertFalse("运行中的会话不应为默认值申请投屏授权", defaultEvaluated)
    }

    @Test
    fun newSessionUsesTheAutomaticDefault() {
        assertEquals(true, automaticAudioDefault(sessionRunning = false) { true })
        assertEquals(false, automaticAudioDefault(sessionRunning = false) { false })
    }
}
