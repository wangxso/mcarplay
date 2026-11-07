package com.mcarplay.app.usb

/**
 * 以 root 身份通过 configfs 临时替换 Android 自己的 USB gadget 的 shell 计划（纯函数，可单测）。
 *
 * 顺序约束：FunctionFS 必须先由用户态向 ep0 写入描述符，才能绑定 UDC；所以计划拆成
 * [prepare]（建 gadget、挂载 functionfs）→ 写描述符 → [bind] → … → [teardown]。
 *
 * 停掉 `sys.usb.config` 后 USB adb 会断开，台架调试请改用无线 adb。
 */
class GadgetPlan(
    private val identity: AppleUsbIdentity,
    val gadgetName: String = "mcarplay",
    val functionName: String = "mcarplay_iap",
) {
    val gadgetDir: String get() = "$CONFIGFS_ROOT/$gadgetName"
    val ffsMountDir: String get() = "$FFS_ROOT/$functionName"
    val ep0Path: String get() = "$ffsMountDir/ep0"

    private val configDir get() = "$gadgetDir/configs/c.1"
    private val functionDir get() = "$gadgetDir/functions/ffs.$functionName"

    fun prepare(): List<String> =
        listOf(
            // 旧式机型由 init 按 sys.usb.config 解绑；Gadget HAL 机型（小米 17 实测 sys.usb.config=none，
            // 有 g1、g2）不认这个属性，所以再把任何占着 UDC 的 gadget 显式解绑
            "setprop sys.usb.config none",
            "sleep 1",
            "for g in $CONFIGFS_ROOT/*; do [ \"\$g\" = $gadgetDir ] && continue; " +
                "[ -n \"\$(cat \"\$g/UDC\" 2>/dev/null)\" ] && echo '' > \"\$g/UDC\"; done; true",
            "mkdir -p $gadgetDir",
            "echo ${hex(identity.vendorId)} > $gadgetDir/idVendor",
            "echo ${hex(identity.productId)} > $gadgetDir/idProduct",
            "echo 0x0200 > $gadgetDir/bcdUSB",
            "echo ${hex(identity.bcdDevice)} > $gadgetDir/bcdDevice",
            "mkdir -p $gadgetDir/strings/0x409",
            "echo ${quote(identity.manufacturer)} > $gadgetDir/strings/0x409/manufacturer",
            "echo ${quote(identity.product)} > $gadgetDir/strings/0x409/product",
            "echo ${quote(identity.serialNumber)} > $gadgetDir/strings/0x409/serialnumber",
            "mkdir -p $configDir/strings/0x409",
            "echo 'iAP' > $configDir/strings/0x409/configuration",
            "echo ${identity.maxPowerMilliAmps} > $configDir/MaxPower",
            "mkdir -p $functionDir",
            "[ -e $configDir/ffs.$functionName ] || ln -s $functionDir $configDir/",
            "mkdir -p $ffsMountDir",
            "mount -t functionfs $functionName $ffsMountDir",
        )

    fun bind(udc: String): List<String> {
        require(UDC_NAME.matches(udc)) { "UDC 名称异常：$udc" }
        return listOf("echo $udc > $gadgetDir/UDC")
    }

    /**
     * 拆除本 gadget 并把 USB 交还 Android。每步都容错（`|| true`），以便从任意半成品状态回滚。
     *
     * 交还时先按旧式机型写回 `sys.usb.config`（仅当进入前的值有意义），再让 Gadget HAL 重建自己的 gadget
     * （`svc usb resetUsbGadget`，Android 12+）。不再猜测默认的 `mtp,adb`：HAL 机型上它会触发 init 的旧路径。
     *
     * @param previousUsbConfig 进入前 `getprop sys.usb.config` 的值；空或 `none` 时只重置 HAL。
     * @param restoreAndroid 角色切换成功、手机即将当主机时为 false：此时不能把 Android gadget 绑回去。
     */
    fun teardown(
        previousUsbConfig: String,
        restoreAndroid: Boolean = true,
    ): List<String> {
        val commands =
            mutableListOf(
                "[ -e $gadgetDir/UDC ] && echo '' > $gadgetDir/UDC || true",
                "umount $ffsMountDir 2>/dev/null || true",
                "rmdir $ffsMountDir 2>/dev/null || true",
                "rm -f $configDir/ffs.$functionName",
                "rmdir $configDir/strings/0x409 $configDir $functionDir $gadgetDir/strings/0x409 $gadgetDir 2>/dev/null || true",
            )
        if (restoreAndroid) {
            previousUsbConfig.trim().takeIf { USB_CONFIG.matches(it) && it != "none" }?.let {
                commands += "setprop sys.usb.config $it"
            }
            commands += "svc usb resetUsbGadget 2>/dev/null || true"
        }
        return commands
    }

    companion object {
        const val CONFIGFS_ROOT = "/config/usb_gadget"
        const val FFS_ROOT = "/dev/usb-ffs"

        private val UDC_NAME = Regex("[A-Za-z0-9._:-]+")
        private val USB_CONFIG = Regex("[a-z0-9_,]+")

        fun hex(value: Int): String = "0x%04x".format(value)

        /** POSIX sh 单引号转义。 */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
