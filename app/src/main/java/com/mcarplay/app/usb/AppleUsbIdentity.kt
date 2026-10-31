package com.mcarplay.app.usb

import java.security.SecureRandom

/**
 * 有线 CarPlay（root 实验）：手机以 USB 设备身份冒充 iPhone 时使用的描述符参数。
 *
 * 车机靠 Apple VID 判断「插进来的是 iPhone」，随后才会发角色切换请求。PID、bcdDevice
 * 与接口类别**均未与实机抓包核对**：上车前必须用 `lsusb -v` 对照真 iPhone 或有线转无线
 * 盒子（它对车机冒充的就是 iPhone）修正。见 `docs/WIRED-CARPLAY.md`。
 */
data class AppleUsbIdentity(
    val vendorId: Int = APPLE_VENDOR_ID,
    val productId: Int = DEFAULT_IPHONE_PRODUCT_ID,
    val bcdDevice: Int = 0x0000,
    val manufacturer: String = "Apple Inc.",
    val product: String = "iPhone",
    val serialNumber: String = randomSerial(),
    /** iAP 接口：厂商自定义类（0xFF），子类 0xF0（待核对）。 */
    val iapInterfaceClass: Int = 0xFF,
    val iapInterfaceSubclass: Int = 0xF0,
    val iapInterfaceProtocol: Int = 0x00,
    val iapInterfaceName: String = "iAP Interface",
    /** 设备声明的最大取电（mA）；configfs 的 MaxPower 单位就是 mA。 */
    val maxPowerMilliAmps: Int = 500,
) {
    init {
        require(vendorId in 0..0xFFFF && productId in 0..0xFFFF && bcdDevice in 0..0xFFFF) { "USB ID 超出 16 位" }
        require(serialNumber.isNotEmpty() && serialNumber.all { it.isLetterOrDigit() }) { "序列号只能是字母数字" }
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05AC

        /** 常见 iPhone PID（待核对；不同机型/接口可能不同）。 */
        const val DEFAULT_IPHONE_PRODUCT_ID = 0x12A8

        /** iPhone 的 USB 序列号形似 40 位十六进制 UDID；每次随机，不使用本机任何标识。 */
        fun randomSerial(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(20).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
