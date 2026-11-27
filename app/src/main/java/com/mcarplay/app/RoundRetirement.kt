package com.mcarplay.app

internal interface RoundSlots<C, S, A, B, K> {
    var client: C?
    var sender: S?
    var audio: A?
    var bridge: B?
    val context: K?

    fun clearRecovery()

    fun clearSharedSecret()

    fun clearAudioHost()

    fun clearEndedScreen() {}

    fun markCancelled(client: C)

    fun setSessionInactive()
}

internal interface RoundActions<C, S, A, B, K> {
    fun beginClient(client: C)

    fun stopAutoPlayWatch()

    fun stopSender(sender: S)

    fun quiesceSender(sender: S)

    fun closeDashboard() {}

    fun releaseWifiLock()

    fun stopScreenMonitor(context: K)

    fun setDimmerInactive()

    fun restorePhoneScreen(sleepIfCarSide: Boolean)

    fun stopAudio(audio: A)

    fun stopBridge(bridge: B)

    fun stopFormalCarLife()

    fun closeClient(client: C)
}

/**
 * 同步退役上一轮会话资源。
 *
 * 槽位读取、普通字段写入和资源叶子均沿用调用者语义；本类不新增锁、调度或异常处理。
 */
internal class RoundRetirement<C : Any, S : Any, A : Any, B : Any, K : Any>(
    private val slots: RoundSlots<C, S, A, B, K>,
    private val actions: RoundActions<C, S, A, B, K>,
) {
    fun retire(
        closeClient: Boolean,
        preserveScreenSocket: Boolean,
    ) {
        slots.client?.let(actions::beginClient)
        slots.clearEndedScreen()
        slots.clearRecovery()
        actions.stopAutoPlayWatch()
        val retiringSender = slots.sender
        slots.sender = null
        if (preserveScreenSocket) {
            retiringSender?.let(actions::quiesceSender)
        } else {
            retiringSender?.let(actions::stopSender)
        }
        actions.closeDashboard()
        actions.releaseWifiLock()
        val context = slots.context
        context?.let(actions::stopScreenMonitor)
        actions.setDimmerInactive()
        actions.restorePhoneScreen(false)
        val retiringAudio = slots.audio
        retiringAudio?.let(actions::stopAudio)
        slots.audio = null
        val retiringBridge = slots.bridge
        retiringBridge?.let(actions::stopBridge)
        slots.bridge = null
        slots.clearSharedSecret()
        slots.clearAudioHost()
        actions.stopFormalCarLife()
        slots.client?.let(slots::markCancelled)
        if (closeClient) {
            slots.client?.let(actions::closeClient)
        }
        slots.client = null
        slots.setSessionInactive()
    }
}
