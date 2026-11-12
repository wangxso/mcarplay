package com.mcarplay.app.diagnostics

/**
 * 阶段推进规则。TCP 重连可能发生在 SETUP 已开始之后；AIRPLAY_CONNECTED 只允许把
 * 尚未到达 PAIRING 的阶段推进到 PAIRING，不能把 SETUP/CARWITH 等阶段退回。
 */
internal object ConnectionStagePolicy {
    fun shouldEnterPairing(current: ConnectionStage?): Boolean = current == null || current.ordinal < ConnectionStage.PAIRING.ordinal
}
