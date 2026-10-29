package com.mcarplay.app.usb

/**
 * 能把手机 USB 控制器强制切成主机的 sysfs/debugfs 节点（需要 root）。
 *
 * 不同 SoC/内核暴露的入口不同，且是否在「手机作为供电接收端、线缆无 PD」时生效完全取决于
 * 硬件——这正是阶段 0 探测（`tools/usb_role_probe.sh`）要回答的问题。按优先级逐个尝试。
 */
data class UsbRoleNode(
    val path: String,
    val hostValue: String,
    val deviceValue: String,
    val source: Source,
) {
    enum class Source { USB_ROLE_CLASS, TYPEC_DATA_ROLE, QCOM_SSUSB_MODE, DWC3_DEBUGFS_MODE }

    fun hostCommand(): String = "echo $hostValue > $path"

    fun deviceCommand(): String = "echo $deviceValue > $path"
}

object UsbRoleNodes {
    private val patterns =
        listOf(
            Regex("/sys/class/usb_role/[^/]+/role") to Triple("host", "device", UsbRoleNode.Source.USB_ROLE_CLASS),
            Regex("/sys/class/typec/port\\d+/data_role") to Triple("host", "device", UsbRoleNode.Source.TYPEC_DATA_ROLE),
            Regex("/sys/devices/platform/soc/[^/]+\\.ssusb/mode") to Triple("host", "peripheral", UsbRoleNode.Source.QCOM_SSUSB_MODE),
            Regex("/sys/kernel/debug/usb/[^/]+\\.dwc3/mode") to Triple("host", "device", UsbRoleNode.Source.DWC3_DEBUGFS_MODE),
        )

    /** 只需遍历这些父目录即可找全候选（root 进程里用 File.listFiles 展开）。 */
    val searchRoots: List<String> =
        listOf("/sys/class/usb_role", "/sys/class/typec", "/sys/devices/platform/soc", "/sys/kernel/debug/usb")

    /** 叶子文件名：与 [searchRoots] 组合出待检查路径。 */
    val leafNames: List<String> = listOf("role", "data_role", "mode")

    /**
     * Type-C `power_role` 读回值（形如 `source [sink]`）是否表示手机正在向 VBUS 供电。
     * 切主机后若变成供电方，会与车机的 VBUS 对顶，必须立即回滚。
     */
    fun isPowerSource(powerRole: String?): Boolean = powerRole?.contains("[source]") == true

    /** 从已存在的路径里挑出候选，按 [patterns] 的优先级排序；同类内按路径排序，结果稳定。 */
    fun fromPaths(existing: Collection<String>): List<UsbRoleNode> =
        patterns.flatMap { (regex, values) ->
            existing
                .filter { regex.matches(it) }
                .sorted()
                .map { UsbRoleNode(it, values.first, values.second, values.third) }
        }
}
