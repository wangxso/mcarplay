package com.mcarplay.app.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：取消/跨轮时，热点确认弹窗必须被**撤掉**，而不只是停止等待。
 *
 * `WifiPromptBroker` 负责让等待提前返回（见 `WifiPromptBrokerTest.awaitStopsPromptlyWhenTheRoundIsCancelled`），
 * 但它不持有 AlertDialog：等待线程退出后，屏幕上的框仍存在、点了也没有消费者。
 * 因此 `askWifiJoinChoice` 必须在提前退出时调用 `dialogDismissSink`。
 *
 * 这段逻辑依赖 `Handler`/`Looper` 与单例状态，JVM 单测无法直接驱动，
 * 按仓库既有做法（`ArchitectureRulesTest`、`LogContractTest`）用源码契约钉住。
 */
class HotspotDialogDismissTest {
    private val source: String by lazy {
        SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    }

    /** 取 `askWifiJoinChoice` 的函数体：先配平参数表圆括号，再配平函数体花括号。 */
    private fun askBody(): String {
        val start = source.indexOf("private fun askWifiJoinChoice(")
        check(start >= 0) { "找不到 askWifiJoinChoice" }
        var parens = 0
        var i = source.indexOf('(', start)
        while (i < source.length) {
            when (source[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            i++
        }
        val open = source.indexOf('{', i)
        var depth = 0
        var j = open
        while (j < source.length) {
            when (source[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, j + 1)
                }
            }
            j++
        }
        error("askWifiJoinChoice 的函数体没有闭合")
    }

    @Test
    fun dismissSinkIsInvokedWhenTheWaitEndsEarly() {
        val body = askBody()
        assertTrue(
            "提前退出必须按 requestId 撤框，否则取消后界面上留着无人消费的确认框",
            body.contains("dialogDismissSink?.invoke(requestId)"),
        )
        assertTrue("撤框必须放在 finally 里，异常路径也要撤", body.contains("finally"))
        assertTrue(
            "已应答的弹窗不应再被撤掉（避免误关用户刚点过的框）",
            body.contains("if (!answered.get())"),
        )
    }

    @Test
    fun dismissSinkIsWiredAndClearedByTheHost() {
        val activity = SourceTree.mainKotlinFiles.getValue("MainActivity.kt").readText()
        assertTrue("MainActivity 必须注册 dialogDismissSink", activity.contains("SessionController.dialogDismissSink ="))
        assertTrue("销毁时必须解绑，避免持有已销毁的 Activity", activity.contains("SessionController.dialogDismissSink = null"))
        assertTrue(
            "弹窗必须按 requestId 登记，撤框才能只关对应那一个",
            activity.contains("dialogRequestIds[spec.requestId] = dialog"),
        )
    }
}
