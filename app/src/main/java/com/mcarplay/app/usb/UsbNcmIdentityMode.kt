package com.mcarplay.app.usb

/**
 * USB NCM 台架向主机声明的设备身份。
 *
 * [IPHONE_COMPAT] 只复用 Apple 的 USB 枚举特征，让 MacPlay 的设备列表能识别当前台架；
 * 它不实现 usbmux、lockdown 或 carkit。若该身份导致 NCM 无法枚举，连接器会退回 [GENERIC]。
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
