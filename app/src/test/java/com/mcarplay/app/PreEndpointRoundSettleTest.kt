package com.mcarplay.app

import com.mcarplay.app.diagnostics.ConnectionStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归（bug #66）：端点交付前失败的 BT 轮次必须被收尾并计入熔断。
 *
 * 保时捷退化 0x1d01 形态下，每轮卡在 iAP2 协商超时后只写一行 `SESSION 失败`：
 * 时间线不结束、`elapsedMs` 一路累加（报告里 BLOCKED_AT BLUETOOTH:WIFI_INFO 224s+），
 * 熔断也看不到这些失败，自动重连可以无限重试。
 */
class PreEndpointRoundSettleTest {
    @Test
    fun aWaitingRoundStillInTheBluetoothStageIsSettled() {
        assertTrue(settlesOnPreEndpointFailure(status = "waiting", stage = ConnectionStage.BLUETOOTH))
    }

    @Test
    fun roundsThatAlreadyMovedPastBluetoothAreNotTouched() {
        // 端点已交付（进入 WIFI/DISCOVERY 及以后）的轮次由 onLinkClosed 负责，
        // 这里不能再结束一次，否则会把仍在进行的入网/配对误记成失败。
        listOf(
            ConnectionStage.WIFI,
            ConnectionStage.DISCOVERY,
            ConnectionStage.PAIRING,
            ConnectionStage.SETUP,
            ConnectionStage.CARWITH,
            ConnectionStage.STREAMING,
        ).forEach { stage ->
            assertFalse("$stage 阶段不应被预端点收尾覆盖", settlesOnPreEndpointFailure("waiting", stage))
        }
    }

    @Test
    fun alreadyFinishedRoundsAreNotOverwritten() {
        listOf("failed", "stopped", "streaming").forEach { status ->
            assertFalse(
                "status=$status 的轮次不应被预端点收尾覆盖",
                settlesOnPreEndpointFailure(status, ConnectionStage.BLUETOOTH),
            )
        }
    }

    @Test
    fun precheckStageIsLeftAlone() {
        // PRECHECK 是权限/前置检查，不是 BT 轮次；它的失败有自己的错误码。
        assertFalse(settlesOnPreEndpointFailure("waiting", ConnectionStage.PRECHECK))
        assertFalse(settlesOnPreEndpointFailure("waiting", ConnectionStage.SELF_TEST))
    }

    /**
     * 空端点（车机声明会发 0x4301 但窗口内未到）也停在 waiting+BLUETOOTH，
     * 但那是**有意**等待晚到端点（奥迪 MMI 9310 实测晚 2 分 35 秒）。
     * 结束它会把一条正在正常等待的链路误记成失败，并可能触发熔断。
     */
    @Test
    fun aRoundDeliberatelyWaitingForALateEndpointIsNotSettled() {
        assertFalse(
            settlesOnPreEndpointFailure(
                status = "waiting",
                stage = ConnectionStage.BLUETOOTH,
                waitingForLateEndpoint = true,
            ),
        )
        assertTrue(
            settlesOnPreEndpointFailure(
                status = "waiting",
                stage = ConnectionStage.BLUETOOTH,
                waitingForLateEndpoint = false,
            ),
        )
    }

    /**
     * 还有别的 BT 链路活着时不能判定：无法证明这次失败属于正在等待的那一轮，
     * 误判会把别的链路的失败记到当前轮次头上并误触发熔断。
     */
    @Test
    fun anotherLiveLinkSuppressesTheSettle() {
        assertFalse(
            settlesOnPreEndpointFailure(
                status = "waiting",
                stage = ConnectionStage.BLUETOOTH,
                anotherLinkLive = true,
            ),
        )
        assertTrue(
            settlesOnPreEndpointFailure(
                status = "waiting",
                stage = ConnectionStage.BLUETOOTH,
                anotherLinkLive = false,
            ),
        )
    }
}
