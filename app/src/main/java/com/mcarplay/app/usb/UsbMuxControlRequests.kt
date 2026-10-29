package com.mcarplay.app.usb

/**
 * macOS/Linux usbmuxd 在打开接口前发送的 Apple 厂商模式协商。
 *
 * `0x45` 返回当前模式；`0x52` 的单字节应答为 `0x00` 时表示设备会重枚举。
 * 本台架已经把 NCM 和 usbmux 放进同一配置，因此返回非零的 `0x01`，让 usbmuxd
 * 记录 unexpected response 后继续使用当前配置。
 */
object UsbMuxControlRequests {
    const val REQUEST_GET_MODE = 0x45
    const val REQUEST_SET_MODE = 0x52

    private const val VENDOR_DEVICE_IN = 0xC0
    private const val KEEP_CURRENT_MODE = 0x01

    fun replyTo(setup: UsbSetupPacket): Ep0Reply =
        when {
            setup.requestType == VENDOR_DEVICE_IN &&
                setup.request == REQUEST_GET_MODE &&
                setup.length == UsbMuxProtocol.INITIAL_MODE.size -> Ep0Reply.WriteIn(UsbMuxProtocol.INITIAL_MODE)
            setup.requestType == VENDOR_DEVICE_IN &&
                setup.request == REQUEST_SET_MODE &&
                setup.length == 1 -> Ep0Reply.WriteIn(byteArrayOf(KEEP_CURRENT_MODE.toByte()))
            setup.kind == 2 && !setup.isDeviceToHost -> Ep0Reply.AckOut(setup.length)
            else -> Ep0Reply.Stall
        }
}
