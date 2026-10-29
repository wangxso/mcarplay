package com.mcarplay.app.shizuku

import com.mcarplay.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuGuidanceTest {
    @Test
    fun notRunningTellsUserToStartService() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.NOT_RUNNING)
        assertEquals(R.string.shizuku_not_running_title, guidance.title)
        // 必须给出可执行动作（打开 Shizuku App 启动），否则用户无从下手
        assertEquals(R.string.ma_open_shizuku, guidance.primaryLabel)
        assertEquals(R.string.shizuku_not_running_body, guidance.message)
    }

    @Test
    fun unauthorizedTellsUserToGrant() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.UNAUTHORIZED)
        assertEquals(R.string.shizuku_unauthorized_title, guidance.title)
        assertEquals(R.string.ma_request_permission, guidance.primaryLabel)
        assertEquals(R.string.shizuku_unauthorized_body, guidance.message)
    }

    @Test
    fun notInstalledHasNoFakeAction() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.NOT_INSTALLED)
        assertEquals(R.string.shizuku_not_installed_title, guidance.title)
        // 未安装时不该给一个编造的跳转/下载按钮
        assertNull(guidance.primaryLabel)
        assertNotNull(guidance.message)
    }

    @Test
    fun eachStateExplainsConsequenceOthersDoNotAllLookSame() {
        // 三态文案必须互不相同：混成一句「Shizuku 未就绪」用户无法判断该装/启动/授权
        val states =
            listOf(
                ShizukuGate.State.NOT_INSTALLED,
                ShizukuGate.State.NOT_RUNNING,
                ShizukuGate.State.UNAUTHORIZED,
            )
        val titles = states.map { ShizukuGate.guidanceOf(it).title }
        val messages = states.map { ShizukuGate.guidanceOf(it).message }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals(messages.size, messages.filterNotNull().toSet().size)
    }

    @Test
    fun readyHasGuidanceObject() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.READY)
        assertNotNull(guidance)
        assertNull(guidance.primaryLabel)
    }
}
