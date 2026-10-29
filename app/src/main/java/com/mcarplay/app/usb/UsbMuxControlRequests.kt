package com.mcarplay.app.usb

/**
 * macOS/Linux usbmuxd 在打开接口前发送的 Apple 厂商模式协商。
 *
 * `0x45` 返回当前模式；`0x52` 的单字节应答为 `0x00` 时表示设备会重枚举。
 * 本台架已经把 NCM 和 usbmux 放进同一配置，因此对 usbmuxd 的 NCM 模式（`wIndex=3`）
 * 返回非零的 `0x01`，让它记录 unexpected response 后继续使用当前配置。
 *
 * 车机/DiPlay（LIVI 路径）发送 CarPlay 模式 `wIndex=4` 后关闭连接并等待 iPhone 重枚举；
 * 不重枚举会让它一直停在 WaitingForReenumeration。调用方允许时对该请求返回 `0x00`，
 * 并由 root 进程通过 UDC soft_connect 模拟一次重枚举。
 */
object UsbMuxControlRequests {
    const val REQUEST_GET_MODE = 0x45
    const val REQUEST_SET_MODE = 0x52
    const val CARPLAY_MODE_INDEX = 0x0004

    private const val VENDOR_DEVICE_IN = 0xC0
    private const val KEEP_CURRENT_MODE = 0x01
    private const val WILL_REENUMERATE = 0x00

    /** 该请求是否为车机发出的 CarPlay 模式切换（需要真实重枚举才能继续）。 */
    fun isCarPlayModeRequest(setup: UsbSetupPacket): Boolean =
        setup.requestType == VENDOR_DEVICE_IN &&
            setup.request == REQUEST_SET_MODE &&
            setup.index == CARPLAY_MODE_INDEX &&
            setup.length == 1

    fun replyTo(
        setup: UsbSetupPacket,
        reenumerateForCarPlay: Boolean = false,
    ): Ep0Reply =
        when {
            setup.requestType == VENDOR_DEVICE_IN &&
                setup.request == REQUEST_GET_MODE &&
                setup.length == UsbMuxProtocol.INITIAL_MODE.size -> Ep0Reply.WriteIn(UsbMuxProtocol.INITIAL_MODE)
            reenumerateForCarPlay && isCarPlayModeRequest(setup) -> Ep0Reply.WriteIn(byteArrayOf(WILL_REENUMERATE.toByte()))
            setup.requestType == VENDOR_DEVICE_IN &&
                setup.request == REQUEST_SET_MODE &&
                setup.length == 1 -> Ep0Reply.WriteIn(byteArrayOf(KEEP_CURRENT_MODE.toByte()))
            setup.kind == 2 && !setup.isDeviceToHost -> Ep0Reply.AckOut(setup.length)
            else -> Ep0Reply.Stall
        }
}
