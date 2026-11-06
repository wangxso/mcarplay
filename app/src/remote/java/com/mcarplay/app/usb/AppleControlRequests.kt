package com.mcarplay.app.usb

/**
 * 车机（USB 主机）发给「iPhone」的厂商控制请求的分类与应答策略。
 *
 * 编号来自公开逆向资料，**尚未在本项目实机抓到**：
 *  - 0x51：CarPlay 角色切换——请求 iPhone 改当 USB 主机、车机转为 USB 设备；
 *  - 0x40：Apple 充电电流请求（主机告知可供电流）。
 * 所有 SETUP 都应完整写日志：第一次上车/上测试台的首要产出就是核对这两个编号。
 */
object AppleControlRequests {
    const val REQUEST_ROLE_SWITCH = 0x51
    const val REQUEST_POWER = 0x40

    private const val KIND_VENDOR = 2

    enum class Kind { ROLE_SWITCH, POWER, OTHER_VENDOR, NON_VENDOR }

    fun classify(setup: UsbSetupPacket): Kind {
        if (setup.kind != KIND_VENDOR) return Kind.NON_VENDOR
        if (setup.isDeviceToHost) return Kind.OTHER_VENDOR
        return when (setup.request) {
            REQUEST_ROLE_SWITCH -> Kind.ROLE_SWITCH
            REQUEST_POWER -> Kind.POWER
            else -> Kind.OTHER_VENDOR
        }
    }

    /**
     * 应答策略。FunctionFS 的约定：OUT 请求用 read(ep0) 读完数据阶段即为 ACK（无数据时读 0 字节），
     * IN 请求用 write(ep0) 回数据；朝错误方向读写即 STALL。
     */
    fun replyTo(setup: UsbSetupPacket): Ep0Reply =
        when (classify(setup)) {
            Kind.ROLE_SWITCH, Kind.POWER -> Ep0Reply.AckOut(setup.length)
            // 未知的厂商 OUT 请求先收下（拒绝可能让车机放弃识别），并靠日志核对含义
            Kind.OTHER_VENDOR -> if (setup.isDeviceToHost) Ep0Reply.Stall else Ep0Reply.AckOut(setup.length)
            Kind.NON_VENDOR -> Ep0Reply.Stall
        }
}

sealed interface Ep0Reply {
    /** 读取 [length] 字节数据阶段（0 = 仅状态阶段）。 */
    data class AckOut(
        val length: Int,
    ) : Ep0Reply

    /** IN 请求回数据。 */
    class WriteIn(
        val data: ByteArray,
    ) : Ep0Reply

    data object Stall : Ep0Reply
}
