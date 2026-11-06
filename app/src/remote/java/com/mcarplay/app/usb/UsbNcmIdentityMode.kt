package com.mcarplay.app.usb

/**
 * USB NCM 台架向主机声明的设备身份。
 *
 * [IPHONE_COMPAT] 复用 Apple 的 USB 枚举特征，并由同一声明暴露最小
 * usbmux、lockdown 和 carkit 服务。连接器只在网卡本身无法枚举时退回 [GENERIC]；
 * 网卡已出现但地址尚未就绪不会重建 gadget，以免破坏已经完成的 Apple 设备枚举。
 */
enum class UsbNcmIdentityMode(
    val wireValue: String,
    val displayName: String,
) {
    IPHONE_COMPAT("iphone-ui", "iPhone 兼容身份"),
    GENERIC("generic", "Linux NCM 身份"),
    ;

    companion object {
        fun attempts(preferIphoneIdentity: Boolean): List<UsbNcmIdentityMode> =
            if (preferIphoneIdentity) {
                listOf(IPHONE_COMPAT, GENERIC)
            } else {
                listOf(GENERIC)
            }
    }
}
