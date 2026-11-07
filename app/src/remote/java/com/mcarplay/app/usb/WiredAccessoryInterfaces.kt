package com.mcarplay.app.usb

/**
 * 角色切换后车机作为 USB 设备暴露的接口摘要（与 android.hardware.usb 解耦，便于单测）。
 */
data class UsbInterfaceSummary(
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointSummary>,
)

data class UsbEndpointSummary(
    val address: Int,
    /** USB 传输类型：0 控制，1 同步，2 bulk，3 中断。 */
    val type: Int,
) {
    val isIn: Boolean get() = address and 0x80 != 0
    val isBulk: Boolean get() = type == 2
}

/** 有线 CarPlay 需要的两类接口：iAP2 bulk 通道与 CDC-NCM 网卡（AirPlay 跑在其上）。 */
data class WiredAccessoryInterfaces(
    val iap2: UsbInterfaceSummary?,
    val ncmControl: UsbInterfaceSummary?,
    val ncmData: UsbInterfaceSummary?,
) {
    companion object {
        private const val CLASS_VENDOR = 0xFF
        private const val SUBCLASS_IAP = 0xF0
        private const val CLASS_COMM = 0x02
        private const val SUBCLASS_NCM = 0x0D
        private const val CLASS_CDC_DATA = 0x0A

        /**
         * iAP2 接口优先认厂商类 + 子类 0xF0（待实机核对）；找不到时退而求其次，
         * 取第一个「厂商类且恰好一对 bulk IN/OUT」的接口，并由调用方在日志里标明是猜测。
         */
        fun match(interfaces: List<UsbInterfaceSummary>): WiredAccessoryInterfaces {
            val iap2 =
                interfaces.firstOrNull { it.interfaceClass == CLASS_VENDOR && it.interfaceSubclass == SUBCLASS_IAP && it.hasBulkPair() }
                    ?: interfaces.firstOrNull { it.interfaceClass == CLASS_VENDOR && it.hasBulkPair() }
            val ncmControl = interfaces.firstOrNull { it.interfaceClass == CLASS_COMM && it.interfaceSubclass == SUBCLASS_NCM }
            val ncmData =
                ncmControl?.let { control ->
                    interfaces.firstOrNull { it.id > control.id && it.interfaceClass == CLASS_CDC_DATA && it.hasBulkPair() }
                }
            return WiredAccessoryInterfaces(iap2, ncmControl, ncmData)
        }

        private fun UsbInterfaceSummary.hasBulkPair(): Boolean =
            endpoints.count { it.isBulk && it.isIn } == 1 && endpoints.count { it.isBulk && !it.isIn } == 1
    }
}
