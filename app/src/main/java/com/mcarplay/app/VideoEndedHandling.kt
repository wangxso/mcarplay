package com.mcarplay.app

internal interface VideoEndSlots<S : Any, T : Any, B : Any, K : Any> {
    val currentSender: S?

    val outputSender: S?

    val nativeBridge: B?

    val btClient: T?

    val context: K?

    val forceKeyFrames: Int

    val requestUi: Int

    val touches: Int

    val consumerReleases: Int

    fun clearCarLifeOutput()

    fun clearNativeBridge()

    fun setSessionInactive()

    fun setDimmerInactive()

    fun resetEnvironmentSampleAt()
}

internal interface VideoEndCapture<S : Any, L : Any> {
    val senderIdentity: () -> S

    val carLifeBinding: () -> L?

    val mediaObservation: () -> Any?

    val attempt: String

    val peerAddress: String?

    fun closeDashboard() {}
}

internal interface VideoEndActions<S : Any, L : Any, T : Any, B : Any, K : Any> {
    fun log(message: String)

    fun videoEndedTimeline(attempt: String)

    fun mediaEnded(observation: Any?)

    fun stopCarLife(carLife: L)

    fun releaseCarLife(carLife: L)

    fun releaseWifiLock()

    fun stopScreenMonitor(context: K)

    fun stopNativeBridge(bridge: B)

    fun armBluetoothAfterSessionEnd(btClient: T)

    fun restorePhoneScreen(sleepIfCarSide: Boolean)

    fun cleanupShellFallbackNetwork()

    fun attributeAudioTeardown()

    fun recordAutoReconnectOutcome(
        attempt: String?,
        peer: String?,
    ): Boolean

    fun disconnect(context: K)
}

/**
 * 同步处理真实视频流结束。
 *
 * 槽位读取、延迟捕获读取和资源叶子均沿用调用者语义；本类不新增锁、调度或异常处理。
 */
internal class VideoEndedHandling<S : Any, L : Any, T : Any, B : Any, K : Any>(
    private val slots: VideoEndSlots<S, T, B, K>,
    private val actions: VideoEndActions<S, L, T, B, K>,
) {
    fun handle(capture: VideoEndCapture<S, L>) {
        val sender = capture.senderIdentity()
        if (slots.currentSender !== sender) return

        actions.log(
            "SESSION: 本轮车机交互统计 forceKeyFrame=${slots.forceKeyFrames} requestUI=${slots.requestUi} " +
                "触摸报文=${slots.touches} 媒体键释放=${slots.consumerReleases}",
        )
        slots.resetEnvironmentSampleAt()
        actions.videoEndedTimeline(capture.attempt)
        actions.mediaEnded(capture.mediaObservation())
        capture.carLifeBinding()?.let { carLife ->
            actions.stopCarLife(carLife)
            actions.releaseCarLife(carLife)
        }
        if (slots.outputSender === capture.senderIdentity()) slots.clearCarLifeOutput()
        capture.closeDashboard()
        actions.releaseWifiLock()
        slots.context?.let(actions::stopScreenMonitor)
        slots.nativeBridge?.let(actions::stopNativeBridge)
        slots.clearNativeBridge()
        actions.log(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
        slots.btClient?.let(actions::armBluetoothAfterSessionEnd)
        slots.setSessionInactive()
        slots.setDimmerInactive()
        actions.restorePhoneScreen(sleepIfCarSide = true)
        actions.cleanupShellFallbackNetwork()
        actions.attributeAudioTeardown()
        if (actions.recordAutoReconnectOutcome(capture.attempt, capture.peerAddress)) {
            slots.context?.let(actions::disconnect)
        }
    }
}
