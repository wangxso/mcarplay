package com.mcarplay.app

import com.mcarplay.app.architecture.SourceTree
import com.mcarplay.app.session.DialogSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `SessionController.ui` 只是转发到对外保留的四个字段（Phase 1a，门面 API 不变）。 */
class SessionUiPortTest {
    private var savedPhase: String? = null
    private var savedHint: ((String) -> Unit)? = null
    private var savedSsid: ((String) -> Unit)? = null
    private var savedDialog: ((DialogSpec, (Boolean?) -> Unit) -> Unit)? = null

    @Before
    fun saveSingletonState() {
        savedPhase = SessionController.phaseHint
        savedHint = SessionController.uiHintSink
        savedSsid = SessionController.ssidSink
        savedDialog = SessionController.dialogSink
    }

    @After
    fun restoreSingletonState() {
        SessionController.phaseHint = savedPhase
        SessionController.uiHintSink = savedHint
        SessionController.ssidSink = savedSsid
        SessionController.dialogSink = savedDialog
    }

    @Test
    fun phaseWritesTheFacadeField() {
        SessionController.ui.phase("正在配对")
        assertEquals("正在配对", SessionController.phaseHint)
        SessionController.ui.phase(null)
        assertNull(SessionController.phaseHint)
    }

    @Test
    fun hintsAndSsidReachTheAttachedSinksAndAreDroppedWithoutOne() {
        val hints = mutableListOf<String>()
        val ssids = mutableListOf<String>()
        SessionController.uiHintSink = null
        SessionController.ssidSink = null
        SessionController.ui.hint("无人接收")
        SessionController.ui.ssidChanged("无人接收")
        SessionController.uiHintSink = { hints.add(it) }
        SessionController.ssidSink = { ssids.add(it) }
        SessionController.ui.hint("请打开 WLAN")
        SessionController.ui.ssidChanged("CarHotspot")
        assertEquals(listOf("请打开 WLAN"), hints)
        assertEquals(listOf("CarHotspot"), ssids)
    }

    @Test
    fun askReportsWhetherADialogCouldBeShown() {
        val spec = DialogSpec("加入车机热点", "是否加入？", "加入", "取消")
        SessionController.dialogSink = null
        var calledWithoutUi = false
        assertFalse(SessionController.ui.ask(spec) { calledWithoutUi = true })
        assertFalse(calledWithoutUi)

        var shown: DialogSpec? = null
        var answer: Boolean? = null
        SessionController.dialogSink = { received, onChoice ->
            shown = received
            onChoice(true)
        }
        assertTrue(SessionController.ui.ask(spec) { answer = it })
        assertSame(spec, shown)
        assertEquals(true, answer)
    }

    @Test
    fun sessionControllerWritesUiStateOnlyThroughThePort() {
        // 新代码若绕过 ui 直接写字段，抽模块时就会漏掉这条通道
        val code = SourceTree.scan("SessionController.kt").code
        assertEquals("phaseHint 的直接赋值只应出现在 ui.phase 里", 1, Regex("""\bphaseHint\s*=[^=]""").findAll(code).count())
        assertEquals(1, Regex("""\buiHintSink\?\.invoke\(""").findAll(code).count())
        assertEquals(1, Regex("""\bssidSink\?\.invoke\(""").findAll(code).count())
        assertEquals("dialogSink 只应在 ui.ask 里读取", 1, Regex("""=\s*dialogSink\b""").findAll(code).count())
    }
}
